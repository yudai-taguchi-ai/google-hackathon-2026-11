"""評価: 同じ地点・同じ条件で「Gemini 単体」と「このアプリ」に避難先を聞き、公式データで採点する（docs/plan.md 8章）。

使い方（backend/ で）:
  GOOGLE_MAPS_API_KEY=... \
  GOOGLE_CLOUD_PROJECT=... ./.venv/Scripts/python -m eval.run_eval [前回の結果.json]（失敗した所だけ聞き直す）
結果は eval/results/ に JSON（全部の答え）と Markdown（集計）で出る。
"""
import difflib
import json
import os
import random
import re
import sys
import time
import unicodedata
from datetime import datetime
from pathlib import Path

import requests
from google import genai
from google.genai import types

import gcp_auth

from app.planner import inundation_at, plan, shelters
from app.walking import WALK_SPEED_M_PER_S, distance_m, to_minutes

OUT = Path(__file__).resolve().parent / "results"
MINUTES_LEFT = 17.0
SAFETY_MARGIN_MIN = 3.0
MATCH_RADIUS_M = 3000
SAME_PLACE_M = 200
MODELS = ["gemini-3.8-flash", "gemini-4-argon"]
GEOCODE_URL = "https://maps.googleapis.com/maps/api/geocode/json"
MATRIX_URL = "https://routes.googleapis.com/distanceMatrix/v2:computeRouteMatrix"

LANDMARKS = [
    ("海遊館", 34.6545, 135.4290),
    ("ユニバーサル・スタジオ・ジャパン", 34.6654, 135.4323),
    ("大阪港駅", 34.6550, 135.4410),
    ("ATC（アジア太平洋トレードセンター）", 34.6386, 135.4170),
    ("弁天町駅", 34.6694, 135.4615),
    ("大正駅", 34.6648, 135.4789),
    ("京セラドーム大阪", 34.6694, 135.4762),
    ("南海 堺駅", 34.5813, 135.4673),
    ("堺旧港", 34.5833, 135.4580),
    ("大浜公園", 34.5786, 135.4610),
    ("浜寺公園", 34.5385, 135.4400),
    ("堺浜", 34.6095, 135.4500),
]
RANDOM_BOXES = [("大阪市の海沿い", 34.62, 34.70, 135.40, 135.49, 9), ("堺市の海沿い", 34.54, 34.62, 135.43, 135.48, 9)]

PROMPT = """大阪で大津波警報が出ました。私は旅行者で、今 緯度 {lat:.5f}, 経度 {lon:.5f}（{near}の近く）にいます。移動は徒歩です。
津波の到達予想は {minutes:.0f} 分後です。どこへ避難すべきですか。
具体的な避難先を優先順に最大3つ挙げてください。名前は正式名称（日本語）、住所は日本語で。
JSON だけを返してください: {{"places": [{{"name": "...", "address": "...", "kind": "浸水しない高台・区域の外 | 建物の上の階 | その他", "floor": 数字または null, "walk_min": 数字}}]}}"""


def points() -> list[dict]:
    pts = [{"label": n, "lat": la, "lon": lo, "kind": "観光地・駅"} for n, la, lo in LANDMARKS]
    rng = random.Random(42)
    for label, la0, la1, lo0, lo1, n in RANDOM_BOXES:
        got = 0
        while got < n:
            la, lo = rng.uniform(la0, la1), rng.uniform(lo0, lo1)
            if inundation_at(la, lo) is None:
                continue
            pts.append({"label": f"{label}{got + 1}", "lat": round(la, 5), "lon": round(lo, 5), "kind": "無作為"})
            got += 1
    return pts


def norm(s: str) -> str:
    s = unicodedata.normalize("NFKC", s or "")
    s = re.sub(r"(株式会社|（株）|\(株\)|大阪府|大阪市|堺市|\s|・|-|ー|−)", "", s)
    return s.lower()


def match_official(name: str, lat: float, lon: float) -> dict | None:
    target = norm(name)
    if not target:
        return None
    best, best_score = None, 0.0
    for s in shelters():
        if distance_m(lat, lon, s["lat"], s["lon"]) > MATCH_RADIUS_M:
            continue
        cand = norm(s["name"])
        score = 1.0 if (target in cand or cand in target) and min(len(target), len(cand)) >= 3 else difflib.SequenceMatcher(None, target, cand).ratio()
        if score > best_score:
            best, best_score = s, score
    return best if best_score >= 0.8 else None


