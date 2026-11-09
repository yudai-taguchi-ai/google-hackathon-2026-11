"""追加学習（FunctionGemma）用のデータを Gemini で作る。旅行者が慌てて話す一言 → 頼みごと（関数と引数）。

学習用とテスト用は、別の言い方の指示で別々に作る（覚え込むだけになるのを防ぐ）。
使い方（backend/ で）:
  GOOGLE_CLOUD_PROJECT=... ./.venv/Scripts/python -m finetune.make_dataset
出力: finetune/data/train.jsonl・test.jsonl（1行 = {"text", "lang", "name", "args"}）
"""
import json
import os
import random
import sys
import unicodedata
from pathlib import Path

from google import genai
from google.genai import types

import gcp_auth

OUT = Path(__file__).resolve().parent / "data"
MODEL = "gemini-3.8-flash"
LANGS = {"en": "English", "zh": "简体中文", "ko": "한국어", "ja": "日本語"}
TRAIN_PER = 40
TEST_PER = 10

# 頼みごとの一覧（docs/decisions.md 2026-11-09）。reselect だけ引数がある
LABELS = [
    ("reselect", {"mobility": "normal"}, "別の避難先にしたい・ここはやめたい・他の場所はある？（理由は言わない、または混んでいる・入れない）"),
    ("reselect", {"mobility": "slow"}, "早く歩けない・走れない（子ども・高齢者・妊娠中・足が痛い・荷物が重い）"),
    ("reselect", {"mobility": "wheelchair"}, "車いす・ベビーカー・杖・階段が使えない"),
    ("ask_floor", {}, "建物の何階まで上がればいいか・どこまで上がるか"),
    ("road_blocked", {}, "道がふさがっている・通れない・水が来ている・倒れた物で進めない"),
    ("lost_family", {}, "家族や友達とはぐれた・連れが見つからない"),
    ("injured", {}, "けがをした・血が出ている・動けない・具合が悪い"),
    ("when_to_return", {}, "いつ戻っていいか・ホテルに荷物を取りに戻っていいか・いつまでここにいるか"),
    ("repeat", {}, "もう一度言って・聞こえなかった・分からなかった"),
    ("other", {}, "上のどれでもない（雑談・関係ない質問・意味のない音・天気・食べ物・感謝の言葉など）"),
]

STYLE_TRAIN = "旅行者がスマホに話しかける一言。普通の言い方・丁寧な言い方・短い言い方・文法の崩れた言い方を混ぜる。"
STYLE_TEST = "慌てて叫ぶような断片・言い直し・音声認識の聞き間違い（似た音の単語・句読点なし）・方言やスラング・他の言語の単語が混ざる言い方。"


def generate(lang: str, desc: str, n: int, style: str, seed: int) -> list[str]:
    schema = types.Schema(type=types.Type.OBJECT, properties={
        "utterances": types.Schema(type=types.Type.ARRAY, items=types.Schema(type=types.Type.STRING)),
    }, required=["utterances"])
    res = gcp_auth.call(lambda client: client.models.generate_content(
        model=MODEL,
        contents=(
            f"津波警報で避難中の旅行者が、避難案内アプリに{LANGS[lang]}で話しかける一言を {n} 個作ってください。\n"
            f"意味: {desc}\n言い方: {style}\n全部違う言い方にする。1つは 2〜20 語程度。番号は付けない。（乱数の種: {seed}）"
        ),
        config=types.GenerateContentConfig(response_mime_type="application/json", response_schema=schema),
    ))
    return json.loads(res.text)["utterances"][:n]


def key(text: str) -> str:
    return unicodedata.normalize("NFKC", text).lower().strip(" .。!！?？")


def main() -> None:
    OUT.mkdir(exist_ok=True)
    train, test, seen = [], [], set()
    for li, (name, args, desc) in enumerate(LABELS):
        for lang in LANGS:
            for split, n, style, rows in (("train", TRAIN_PER, STYLE_TRAIN, train), ("test", TEST_PER, STYLE_TEST, test)):
                for text in generate(lang, desc, n, style, seed=li * 100 + len(rows)):
                    k = key(text)
                    if not k or k in seen:
                        continue
                    seen.add(k)
                    rows.append({"text": text.strip(), "lang": lang, "name": name, "args": args})
            print(f"{name} {args} {lang}: train {len(train)} / test {len(test)}", file=sys.stderr)
    random.Random(0).shuffle(train)
    for split, rows in (("train", train), ("test", test)):
        with open(OUT / f"{split}.jsonl", "w", encoding="utf-8") as f:
            for r in rows:
                f.write(json.dumps(r, ensure_ascii=False) + "\n")
    print(f"train {len(train)} 件・test {len(test)} 件 → {OUT}")


if __name__ == "__main__":
    main()
