from app.flood import MAX_SPAN_DEG, flood_in


def test_sakai_station_area_has_flood_polygons():
    gj = flood_in(34.57, 135.455, 34.592, 135.485)
    assert gj["type"] == "FeatureCollection"
    assert gj["features"], "堺駅の周りは浸水想定区域にかかっている"
    assert all(f["geometry"]["type"] in ("Polygon", "MultiPolygon") for f in gj["features"])


def test_too_wide_area_is_cut_to_the_limit():
    gj = flood_in(34.0, 135.0, 35.0, 136.0)
    for f in gj["features"]:
        coords = f["geometry"]["coordinates"]
        flat = [pt for poly in (coords if f["geometry"]["type"] == "MultiPolygon" else [coords]) for ring in poly for pt in ring]
        assert max(p[1] for p in flat) - min(p[1] for p in flat) <= MAX_SPAN_DEG + 1e-6