def geocode(name: str, address: str, key: str) -> tuple[float, float] | None:
    q = f"{name} {address}".strip()
    r = requests.get(GEOCODE_URL, params={"address": q, "key": key, "language": "ja", "region": "jp"}, timeout=20)
    res = r.json().get("results", [])
    if not res:
        return None
    loc = res[0]["geometry"]["location"]
    return loc["lat"], loc["lng"]


def walk_meters(lat: float, lon: float, dest: tuple[float, float], key: str) -> float | None:
    body = {
        "origins": [{"waypoint": {"location": {"latLng": {"latitude": lat, "longitude": lon}}}}],
        "destinations": [{"waypoint": {"location": {"latLng": {"latitude": dest[0], "longitude": dest[1]}}}}],
        "travelMode": "WALK",
    }
    headers = {"X-Goog-Api-Key": key, "X-Goog-FieldMask": "distanceMeters,condition"}
    r = requests.post(MATRIX_URL, json=body, headers=headers, timeout=20)
    for el in r.json():
        if el.get("condition") == "ROUTE_EXISTS" and "distanceMeters" in el:
            return float(el["distanceMeters"])
    return None


def ask_gemini(model: str, p: dict) -> tuple[list[dict], str]:
    res = gcp_auth.call(lambda client: client.models.generate_content(
        model=model,
        contents=PROMPT.format(lat=p["lat"], lon=p["lon"], near=p["label"], minutes=MINUTES_LEFT),
        config=types.GenerateContentConfig(tools=[types.Tool(google_search=types.GoogleSearch()), types.Tool(google_maps=types.GoogleMaps())]),
    ))
    text = res.text or ""
    m = re.search(r"\{.*\}", text, flags=re.S)
    try:
        return json.loads(m.group(0))["places"][:3], text
    except Exception:
        return [], text


def score_place(p: dict, place: dict, key: str) -> dict:
    official = match_official(place.get("name", ""), p["lat"], p["lon"])
    loc = geocode(place.get("name", ""), place.get("address", ""), key)
    says_safe_ground = "高台" in (place.get("kind") or "") or "区域の外" in (place.get("kind") or "")
    depth_at_place = inundation_at(*loc) if loc else None
    return {
        **place,
        "official_id": official["id"] if official else None,
        "official_name": official["name"] if official else None,
        "is_official": official is not None,
        "name_and_place_ok": bool(official and loc and distance_m(*loc, official["lat"], official["lon"]) <= SAME_PLACE_M),
        "geocoded": loc,
        "safe_ground_but_inundated": bool(says_safe_ground and loc and depth_at_place is not None),
        "depth_at_place": depth_at_place,
    }


def main() -> None:
    key = os.environ["GOOGLE_MAPS_API_KEY"].strip()
    OUT.mkdir(exist_ok=True)
    done = {}
    if len(sys.argv) > 1:
        for r in json.loads(Path(sys.argv[1]).read_text(encoding="utf-8")):
            done[r["point"]["label"]] = r
    rows = []
    for i, p in enumerate(points(), 1):
        app_plan = gcp_auth.retry(lambda: plan(p["lat"], p["lon"], MINUTES_LEFT))
        best = app_plan["primary"]
        row = {"point": p, "user_depth": inundation_at(p["lat"], p["lon"]), "app": {
            "name": best["name"], "id": best["id"], "walk_min": best["walk_min"], "reachable": best["reachable"],
            "decision": app_plan["decision"], "type": best["type"],
        }, "gemini": {}}
        prev = done.get(p["label"], {}).get("gemini", {})
        for model in MODELS:
            if model in prev and "error" not in prev[model]:
                row["gemini"][model] = prev[model]
                continue
            try:
                places, raw = ask_gemini(model, p)
            except Exception as e:
                row["gemini"][model] = {"error": str(e)}
                continue
            scored = [gcp_auth.retry(lambda pl=pl: score_place(p, pl, key)) for pl in places]
            first = scored[0] if scored else None
            first_walk = None
            if first and first["geocoded"]:
                meters = gcp_auth.retry(lambda: walk_meters(p["lat"], p["lon"], first["geocoded"], key))
                first_walk = round(to_minutes(meters), 1) if meters else None
            row["gemini"][model] = {"places": scored, "first_walk_min": first_walk, "raw": raw}
            time.sleep(1)
        rows.append(row)
        (OUT / "eval-partial.json").write_text(json.dumps(rows, ensure_ascii=False, indent=1), encoding="utf-8")
        print(f"{i}/30 {p['label']}", file=sys.stderr)

    stamp = datetime.now().strftime("%Y%m%d-%H%M")
    (OUT / f"eval-{stamp}.json").write_text(json.dumps(rows, ensure_ascii=False, indent=1), encoding="utf-8")
    (OUT / f"eval-{stamp}.md").write_text(summary(rows), encoding="utf-8")
    print(summary(rows))


