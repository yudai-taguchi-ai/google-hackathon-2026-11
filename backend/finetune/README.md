# FunctionGemma の追加学習

旅行者の一言を、避難ナビで使う関数1個に変換する。避難場所そのものはモデルに選ばせず、既存の `Planner` が公式データから決める。

## データと結果

- `data/train.jsonl`: 4言語・1,600件。各言語・各ラベルで均等。
- `data/test.jsonl`: 別の言い方の400件。最終評価専用。
- 学習時は train のうち160件を検証用に分ける。残り1,440件で学習し、検証損失が最も低いエポックを採用する。
- `train_functiongemma.py` は学習前後の400件の正答率を比較し、言語別の結果と全予測を `output/` に保存する。正解条件は関数名と引数の完全一致。予測ファイルはチーム共通の `score.py` が読める `name`・`args` を含む。

## Google Colab の GPU で実行

`train_functiongemma_colab.ipynb` を Colab で開くと、3ファイルのアップロードから学習・Drive への保存まで順に実行できる。
アップロードするのは `train_functiongemma.py` と `data/train.jsonl`・`data/test.jsonl`。手元では必要に応じて同じフォルダにコピーしてから選ぶ。

1. [モデルのページ](https://huggingface.co/google/functiongemma-270m-it)で利用条件に同意する。
2. Colab の GPU ランタイム（T4 以上）を用意し、シークレット `HF_TOKEN` に読み取りトークンを設定する。トークンをノートブックや Git に書かない。
3. このリポジトリを Colab にクローンするか、`train_functiongemma.py` と `data/train.jsonl`・`data/test.jsonl` を `finetune/` 以下へアップロードする。クローンの場合はこの変更を含むブランチが GitHub にある必要がある。
4. Colab で以下を実行する。

```python
%pip install "torch" "transformers==4.57.1" "trl==0.25.1" "datasets==4.4.1" "accelerate" "protobuf" "sentencepiece"
from google.colab import userdata
import os
os.environ["HF_TOKEN"] = userdata.get("HF_TOKEN")
%cd /content/google-hackathon-2026-11/backend
!python -m finetune.train_functiongemma --output finetune/output
```

アップロード方式なら `%cd` を実際の `backend/` の場所に変更する。成果物は `output/model/`、`output/report.json`、`output/predictions.jsonl`。Colab は切断時にファイルが消えるため、学習後に成果物を Drive 等へ保存する。

`output/` は Git の対象外。チーム共通の物差しで採点するには、`backend/` から次を実行する。

```text
python -X utf8 -m finetune.score finetune/data/test.jsonl finetune/output/predictions.jsonl
```

11月9日の学習時に保存した予測ファイルは、このブランチで形式を合わせる前の `predicted`・`predicted_args` 形式。再学習は不要で、採点前に各行のキーを `name`・`args` に変換すればよい。実測した結果だけを提出資料へ転記する。誤分類は予測ファイルで確認できる。

保存済み予測を共通の `score.py` で採点した結果は [evaluation-2026-11-09.md](evaluation-2026-11-09.md)。全体正答率は93.5%だが、危ない間違いは3.125%で目標の2%以下に届いていない。

## 保存済みモデルを端末用に変換

`convert_saved_functiongemma_colab.ipynb` を新しい Colab ランタイムで開き、上から実行する。Drive の `tsunami-functiongemma-output/model/` を読み、`dynamic_int8` の `.litertlm` を `tsunami-functiongemma-output/litertlm/` に保存する。学習の再実行と Hugging Face トークンは不要。変換手順は [Google の Mobile Actions 例](https://ai.google.dev/gemma/docs/mobile-actions) をもとにしている。変換はまだ実測していないため、実行後にファイルの大きさ・端末での関数呼び出し・速度を確認する。

## 現在の状態

Google Colab の T4 で3エポック学習し、モデルとレポートを Google Drive に保存済み。400件の完全一致は学習前 106件（26.5%）、学習後 374件（93.5%）。言語別は en 91/100、zh 93/100、ko 94/100、ja 96/100。共通 `score.py` による採点まで実施済み。端末用変換、端末上の速さ、Android への組み込みは未実施。
