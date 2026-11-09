"""API の入口。警報 → 避難先を選ぶ → Gemini が説明、の流れと、オフライン用のパックを返す。"""
from dataclasses import asdict
from datetime import date, datetime, timezone
from pathlib import Path

from fastapi import FastAPI, HTTPException, Query
from fastapi.middleware.gzip import GZipMiddleware
from fastapi.responses import FileResponse, Response
from pydantic import BaseModel, Field

from .alerts import current_alert, start_replay
from .explain import explain
from .pack import ANSWERS_JA, audio_wav, build_pack
from .planner import plan
from .routes import safe_route

STATIC = Path(__file__).resolve().parent / "static"
AREAS = {"osaka-bay"}
app = FastAPI(title="津波避難ナビ")
app.add_middleware(GZipMiddleware, minimum_size=1000)


class PlanRequest(BaseModel):
    lat: float = Field(ge=34.0, le=35.2)
    lon: float = Field(ge=134.9, le=135.8)
    lang: str = "ja"
    slow: bool = False


@app.get("/")
def index() -> FileResponse:
    return FileResponse(STATIC / "index.html")


@app.get("/api/alert")
def get_alert() -> dict:
    return _alert_dict()


@app.get("/api/alert/training")
def get_training_alert() -> dict:
    return _alert_dict()


@app.post("/api/alert/replay")
def replay_alert() -> dict:
    start_replay(datetime.now(timezone.utc))
    return _alert_dict()


@app.post("/api/plan")
def make_plan(req: PlanRequest) -> dict:
    alert = _alert_dict()
    result = plan(req.lat, req.lon, alert["minutes_left"], slow=req.slow)
    try:
        route = safe_route(req.lat, req.lon, result["primary"])
    except Exception as e:
        route = {"error": str(e)}
    result["route"] = route
    result["waypoints"] = (route or {}).get("waypoints", [])
    return {"alert": alert, "plan": result, "explanation": explain(alert, result, req.lang)}


@app.get("/api/pack")
def get_pack(area: str = "osaka-bay", lang: str = "en", expires_at: str | None = None) -> dict:
    if area not in AREAS:
        raise HTTPException(404, f"対応していない地域です: {area}")
    return build_pack(area, lang, date.today().isoformat(), expires_at)


@app.get("/api/pack/audio")
def get_pack_audio(lang: str = "en", id: str = Query(...)) -> Response:
    if id not in ANSWERS_JA:
        raise HTTPException(404, f"答えの id がありません: {id}")
    return Response(audio_wav(lang, id), media_type="audio/wav")


def _alert_dict() -> dict:
    now = datetime.now(timezone.utc)
    a, replaying = current_alert(now)
    if a is None:
        raise HTTPException(500, "訓練電文に大阪府の情報がありません")
    d = asdict(a)
    d["minutes_left"] = round(a.minutes_left(now if replaying else None), 1)
    d["active"] = True
    d["area_code"] = "510"
    d["arrival_time"] = a.arrival_at.isoformat() if a.arrival_at else None
    d["is_training"] = True
    d["replaying"] = replaying
    return d
