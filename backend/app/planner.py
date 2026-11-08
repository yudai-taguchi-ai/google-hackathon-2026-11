"""公式の津波避難場所から、間に合う避難先を選ぶ。Gemini は使わない（選ぶのは決まった手順だけ）。"""
import json
import re
from functools import lru_cache
from pathlib import Path

from shapely.geometry import Point, shape
from shapely.strtree import STRtree

from .walking import distance_m, to_minutes, walking_distances

DATA = Path(__file__).resolve().parent.parent / "data"
SAFETY_MARGIN_MIN = 3.0
CANDIDATES = 12
FLOOR_HEIGHT_M = 3.0
OPEN_SPACE = re.compile(r"公園|広場|グラウンド|運動場|緑地|高台|河川敷")


@lru_cache
def shelters() -> list[dict]:
    return json.loads((DATA / "shelters_osaka.json").read_text(encoding="utf-8"))


@lru_cache
def _inundation():
    gj = json.loads((DATA / "raw" / "inundation_osaka.geojson").read_text(encoding="utf-8"))
    polys = [shape(f["geometry"]) for f in gj["features"]]
    depths = [f["properties"]["A40_003"] for f in gj["features"]]
    return polys, depths, STRtree(polys)


def inundation_at(lat: float, lon: float) -> str | None:
    polys, depths, tree = _inundation()
    pt = Point(lon, lat)
    for i in tree.query(pt):
        if polys[i].contains(pt):
            return depths[i]
    return None


def required_floor(depth: str | None) -> int | None:
    """浸水の深さの区分から、上がるべき階の目安を出す。5m 以上は区分の上限が無いので None（最上階・屋上）。"""
    if depth is None:
        return None
    nums = [float(x) for x in re.findall(r"\d+(?:\.\d+)?", depth)]
    if len(nums) < 2:
        return None
    return int(nums[1] // FLOOR_HEIGHT_M) + 2


def is_open_space(name: str) -> bool:
    """名前から屋外（公園・広場など）かを推測する。国のデータに建物か屋外かの列が無いための目安。"""
    return bool(OPEN_SPACE.search(name))


def plan(lat: float, lon: float, minutes_left: float, slow: bool = False) -> dict:
    user_depth = inundation_at(lat, lon)
    by_distance = sorted(shelters(), key=lambda s: distance_m(lat, lon, s["lat"], s["lon"]))
    outside_pool = [s for s in by_distance if s["inundation_depth"] is None][:CANDIDATES]
    upstairs_pool = [] if user_depth is None else [
        s for s in by_distance if s["inundation_depth"] is not None and not is_open_space(s["name"])
    ][:CANDIDATES]
    pool = outside_pool + upstairs_pool
    meters, source = walking_distances(lat, lon, pool)
    budget = minutes_left - SAFETY_MARGIN_MIN

    options = []
    for s, m in zip(pool, meters):
        walk = to_minutes(m, slow)
        options.append({
            **s,
            "walk_m": None if m == float("inf") else round(m),
            "walk_min": round(walk, 1),
            "reachable": walk <= budget,
            "type": "outside_zone" if s["inundation_depth"] is None else "go_upstairs",
            "floor_min": required_floor(s["inundation_depth"]),
            "maps_url": f"https://www.google.com/maps/dir/?api=1&destination={s['lat']},{s['lon']}&travelmode=walking&dir_action=navigate",
        })

    reachable = [o for o in options if o["reachable"]]
    outside = sorted((o for o in reachable if o["type"] == "outside_zone"), key=lambda o: o["walk_min"])
    upstairs = sorted((o for o in reachable if o["type"] == "go_upstairs"), key=lambda o: o["walk_min"])
    if outside:
        primary, decision = outside[0], "reach_outside_zone"
    elif user_depth is None:
        primary, decision = min(options, key=lambda o: o["walk_min"]), "already_outside_zone"
    elif upstairs:
        primary, decision = upstairs[0], "go_upstairs"
    else:
        primary, decision = min(options, key=lambda o: o["walk_min"]), "may_not_make_it"

    others = [o for o in outside + upstairs if o is not primary][:2]
    return {
        "user_inundation_depth": user_depth,
        "minutes_left": round(minutes_left, 1),
        "decision": decision,
        "primary": primary,
        "alternatives": others,
        "walking_time_source": source,
        "walk_speed_m_per_s": 0.5 if slow else 0.62,
    }
