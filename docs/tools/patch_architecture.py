"""構成図（docs/architecture.png）の端末側の 2 つの箱の文字だけを今の実装に合わせて描き直す（公式アイコンの zip が無くても動く）。"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

OUT = Path(__file__).resolve().parent.parent / "architecture.png"
f = lambda size: ImageFont.truetype("C:/Windows/Fonts/BIZ-UDGothicR.ttc", size)
img = Image.open(OUT).convert("RGB")
d = ImageDraw.Draw(img)


def lines(x, y, w, h, fill, texts):
    d.rectangle((x + 96, y + 56, x + w - 6, y + h - 8), fill=fill)
    for i, t in enumerate(texts):
        d.text((x + 100, y + 60 + i * 31), t, font=f(19), fill="#334155")


lines(60, 230, 560, 280, "#EFF6FF", [
    "・Navigation SDK：徒歩の案内＋浸水想定を地図に",
    "・Gemini Live（Firebase AI Logic）：声の会話。",
    "　頼みごとは関数で受け、選び直しはアプリ",
    "・Maps SDK：拡大できる地図／カメラに矢印",
    "・英語・日本語・中国語・韓国語",
    "・通信がある間に、圏外への備えを保存",
])
lines(60, 540, 560, 330, "#F0FDF4", [
    "・保存したパック（避難先・浸水想定・音声）",
    "・歩ける道のグラフ（OpenStreetMap・24 万点）",
    "・避難先の計算：間に合う所を選ぶ",
    "　（0.62m/秒・3階以上・到達＝揺れた＋26分）",
    "・保存した地図・方角の矢印・読み上げ",
    "・端末の音声入力 → FunctionGemma 270M",
    "　（LiteRT-LM）が頼みごとを聞き分ける",
    "　使えない時はキーワード・ボタンに戻る",
])
img.save(OUT)
