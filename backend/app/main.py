"""API の入口。警報 → 避難先を選ぶ → Gemini が説明、の流れを1回の呼び出しで返す。"""
from dataclasses import asdict
from pathlib import Path

from fastapi import FastAPI, HTTPException
from fastapi.responses import FileResponse
from pydantic import BaseModel, Field

from .alerts import training_alert
from .explain import explain
from .planner import plan

STATIC = Path(__file__).resolve().parent / "static"
app = FastAPI(title="津波避難ナビ")


class PlanRequest(BaseModel):
    lat: float = Field(ge=34.0, le=35.2)
    lon: float = Field(ge=134.9, le=135.8)
    lang: str = "ja"
    slow: bool = False


@app.get("/")
def index() -> FileResponse:
    return FileResponse(STATIC / "index.html")


@app.get("/api/alert/training")
def get_training_alert() -> dict:
    return _alert_dict()


@app.post("/api/plan")
def make_plan(req: PlanRequest) -> dict:
    alert = _alert_dict()
    result = plan(req.lat, req.lon, alert["minutes_left"], slow=req.slow)
    return {"alert": alert, "plan": result, "explanation": explain(alert, result, req.lang)}


def _alert_dict() -> dict:
    a = training_alert()
    if a is None:
        raise HTTPException(500, "訓練電文に大阪府の情報がありません")
    d = asdict(a)
    d["minutes_left"] = a.minutes_left()
    return d
