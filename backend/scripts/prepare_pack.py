"""オフライン用のパックに入れる浸水想定を作る。大阪湾岸（大阪市・堺市）の範囲で、深さの区分ごとにまとめて簡略化する。"""
import json
from pathlib import Path

import shapely
from shapely.geometry import box, mapping, shape
from shapely.ops import unary_union

DATA = Path(__file__).resolve().parent.parent / "data"
AREA = box(135.38, 34.50, 135.53, 34.72)
TOLERANCE_DEG = 0.0001
PRECISION_DEG = 0.00001


def main() -> None:
    gj = json.loads((DATA / "raw" / "inundation_osaka.geojson").read_text(encoding="utf-8"))
    by_depth: dict[str, list] = {}
    for f in gj["features"]:
        g = shape(f["geometry"])
        if g.intersects(AREA):
            by_depth.setdefault(f["properties"]["A40_003"], []).append(g)
    features = []
    for depth, geoms in by_depth.items():
        merged = unary_union(geoms).intersection(AREA).simplify(TOLERANCE_DEG, preserve_topology=True)
        merged = shapely.set_precision(merged, PRECISION_DEG)
        features.append({"type": "Feature", "properties": {"depth": depth}, "geometry": mapping(merged)})
    out = DATA / "inundation_pack.geojson"
    out.write_text(json.dumps({"type": "FeatureCollection", "features": features}, separators=(",", ":")), encoding="utf-8")
    print(f"{len(features)} 区分を保存（{out.stat().st_size / 1e6:.1f} MB）→ {out}")


if __name__ == "__main__":
    main()
