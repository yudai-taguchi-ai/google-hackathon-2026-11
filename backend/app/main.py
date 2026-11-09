"""API の入口。警報 → 避難先を選ぶ → Gemini が説明、の流れと、オフライン用のパックを返す。

到達までの時刻は、どこで計算しても同じ順で決める（docs/modes.md）:
  1. 気象庁の到達予想時刻（サーバーが受け取っている警報。デモは訓練電文の流し直し）
  2. 端末が前に受け取った到達予想時刻（arrival_ms）
  3. 「揺れた」を押した時刻 + 26 分（shake_at_ms）
  4. どれも無ければ、今 + 26 分
"""
import time
from dataclasses import asdict
from datetime import date, datetime, timezone
from pathlib import Path

from fastapi import FastAPI, HTTPException, Query
from fastapi.middleware.gzip import GZipMiddleware
from fastapi.responses import FileResponse, Response
from pydantic import BaseModel, Field

from .alerts import current_alert, live_alert, start_replay
from .explain import explain
from .pack import ANSWERS_JA, ARRIVAL_DEFAULT_MIN, audio_wav, build_pack
from .planner import plan
from .routes import safe_route

STATIC = Path(__file__).resolve().parent / "static"
AREAS = {"osaka-bay"}
app = FastAPI(title="津波避難ナビ")
app.add_middleware(GZipMiddleware, minimum_size=1000)


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
    explanation = explain(alert, result, req.lang) if req.explain else None
    return {"alert": alert, "plan": result, "explanation": explanation}


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
