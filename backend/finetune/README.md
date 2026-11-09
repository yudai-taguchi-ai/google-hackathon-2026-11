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

変換は WSL Ubuntu の `litert-torch==0.10.0` と `litert-lm-builder==0.18.0` で完了した。モデルは Google Drive の `tsunami-functiongemma-output/litertlm/tsunami-functiongemma-270m-dynamic-wi8.litertlm` に保存している。`dynamic_wi8_afp32` 量子化後の大きさは285,560,384 bytes（285.56 MB、272.33 MiB）。`litert-lm-peek` で `function_gemma` のメタデータと1,024トークンのKVキャッシュを確認済み。端末上の関数呼び出しと速度は実機で確認する。

### アプリ担当への受け渡し情報（2026-11-10）

- 保存先は [Drive の litertlm フォルダ](https://drive.google.com/drive/folders/1Fs5IC6ZM0fkrJJNx8-iag-pr3VckWAIA)。田口さんへの閲覧権限付与は共有先メールアドレスの確認待ち。
- 学習レポート `tsunami-functiongemma-output/report.json` の `best_checkpoint` は `finetune/output/checkpoints/checkpoint-270`。変換には保存済みの `tsunami-functiongemma-output/model/` を WSL の `/opt/functiongemma-model` にコピーして使用した。
- アプリで使う指示文と関数定義は、このフォルダの `train_functiongemma.py` の `SYSTEM` と `TOOLS` を使用する。変換時にこれらを変更する処理は行っていない。システム指示文は `You are a model that can do function calling with the following functions. Choose exactly one function for the user's message.`。
- 関数は `reselect(mobility=normal|slow|wheelchair)`、`ask_floor`、`road_blocked`、`lost_family`、`injured`、`when_to_return`、`repeat`、`other`。`reselect` 以外の引数は空オブジェクト。関数の説明文も `TOOLS` からそのまま使用する。
- 変換設定は `prefill_lengths=[512]`、`cache_length=1024`、`quantization_recipe="dynamic_wi8_afp32"`、`task="text_generation"`、`bundle_litert_lm=True`、`litert_lm_model_type_override="function_gemma"`。変換 API は `litert_torch.generative.export_hf.export.export`。環境は上記2パッケージに加えて `tensorflow==2.21.0`。
- SHA-256 は `c90233ffdb2e9fdea4a2ec703591922d6557a0447de418a53d3ad2587b04de4b`。WSL の変換出力と G ドライブ保存済みファイルの一致を確認した。
- 93.5% は変換前のモデルの400件評価。変換後の精度評価と実機の読み込み・応答時間測定は未実施。

## 現在の状態

Google Colab の T4 で3エポック学習し、モデルとレポートを Google Drive に保存済み。400件の完全一致は学習前 106件（26.5%）、学習後 374件（93.5%）。言語別は en 91/100、zh 93/100、ko 94/100、ja 96/100。共通 `score.py` による採点と、端末用 `.litertlm` への変換まで実施済み。端末上の速さとAndroidへの組み込みは未実施。
