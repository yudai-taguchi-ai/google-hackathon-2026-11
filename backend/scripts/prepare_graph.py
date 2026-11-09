"""圏外でも本物に近い歩く距離を出すため、OpenStreetMap の歩ける道から「道のグラフ」を作る（data/walk_graph.bin）。
交差点だけを点にし、点と点の間の道の長さを辺にする。避難先ごとに一番近い点も記録する（data/walk_graph_shelters.json）。

使い方（backend/ で）: python -m scripts.prepare_graph <Overpass の JSON があるフォルダ>
地図データ: © OpenStreetMap contributors（ODbL）
"""
import json
import math
import struct
import sys
from collections import defaultdict
from pathlib import Path

DATA = Path(__file__).resolve().parent.parent / "data"


def haversine(a: tuple[float, float], b: tuple[float, float]) -> float:
    r = 6371000.0
    p1, p2 = math.radians(a[0]), math.radians(b[0])
    dp, dl = p2 - p1, math.radians(b[1] - a[1])
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(h))


def load(folder: Path) -> tuple[dict, list]:
    nodes, ways = {}, []
    for f in sorted(folder.glob("t*.json")):
        d = json.loads(f.read_text(encoding="utf-8"))
        for e in d["elements"]:
            if e["type"] == "node":
                nodes[e["id"]] = (e["lat"], e["lon"])
            elif e["type"] == "way":
                ways.append(e["nodes"])
    return nodes, ways


def build(nodes: dict, ways: list):
    use = defaultdict(int)
    for w in ways:
        for i, n in enumerate(w):
            use[n] += 2 if i in (0, len(w) - 1) else 1
    keep = {n for n, c in use.items() if c != 2 and n in nodes}
    edges = defaultdict(dict)
    for w in ways:
        w = [n for n in w if n in nodes]
        start, length = None, 0.0
        for i, n in enumerate(w):
            if i == 0:
                start = n
                keep.add(n)
                continue
            length += haversine(nodes[w[i - 1]], nodes[n])
            if n in keep or i == len(w) - 1:
                keep.add(n)
                if n != start:
                    old = edges[start].get(n)
                    if old is None or length < old:
                        edges[start][n] = length
                        edges[n][start] = length
                start, length = n, 0.0
    # 一番大きくつながった部分だけ残す
    seen, best = set(), set()
    for s in edges:
        if s in seen:
            continue
        comp, stack = {s}, [s]
        seen.add(s)
        while stack:
            u = stack.pop()
            for v in edges[u]:
                if v not in seen:
                    seen.add(v)
                    comp.add(v)
                    stack.append(v)
        if len(comp) > len(best):
            best = comp
    ids = sorted(best)
    index = {n: i for i, n in enumerate(ids)}
    return ids, index, {index[u]: {index[v]: l for v, l in edges[u].items() if v in index} for u in ids}


def write(ids, index, adj, nodes) -> None:
    offsets, targets, lengths = [0], [], []
    for i in range(len(ids)):
        for v, l in sorted(adj[i].items()):
            targets.append(v)
            lengths.append(min(65535, int(round(l))))
        offsets.append(len(targets))
    with open(DATA / "walk_graph.bin", "wb") as f:
        f.write(b"WG01")
        f.write(struct.pack("<ii", len(ids), len(targets)))
        f.write(b"".join(struct.pack("<ff", *nodes[n]) for n in ids))
        f.write(struct.pack(f"<{len(offsets)}i", *offsets))
        f.write(struct.pack(f"<{len(targets)}i", *targets))
        f.write(struct.pack(f"<{len(lengths)}H", *lengths))
    print(f"点 {len(ids)}・辺 {len(targets)}・{(DATA / 'walk_graph.bin').stat().st_size / 1e6:.1f} MB")


def snap_shelters(ids, nodes) -> None:
    cell = 0.003
    grid = defaultdict(list)
    for i, n in enumerate(ids):
        lat, lon = nodes[n]
        grid[(int(lat / cell), int(lon / cell))].append(i)
    out = {}
    for s in json.loads((DATA / "shelters_osaka.json").read_text(encoding="utf-8")):
        cy, cx = int(s["lat"] / cell), int(s["lon"] / cell)
        best, best_d = None, 1e18
        for dy in (-1, 0, 1):
            for dx in (-1, 0, 1):
                for i in grid[(cy + dy, cx + dx)]:
                    d = haversine((s["lat"], s["lon"]), nodes[ids[i]])
                    if d < best_d:
                        best, best_d = i, d
        if best is not None and best_d < 300:
            out[s["id"]] = best
    (DATA / "walk_graph_shelters.json").write_text(json.dumps(out), encoding="utf-8")
    print(f"避難先 {len(out)} 件を道に結んだ")


def main() -> None:
    nodes, ways = load(Path(sys.argv[1]))
    ids, index, adj = build(nodes, ways)
    write(ids, index, adj, nodes)
    snap_shelters(ids, nodes)


if __name__ == "__main__":
    main()
