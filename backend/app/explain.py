"""選ばれた避難先を、Gemini が利用者の言葉で説明する。渡した候補以外の場所は使わせない。"""
import json
import os

from google import genai
from google.genai import types

MODEL = "gemini-3.8-flash"
LANG_NAMES = {"ja": "日本語", "en": "English", "zh": "简体中文", "ko": "한국어"}

INSTRUCTION = """あなたは津波避難の案内役です。渡された JSON の事実だけを使い、指定された言語で、短く・命令形で、今すぐ取るべき行動を伝えてください。
決まり:
- 避難先の名前・距離・時間・階は JSON の値だけを使う。JSON に無い場所・建物・数値を出さない
- decision が go_upstairs なら、建物の floor_min 階以上へ上がるよう伝える（floor_min が null なら最上階か屋上）。階は目安であることを一言添える
- decision が already_outside_zone なら、現在地は浸水想定区域の外なので、海や川の方へ行かず、その場より内陸・海から遠い方向へ離れるよう伝える。primary は時間に余裕があれば向かう先として添えるだけにする（間に合わないとは言わない）
- decision が may_not_make_it なら、間に合わない可能性を伝え、最も近い頑丈な建物の上の階へ上がるよう伝える
- user_inundation_depth が null なら、現在地は浸水想定区域の外であることを伝えたうえで、海や川から離れるよう伝える
- 車を使わない、川沿いを歩かない、警報が解除されるまで戻らない、を最後に一文で
- used_ids には説明に使った避難先の id を入れる"""

SCHEMA = types.Schema(
    type=types.Type.OBJECT,
    properties={
        "message": types.Schema(type=types.Type.STRING),
        "used_ids": types.Schema(type=types.Type.ARRAY, items=types.Schema(type=types.Type.STRING)),
    },
    required=["message", "used_ids"],
)


def _facts(alert: dict, plan: dict) -> dict:
    keep = ("id", "name", "address", "walk_min", "type", "floor_min")
    return {
        "alert": {k: alert[k] for k in ("kind", "area_name", "max_height")},
        "minutes_left": plan["minutes_left"],
        "decision": plan["decision"],
        "user_inundation_depth": plan["user_inundation_depth"],
        "primary": {k: plan["primary"][k] for k in keep},
        "alternatives": [{k: o[k] for k in keep} for o in plan["alternatives"]],
    }


def _fallback(plan: dict) -> str:
    p = plan["primary"]
    floor = f"（{p['floor_min']}階以上・目安）" if p["type"] == "go_upstairs" and p["floor_min"] else ""
    return f"今すぐ {p['name']}{floor} へ。歩いて約{p['walk_min']:.0f}分。車を使わず、川沿いを避け、警報が解除されるまで戻らないでください。"


def explain(alert: dict, plan: dict, lang: str = "ja") -> dict:
    facts = _facts(alert, plan)
    allowed = {facts["primary"]["id"], *(o["id"] for o in facts["alternatives"])}
    try:
        client = genai.Client(
            vertexai=True,
            project=os.environ["GOOGLE_CLOUD_PROJECT"],
            location=os.environ.get("GOOGLE_CLOUD_LOCATION", "global"),
        )
        res = client.models.generate_content(
            model=MODEL,
            contents=f"言語: {LANG_NAMES.get(lang, lang)}\n{json.dumps(facts, ensure_ascii=False)}",
            config=types.GenerateContentConfig(
                system_instruction=INSTRUCTION,
                response_mime_type="application/json",
                response_schema=SCHEMA,
            ),
        )
        out = json.loads(res.text)
        if not set(out["used_ids"]) <= allowed:
            raise ValueError(f"候補に無い id を使った: {set(out['used_ids']) - allowed}")
        return {"message": out["message"], "source": "gemini"}
    except Exception as e:
        return {"message": _fallback(plan), "source": "fallback", "error": str(e)}
