"""避難先までの徒歩の道を Routes API で出し、浸水想定の深い所を通らない道を選ぶ。Navigation SDK に渡す経由地も作る。"""
import os

import requests
from shapely.geometry import Point

from .planner import inundation_at

ROUTES_URL = "https://routes.googleapis.com/directions/v2:computeRoutes"
DEPTH_ORDER = ["0.01～0.3m", "0.3～1.0m", "1.0～2.0m", "2.0～3.0m", "3.0～4.0m", "4.0～5.0m", "5.0m～"]
MAX_WAYPOINTS = 8
SAMPLE_EVERY = 5


def decode_polyline(encoded: str) -> list[tuple[float, float]]:
    points, i, lat, lng = [], 0, 0, 0
    while i < len(encoded):
        for is_lng in (False, True):
            shift = result = 0
            while True:
                b = ord(encoded[i]) - 63
                i += 1
                result |= (b & 0x1F) << shift
                shift += 5
                if b < 0x20:
                    break
            delta = ~(result >> 1) if result & 1 else result >> 1
            if is_lng:
                lng += delta
            else:
                lat += delta
        points.append((lat / 1e5, lng / 1e5))
    return points


def _depth_rank(depth: str | None) -> int:
    return -1 if depth is None else DEPTH_ORDER.index(depth) if depth in DEPTH_ORDER else len(DEPTH_ORDER)


def _max_depth(points: list[tuple[float, float]]) -> tuple[int, str | None]:
    worst, worst_depth = -1, None
    for lat, lng in points[::SAMPLE_EVERY] + points[-1:]:
        d = inundation_at(lat, lng)
        if _depth_rank(d) > worst:
            worst, worst_depth = _depth_rank(d), d
    return worst, worst_depth


def safe_route(lat: float, lon: float, dest: dict) -> dict | None:
    key = os.environ.get("GOOGLE_MAPS_API_KEY", "").strip()
    if not key:
        return None
    body = {
        "origin": {"location": {"latLng": {"latitude": lat, "longitude": lon}}},
        "destination": {"location": {"latLng": {"latitude": dest["lat"], "longitude": dest["lon"]}}},
        "travelMode": "WALK",
        "computeAlternativeRoutes": True,
    }
    headers = {"X-Goog-Api-Key": key, "X-Goog-FieldMask": "routes.distanceMeters,routes.polyline.encodedPolyline"}
    r = requests.post(ROUTES_URL, json=body, headers=headers, timeout=20)
    r.raise_for_status()
    candidates = []
    for route in r.json().get("routes", []):
        pts = decode_polyline(route["polyline"]["encodedPolyline"])
        rank, depth = _max_depth(pts)
        candidates.append((rank, route["distanceMeters"], pts, route["polyline"]["encodedPolyline"], depth))
    if not candidates:
        return None
    rank, meters, pts, encoded, depth = min(candidates, key=lambda c: (c[0], c[1]))
    step = max(1, len(pts) // (MAX_WAYPOINTS + 1))
    waypoints = [{"lat": a, "lon": b} for a, b in pts[step:-1:step]][:MAX_WAYPOINTS]
    return {
        "distance_m": meters,
        "encoded_polyline": encoded,
        "waypoints": waypoints,
        "max_depth_on_route": depth,
        "alternatives_checked": len(candidates),
    }
