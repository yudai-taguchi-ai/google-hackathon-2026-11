"""API の入口。警報 → 避難先を選ぶ → Gemini が説明、の流れと、オフライン用のパックを返す。

到達までの時刻は、どこで計算しても同じ順で決める（docs/modes.md）:
  1. 気象庁の到達予想時刻（サーバーが受け取っている警報。デモは訓練電文の流し直し）
  2. 端末が前に受け取った到達予想時刻（arrival_ms）
  3. 「揺れた」を押した時刻 + 26 分（shake_at_ms）
  4. どれも無ければ、今 + 26 分
"""
import threading
import time
import uuid
from collections import OrderedDict
from dataclasses import asdict
from datetime import date, datetime, timezone
from pathlib import Path

from fastapi import FastAPI, HTTPException, Query
from fastapi.middleware.gzip import GZipMiddleware
from fastapi.responses import FileResponse, Response
from pydantic import BaseModel, Field

from .alerts import current_alert, live_alert, start_replay
from .explain import explain
from .flood import flood_in
from .pack import ANSWERS_JA, ARRIVAL_DEFAULT_MIN, audio_wav, build_pack
from .planner import _inundation, plan, shelters
from .routes import safe_route

STATIC = Path(__file__).resolve().parent / "static"
AREAS = {"osaka-bay"}
app = FastAPI(title="津波避難ナビ")
app.add_middleware(GZipMiddleware, minimum_size=1000)

# 説明を後から作るために、計算した結果を少しの間だけ覚えておく（Cloud Run は 1 台で動かしている）
_PLANS: "OrderedDict[str, tuple[dict, dict, str]]" = OrderedDict()
_PLANS_MAX = 500
_plans_lock = threading.Lock()


def _remember(alert: dict, result: dict, lang: str) -> str:
    plan_id = uuid.uuid4().hex
    with _plans_lock:
        _PLANS[plan_id] = (alert, result, lang)
        while len(_PLANS) > _PLANS_MAX:
            _PLANS.popitem(last=False)
    return plan_id


@app.on_event("startup")
def _warm_up() -> None:
    """最初の人を待たせないよう、避難先と浸水想定の読み込みを起動直後に裏で済ませる。"""
    threading.Thread(target=lambda: (shelters(), _inundation()), daemon=True).start()


class PlanRequest(BaseModel):
    lat: float = Field(ge=34.0, le=35.2)
    lon: float = Field(ge=134.9, le=135.8)
    lang: str = "en"
    slow: bool = False
    arrival_ms: int | None = None
    shake_at_ms: int | None = None
    exclude_ids: list[str] = []
    explain: bool = True


@app.get("/")
def index() -> FileResponse:
    return FileResponse(STATIC / "index.html")


@app.get("/api/alert")
def get_alert() -> dict:
    return _alert_dict()


@app.post("/api/alert/replay")
def replay_alert() -> dict:
    start_replay(datetime.now(timezone.utc))
    return _alert_dict()


@app.post("/api/plan")
def make_plan(req: PlanRequest) -> dict:
    alert = _alert_dict()
    minutes_left, time_source = _minutes_left(alert, req)
    result = plan(req.lat, req.lon, minutes_left, slow=req.slow, exclude=set(req.exclude_ids))
    try:
        route = safe_route(req.lat, req.lon, result["primary"])
    except Exception as e:
        route = {"error": str(e)}
    result["route"] = route
    result["waypoints"] = (route or {}).get("waypoints", [])
    result["time_source"] = time_source
    result["plan_id"] = _remember(alert, result, req.lang)
    explanation = explain(alert, result, req.lang) if req.explain else None
    return {"alert": alert, "plan": result, "explanation": explanation}


@app.get("/api/explain")
def get_explain(plan_id: str) -> dict:
    """先に返した避難先を、Gemini が説明する（Web は避難先を先に出し、説明を後から出す）。"""
    with _plans_lock:
        item = _PLANS.get(plan_id)
    if item is None:
        raise HTTPException(404, "plan not found")
    return explain(*item)


@app.get("/api/flood")
def get_flood(south: float, west: float, north: float, east: float) -> dict:
    """地図に塗る浸水想定区域（見ている範囲だけ）。出典: 国土数値情報 津波浸水想定（大阪府 2023年版）を簡略化"""
    if not (south < north and west < east):
        raise HTTPException(400, "範囲が正しくありません")
    return flood_in(south, west, north, east)


@app.get("/api/pack")
def get_pack(area: str = "osaka-bay", lang: str = "en", expires_at: str | None = None) -> dict:
    if area not in AREAS:
        raise HTTPException(404, f"対応していない地域です: {area}")
    return build_pack(area, lang, date.today().isoformat(), expires_at)


@app.get("/api/pack/graph")
def get_pack_graph() -> FileResponse:
    """歩ける道のグラフ（圏外で道に沿った距離を出すため）。© OpenStreetMap contributors（ODbL）"""
    return FileResponse(Path(__file__).resolve().parent.parent / "data" / "walk_graph.bin", media_type="application/octet-stream")


@app.get("/api/pack/audio")
def get_pack_audio(lang: str = "en", id: str = Query(...)) -> Response:
    if id not in ANSWERS_JA:
        raise HTTPException(404, f"答えの id がありません: {id}")
    return Response(audio_wav(lang, id), media_type="audio/wav")


def _minutes_left(alert: dict, req: PlanRequest) -> tuple[float, str]:
    now_ms = time.time() * 1000
    if alert["active"]:
        return alert["minutes_left"], "jma_alert"
    if req.arrival_ms:
        return max(0.0, (req.arrival_ms - now_ms) / 60_000), "cached_alert"
    if req.shake_at_ms:
        return max(0.0, (req.shake_at_ms + ARRIVAL_DEFAULT_MIN * 60_000 - now_ms) / 60_000), "shake_plus_26"
    return float(ARRIVAL_DEFAULT_MIN), "default_26"


def _alert_dict() -> dict:
    """今の警報。順番: デモの訓練電文（流し直し中）＞ 気象庁の本物の電文（大阪府 510）＞ 警報なし。"""
    now = datetime.now(timezone.utc)
    jma = live_alert(now)
    jma_info = {"jma_checked_at": jma.get("checked_at"), "jma_error": jma.get("error"), "jma_reason": jma.get("reason")}
    a, replaying = current_alert(now)
    if replaying and a is not None:
        return {**_from_alert(a, now), "is_training": True, "replaying": True, "source": "training_replay", **jma_info}
    if jma.get("active"):
        return {**_from_alert(jma["alert"], now), "is_training": False, "replaying": False, "source": "jma_live", **jma_info}
    return {"active": False, "is_training": False, "replaying": False, "source": "jma_live", "kind": None, "area_name": None,
            "area_code": "510", "arrival_time": None, "minutes_left": None, "max_height": None, **jma_info}


def _from_alert(a, now: datetime) -> dict:
    d = asdict(a)
    d["minutes_left"] = round(a.minutes_left(now), 1)
    d["active"] = True
    d["area_code"] = "510"
    d["arrival_time"] = a.arrival_at.isoformat() if a.arrival_at else None
    for k in ("reported_at", "arrival_at"):
        d[k] = d[k].isoformat() if d[k] else None
    return d
