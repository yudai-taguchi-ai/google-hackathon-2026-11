"""現在地から各避難先までの歩く距離（m）と時間（分）を出す。Routes API のキーが無い時は直線距離からの目安を使う。"""
import math
import os

import requests

ROUTES_URL = "https://routes.googleapis.com/distanceMatrix/v2:computeRouteMatrix"
WALK_SPEED_M_PER_S = 0.62
SLOW_WALK_SPEED_M_PER_S = 0.5
FALLBACK_DETOUR = 1.6


def distance_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    r = 6371000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def _estimate(lat: float, lon: float, dests: list[dict]) -> list[float]:
    return [distance_m(lat, lon, d["lat"], d["lon"]) * FALLBACK_DETOUR for d in dests]


def _routes_api(lat: float, lon: float, dests: list[dict], key: str) -> list[float]:
    def wp(a, b):
        return {"waypoint": {"location": {"latLng": {"latitude": a, "longitude": b}}}}

    body = {
        "origins": [wp(lat, lon)],
        "destinations": [wp(d["lat"], d["lon"]) for d in dests],
        "travelMode": "WALK",
    }
    headers = {"X-Goog-Api-Key": key, "X-Goog-FieldMask": "destinationIndex,distanceMeters,condition"}
    r = requests.post(ROUTES_URL, json=body, headers=headers, timeout=20)
    r.raise_for_status()
    meters = [math.inf] * len(dests)
    for el in r.json():
        if el.get("condition") == "ROUTE_EXISTS" and "distanceMeters" in el:
            meters[el["destinationIndex"]] = float(el["distanceMeters"])
    return meters


def walking_distances(lat: float, lon: float, dests: list[dict]) -> tuple[list[float], str]:
    key = os.environ.get("GOOGLE_MAPS_API_KEY", "").strip()
    if key:
        return _routes_api(lat, lon, dests, key), "routes_api"
    return _estimate(lat, lon, dests), "estimate"


def to_minutes(meters: float, slow: bool = False) -> float:
    speed = SLOW_WALK_SPEED_M_PER_S if slow else WALK_SPEED_M_PER_S
    return meters / speed / 60
