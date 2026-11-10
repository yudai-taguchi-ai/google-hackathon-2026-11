"""地図に塗る浸水想定区域。パック用に簡略化したデータ（inundation_pack.geojson）を、見ている範囲で切り出して返す。"""
import json
from functools import lru_cache
from pathlib import Path

from shapely.geometry import box, mapping, shape
from shapely.ops import unary_union

DATA = Path(__file__).resolve().parent.parent / "data"
MAX_SPAN_DEG = 0.04


@lru_cache(maxsize=1)
def _areas():
    gj = json.loads((DATA / "inundation_pack.geojson").read_text(encoding="utf-8"))
    return [(f["properties"]["depth"], shape(f["geometry"])) for f in gj["features"]]


def _round(coords):
    if isinstance(coords[0], (int, float)):
        return [round(coords[0], 5), round(coords[1], 5)]
    return [_round(c) for c in coords]


def flood_in(south: float, west: float, north: float, east: float) -> dict:
    """範囲（広すぎる時は中心から MAX_SPAN_DEG 四方）にかかる浸水域を、深さの区分ごとの GeoJSON で返す。"""
    if north - south > MAX_SPAN_DEG or east - west > MAX_SPAN_DEG:
        cy, cx = (north + south) / 2, (east + west) / 2
        south, north = cy - MAX_SPAN_DEG / 2, cy + MAX_SPAN_DEG / 2
        west, east = cx - MAX_SPAN_DEG / 2, cx + MAX_SPAN_DEG / 2
    features = []
    for depth, geom in _areas():
        part = geom.intersection(box(west, south, east, north))
        if part.geom_type not in ("Polygon", "MultiPolygon"):
            polys = [p for p in getattr(part, "geoms", []) if p.geom_type in ("Polygon", "MultiPolygon")]
            part = unary_union(polys) if polys else None
        if part is None or part.is_empty:
            continue
        g = mapping(part.simplify(0.00006, preserve_topology=True))
        features.append({"type": "Feature", "properties": {"depth": depth}, "geometry": {"type": g["type"], "coordinates": _round(g["coordinates"])}})
    return {"type": "FeatureCollection", "features": features}

