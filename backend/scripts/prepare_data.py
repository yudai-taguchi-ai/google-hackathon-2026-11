"""国土地理院の指定緊急避難場所（津波）と国土数値情報の津波浸水想定を取得し、アプリ用のデータを作る。"""
import csv
import io
import json
import zipfile
from pathlib import Path

import requests
from shapely.geometry import Point, shape
from shapely.strtree import STRtree

DATA = Path(__file__).resolve().parent.parent / "data"
RAW = DATA / "raw"
CITIES = {"27100": "大阪市", "27140": "堺市"}
GSI_URL = "https://hinanmap.gsi.go.jp/hinanjocp/defaultFtpData/csv/{code}_2.csv"
A40_URL = "https://nlftp.mlit.go.jp/ksj/gml/data/A40/A40-23/A40-23_27_GML.zip"
UA = {"User-Agent": "tsunami-nav-hackathon/0.1"}


def fetch(url: str) -> bytes:
    r = requests.get(url, headers=UA, timeout=120)
    r.raise_for_status()
    return r.content


def decode(raw: bytes) -> str:
    for enc in ("utf-8-sig", "cp932"):
        try:
            return raw.decode(enc)
        except UnicodeDecodeError:
            pass
    raise ValueError("文字コードを判定できません")


def load_inundation() -> dict:
    path = RAW / "inundation_osaka.geojson"
    if not path.exists():
        z = zipfile.ZipFile(io.BytesIO(fetch(A40_URL)))
        path.write_bytes(z.read("A40-23_27_GML/A40-23_27.geojson"))
    return json.loads(path.read_text(encoding="utf-8"))


def main() -> None:
    RAW.mkdir(parents=True, exist_ok=True)
    gj = load_inundation()
    polys = [shape(f["geometry"]) for f in gj["features"]]
    depths = [f["properties"]["A40_003"] for f in gj["features"]]
    tree = STRtree(polys)

    shelters = []
    for code, city in CITIES.items():
        rows = csv.DictReader(io.StringIO(decode(fetch(GSI_URL.format(code=code)))))
        for row in rows:
            if not row.get("津波", "").strip():
                continue
            lat, lon = float(row["緯度"]), float(row["経度"])
            pt = Point(lon, lat)
            hit = [i for i in tree.query(pt) if polys[i].contains(pt)]
            shelters.append({
                "id": row["共通ID"],
                "name": row["施設・場所名"],
                "address": row["住所"],
                "city": city,
                "lat": lat,
                "lon": lon,
                "inundation_depth": depths[hit[0]] if hit else None,
            })

    out = DATA / "shelters_osaka.json"
    out.write_text(json.dumps(shelters, ensure_ascii=False, indent=1), encoding="utf-8")
    inside = sum(s["inundation_depth"] is not None for s in shelters)
    print(f"{len(shelters)} 件を保存（浸水想定区域の中: {inside} 件）→ {out}")


if __name__ == "__main__":
    main()
