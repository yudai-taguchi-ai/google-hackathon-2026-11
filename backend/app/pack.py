"""オフライン用のデータ一式（パック）。旅行の前に端末へ保存し、圏外でも避難先を選べるようにする。"""
import io
import json
import os
import wave
from functools import lru_cache
from pathlib import Path

from google import genai
from google.genai import types

from .planner import is_open_space, required_floor, shelters

DATA = Path(__file__).resolve().parent.parent / "data"
TEXT_MODEL = "gemini-3.8-flash"
TTS_MODEL = "gemini-3.8-flash-tts"
ARRIVAL_DEFAULT_MIN = 26
LANG_NAMES = {"ja": "日本語", "en": "English", "zh": "简体中文", "ko": "한국어"}

ANSWERS_JA = {
    "reselect_slow": "ゆっくり歩く前提で、間に合う避難先を選び直します。画面と声の案内に従ってください。",
    "reselect_wheelchair": "車いすの前提で、近い避難先を選び直します。上の階へは、近くの人に助けを求めてください。",
    "ask_floor": "3階以上、できればもっと高い階へ上がってください。階は目安です。",
    "road_blocked": "その道は使わず、別の避難先を選び直します。",
    "lost_family": "今は探しに戻らず、まず自分が避難してください。警報が解除されてから、避難所で探してください。",
    "injured": "動けるなら避難を続けてください。動けない時は、近くの人に助けを求めてください。",
    "when_to_return": "津波は何度も来ます。警報が解除されるまで戻らないでください。",
    "repeat": "もう一度案内します。",
    "other": "すみません、分かりませんでした。画面のボタンから選んでください。",
}


@lru_cache
def _client() -> genai.Client:
    return genai.Client(
        vertexai=True,
        project=os.environ["GOOGLE_CLOUD_PROJECT"],
        location=os.environ.get("GOOGLE_CLOUD_LOCATION", "global"),
    )


ANSWERS_DIR = DATA / "answers"


@lru_cache
def answers(lang: str) -> dict[str, str]:
    """答えの文。scripts/prepare_answers.py で先に作ったファイルがあればそれを使う（起動のたびに Gemini を呼ばない）。"""
    baked = ANSWERS_DIR / f"{lang}.json"
    if baked.exists():
        return json.loads(baked.read_text(encoding="utf-8"))
    if lang == "ja":
        return dict(ANSWERS_JA)
    schema = types.Schema(
        type=types.Type.OBJECT,
        properties={k: types.Schema(type=types.Type.STRING) for k in ANSWERS_JA},
        required=list(ANSWERS_JA),
    )
    res = _client().models.generate_content(
        model=TEXT_MODEL,
        contents=json.dumps(ANSWERS_JA, ensure_ascii=False),
        config=types.GenerateContentConfig(
            system_instruction=f"各値を{LANG_NAMES.get(lang, lang)}に訳す。意味を足したり減らしたりしない。短く、命令形のまま。キーは変えない。",
            response_mime_type="application/json",
            response_schema=schema,
        ),
    )
    return json.loads(res.text)


@lru_cache(maxsize=64)
def audio_wav(lang: str, answer_id: str) -> bytes:
    baked = ANSWERS_DIR / "audio" / lang / f"{answer_id}.wav"
    if baked.exists():
        return baked.read_bytes()
    return synthesize(answers(lang)[answer_id])


def synthesize(text: str) -> bytes:
    res = _client().models.generate_content(
        model=TTS_MODEL,
        contents=text,
        config=types.GenerateContentConfig(response_modalities=["AUDIO"]),
    )
    pcm = res.candidates[0].content.parts[0].inline_data.data
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(24000)
        w.writeframes(pcm)
    return buf.getvalue()


@lru_cache
def _inundation() -> dict:
    return json.loads((DATA / "inundation_pack.geojson").read_text(encoding="utf-8"))


def build_pack(area: str, lang: str, version: str, expires_at: str | None) -> dict:
    items = [{
        **s,
        "floor_min": required_floor(s["inundation_depth"]),
        "open_space": is_open_space(s["name"]),
    } for s in shelters()]
    texts = answers(lang)
    return {
        "version": version,
        "area": area,
        "lang": lang,
        "expires_at": expires_at,
        "arrival_default_min": ARRIVAL_DEFAULT_MIN,
        "walk_speed_m_per_s": {"normal": 0.62, "slow": 0.5},
        "shelters": items,
        "inundation": _inundation(),
        "answers": [{"id": k, "text": v, "audio_url": f"/api/pack/audio?lang={lang}&id={k}"} for k, v in texts.items()],
        "sources": {
            "shelters": "国土地理院 指定緊急避難場所データ（大阪市・堺市・津波）",
            "inundation": "国土数値情報 津波浸水想定（大阪府 2023年版）を簡略化",
        },
    }
