"""構成図（docs/architecture.png）を描く。アイコンは Google Cloud 公式（https://cloud.google.com/icons）の PNG を使う。

使い方: python docs/tools/make_architecture.py <公式アイコンの zip を展開したフォルダ>
"""
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ICONS = Path(sys.argv[1])
OUT = Path(__file__).resolve().parent.parent / "architecture.png"
FONT = "C:/Windows/Fonts/BIZ-UDGothicR.ttc"
FONT_B = "C:/Windows/Fonts/BIZ-UDGothicB.ttc"
W, H = 1800, 1160

ICON_FILES = {
    "run": "Unique Icons/Cloud Run/PNG/CloudRun-512-color-rgb.png",
    "vertex": "Unique Icons/Vertex AI/PNG/VertexAI-512-color.png",
    "maps": "Category Icons/Maps & Geospatial/PNG/MapsGeospatial-512-color.png",
    "secret": "Category Icons/Security Identity/PNG/SecurityIdentity-512-color.png",
    "mobile": "Category Icons/Web & Mobile/PNG/WebMobile-512-color.png",
    "ai": "Category Icons/AI _ Machine Learning/PNG/AIMachineLearning-512-color.png",
}

img = Image.new("RGB", (W, H), "white")
d = ImageDraw.Draw(img)
f = lambda size, bold=False: ImageFont.truetype(FONT_B if bold else FONT, size)


def icon(key: str, x: int, y: int, size: int = 72) -> None:
    im = Image.open(ICONS / ICON_FILES[key]).convert("RGBA").resize((size, size))
    img.paste(im, (x, y), im)


def box(x, y, w, h, title, lines, key=None, fill="#F8FAFC", edge="#94A3B8"):
    d.rounded_rectangle((x, y, x + w, y + h), radius=14, fill=fill, outline=edge, width=2)
    tx = x + 16
    if key:
        icon(key, x + 14, y + 14)
        tx = x + 100
    d.text((tx, y + 18), title, font=f(26, True), fill="#0F172A")
    for i, line in enumerate(lines):
        d.text((tx, y + 60 + i * 32), line, font=f(20), fill="#334155")


def arrow(x1, y1, x2, y2, label="", color="#475569", lx=None, ly=None):
    d.line((x1, y1, x2, y2), fill=color, width=3)
    import math
    a = math.atan2(y2 - y1, x2 - x1)
    for s in (0.45, -0.45):
        d.line((x2, y2, x2 - 16 * math.cos(a + s), y2 - 16 * math.sin(a + s)), fill=color, width=3)
    if label:
        d.text((lx if lx is not None else (x1 + x2) / 2 + 8, ly if ly is not None else (y1 + y2) / 2 - 26), label, font=f(18), fill=color)


d.text((40, 24), "津波避難ナビ（旅行者向け）構成図", font=f(34, True), fill="#0F172A")
d.text((40, 70), "オンラインが前提。通信が落ちたら端末の中だけで案内を続ける", font=f(22), fill="#475569")

# 端末（左）
d.rounded_rectangle((40, 120, 640, 1080), radius=18, outline="#2563EB", width=3)
icon("mobile", 56, 136)
d.text((140, 150), "Android アプリ（Kotlin・Jetpack Compose）", font=f(24, True), fill="#1D4ED8")
box(60, 230, 560, 280, "通信あり", [
    "・Navigation SDK：目的地まで徒歩の案内",
    "・Gemini Live（Firebase AI Logic）",
    "　声で会話。頼みごとは関数で受け取り、",
    "　選び直しはアプリが公式データで行う",
    "・App Check で API を守る",
], key="maps", fill="#EFF6FF", edge="#93C5FD")
box(60, 540, 560, 330, "通信なし（圏外・機内モード）", [
    "・保存したパック（避難先・浸水想定・音声）",
    "・避難先の計算：間に合う所を選ぶ",
    "　（0.62m/秒・3階以上・到達＝揺れた＋26分）",
    "・方角の矢印と距離・端末の読み上げ",
    "・頼みごとはボタンで受ける",
    "　（FunctionGemma 270M を追加学習中）",
], key="ai", fill="#F0FDF4", edge="#86EFAC")
d.text((70, 890), "通信が落ちたことを検知して自動で切り替える", font=f(20, True), fill="#15803D")
d.text((70, 930), "現在地：GPS（通信なしでも動く）", font=f(20), fill="#334155")

# Cloud Run（中央）
box(740, 330, 480, 330, "Cloud Run（東京）", [
    "Python・FastAPI",
    "/api/alert  警報（デモは訓練電文）",
    "/api/plan   間に合う避難先を選ぶ",
    "            ＋浸水の浅い道と経由地",
    "/api/pack   オフライン用のパック",
    "Web 版の画面（審査員が触れる）",
], key="run")

# 右側
box(1300, 120, 460, 220, "Vertex AI", [
    "Gemini 3.8 Flash",
    "・選んだ避難先の説明",
    "　（候補の外の場所は言わせない）",
    "・答えの翻訳・音声の生成（TTS）",
], key="vertex")
box(740, 120, 480, 180, "Firebase AI Logic", [
    "・Gemini Live（声の会話・関数呼び出し）",
    "・App Check（不正な呼び出しを防ぐ）",
], key="ai", fill="#EEF2FF", edge="#A5B4FC")
box(1300, 370, 460, 200, "Google Maps Platform", [
    "・Routes API：徒歩の道のり・別の道",
    "・Geocoding API：評価で使う",
], key="maps")
box(1300, 600, 460, 150, "Secret Manager", ["・Routes API のキーを保管"], key="secret")
box(1300, 780, 460, 260, "Colab（Google）で追加学習", [
    "・教師データを Gemini で作る",
    "　（4 言語 × 頼みごと 10 種類）",
    "・FunctionGemma 270M を学習",
    "・.litertlm に変換して端末へ",
], key="ai", fill="#FFFBEB", edge="#FCD34D")

# 公開データ（下中央）
box(740, 760, 480, 300, "公開データ", [
    "・気象庁 防災情報 XML",
    "　（津波警報・到達予想時刻）",
    "・国土地理院 指定緊急避難場所",
    "　（大阪市・堺市・津波）",
    "・国土数値情報 津波浸水想定",
    "　（大阪府）",
], fill="#FFF7ED", edge="#FDBA74")

# 矢印
arrow(620, 380, 740, 430, "避難先を頼む", lx=630, ly=340)
arrow(740, 560, 620, 640, "パックを保存", lx=630, ly=600)
arrow(1220, 400, 1300, 260, "説明・翻訳・音声", lx=1228, ly=345)
arrow(1220, 470, 1300, 470, "道のり", lx=1222, ly=440)
arrow(1300, 670, 1220, 600, "キー", lx=1240, ly=640)
arrow(980, 760, 980, 660, "取り込み", lx=990, ly=700)
c = "#B45309"
d.line((1530, 1040, 1530, 1110, 600, 1110, 600, 1080), fill=c, width=3)
arrow(600, 1110, 600, 875, color=c)
d.text((700, 1078), "学習したモデル（.litertlm）を端末へ", font=f(18), fill=c)
arrow(620, 300, 740, 220, "声の会話", lx=640, ly=225)

img.save(OUT)
print(OUT)
