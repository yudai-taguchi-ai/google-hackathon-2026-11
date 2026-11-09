# FunctionGemma 追加学習の共通採点（2026-11-09）

学習済みモデルの出力を Google Drive の `tsunami-functiongemma-output/predictions.jsonl` から取得し、保存時の `predicted` / `predicted_args` を `name` / `args` に読み替えて、既存の `finetune.score` で採点した。予測や正解は変更していない。テスト400件は追加学習に使用していない。

| 指標 | キーワード方式 | FunctionGemma | 成功の線 | 判定 |
|---|---:|---:|---:|---|
| 関数名・引数の完全一致 | 70.5% | **93.5%** | 90%以上・キーワード方式より上 | OK |
| 最低の頼みごと別正答率 | lost_family 50.0% | reselect(wheelchair) **85.0%** | 全種類85%以上 | OK |
| 最低の言語別正答率 | ko 66.0% | en **91.0%** | 全言語85%以上 | OK |
| 危ない間違い | 15.0% | **3.125%（5/160）** | 2%以下 | **NG** |
| 関係ない言葉で動く | 10.0% | **2.5%** | 3%以下 | OK |
| 関数呼び出しを読めない | 0.0% | **0.0%** | 1%以下 | OK |
| 端末での遅いほうから5%の応答時間 | 未計測 | **未計測** | 3,000ms以内 | 未判定 |
| 端末用モデルの大きさ | 該当なし | **未変換** | 300MB以下 | 未判定 |

頼みごと別: ask_floor 87.5% / injured 87.5% / lost_family 97.5% / other 97.5% / repeat 97.5% / reselect(normal) 97.5% / reselect(slow) 90.0% / reselect(wheelchair) 85.0% / road_blocked 97.5% / when_to_return 97.5%。

言語別: en 91.0% / zh 93.0% / ko 94.0% / ja 96.0%。

この結果は合成テスト文に対するもので、人がスマホの音声入力を通した文は未評価。危ない間違いが目標を超えるため、この数値だけを根拠に避難行動を自動で切り替えない。端末変換後は出力の一致、応答時間、実際の音声入力を別途確認する。

学習条件: `google/functiongemma-270m-it`、学習1,440件、検証160件、3エポック、最良チェックポイント270。保存先: [Google Drive の学習成果物](https://drive.google.com/drive/folders/1qC0TBPGHmZFSfScrLtEUa47no1QDQSo1)。モデルの重みと予測全文は GitHub に含めない。
