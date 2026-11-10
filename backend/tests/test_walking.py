"""Route filtering tests; no API keys or network are required."""
from app.walking import NoRouteFound, routable_candidates


def test_route_filtering():
    shelters = [{"id": "missing"}, {"id": "ok"}, {"id": "nan"}]
    assert routable_candidates(shelters, [float("inf"), 120.0, float("nan")]) == [(shelters[1], 120.0)]
    for distances in ([float("inf")] * 3, [-1.0] * 3):
        try:
            routable_candidates(shelters, distances)
        except NoRouteFound:
            pass
        else:
            raise AssertionError("Unavailable routes must not be presented as reachable")
    try:
        routable_candidates(shelters, [1.0])
    except ValueError:
        pass
    else:
        raise AssertionError("Mismatched route counts must fail")


if __name__ == "__main__":
    test_route_filtering()
    print("ok: unavailable routes are not selected")