def summary(rows: list[dict]) -> str:
    budget = MINUTES_LEFT - SAFETY_MARGIN_MIN
    lines = [
        f"# 評価の結果（{len(rows)} 地点・大津波警報・到達まで {MINUTES_LEFT:.0f} 分・徒歩 {WALK_SPEED_M_PER_S}m/秒）",
        "",
        "| 指標 | " + " | ".join(MODELS) + " | このアプリ |",
        "|---|" + "---|" * (len(MODELS) + 1),
    ]

    def per_model(fn):
        return [fn([r["gemini"].get(m, {}) for r in rows]) for m in MODELS]

    def ratio(a, b):
        return f"{a}/{b}（{(100 * a / b) if b else 0:.0f}%）"

    places = lambda gs: [pl for g in gs for pl in g.get("places", [])]
    lines.append("| ① 挙げた場所が公式の津波避難場所 | " + " | ".join(per_model(lambda gs: ratio(sum(pl["is_official"] for pl in places(gs)), len(places(gs))))) + f" | {ratio(len(rows), len(rows))} |")
    lines.append("| ② 名前と位置が公式と合っている | " + " | ".join(per_model(lambda gs: ratio(sum(pl["name_and_place_ok"] for pl in places(gs)), len(places(gs))))) + f" | {ratio(len(rows), len(rows))} |")
    lines.append("| ③ 浸水想定区域の中を「高台・区域の外」と案内した数 | " + " | ".join(per_model(lambda gs: str(sum(pl["safe_ground_but_inundated"] for pl in places(gs))))) + " | 0 |")

    def walk_diff(gs):
        diffs = [g["first_walk_min"] - r["app"]["walk_min"] for g, r in zip(gs, rows) if g.get("first_walk_min") is not None]
        return f"平均 {sum(diffs) / len(diffs):+.1f} 分（{len(diffs)} 地点）" if diffs else "—"
    lines.append("| ④ 1番目の避難先までの歩く時間（アプリとの差） | " + " | ".join(per_model(walk_diff)) + " | 0 |")

    def reach(gs):
        ok = sum(1 for g in gs if g.get("first_walk_min") is not None and g["first_walk_min"] <= budget and g["places"] and g["places"][0]["is_official"])
        return ratio(ok, len(rows))
    app_ok = sum(1 for r in rows if r["app"]["reachable"])
    lines.append("| ⑤ 1番目が公式の避難場所で、かつ間に合う | " + " | ".join(per_model(reach)) + f" | {ratio(app_ok, len(rows))} |")
    lines += [
        "",
        "- 正解データ: 国土地理院 指定緊急避難場所（大阪市・堺市・津波）。浸水想定: 国土数値情報（大阪府 2023 年版）",
        f"- Gemini は Vertex AI で Google 検索・Google マップの裏付け（grounding）を付けて質問。名前の一致は正規化して {MATCH_RADIUS_M}m 以内・類似度 0.8 以上、位置の一致は {SAME_PLACE_M}m 以内（Geocoding API）",
        "- アプリは公式データから選ぶので ①②③ は作りの上で満点（比べたいのは Gemini 側）。歩く時間は Routes API の距離 ÷ 0.62m/秒",
    ]
    return "\n".join(lines) + "\n"


if __name__ == "__main__":
    main()
