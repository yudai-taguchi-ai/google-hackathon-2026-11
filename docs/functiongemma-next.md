# FunctionGemma の次の作業（モデル担当向け・2026-11-09 夜）

学習の結果（テスト 400 件で 93.5%）は backend/finetune/evaluation-2026-11-09.md。次は「スマホで動く形にして、アプリに入れる」。アプリ側の受け口は Claude Code（田口の側）が並行して用意する。

## 締め切り
- **11/10（火）9:00 までに 1〜3**。12:00 までにアプリへ入れて実機で確かめ、午後に撮影する
- 途中で詰まったら、その時点の状態（エラー文・どこまで進んだか）をすぐ共有する。12:00 に間に合わなければ、アプリはキーワード方式で撮影し、学習の結果は資料に載せる

## 1. .litertlm に変換する
- `backend/finetune/convert_saved_functiongemma_colab.ipynb` を Colab で上から実行（GPU は不要）
- できた `.litertlm` の**ファイル名と大きさ（MB）**を記録する

## 2. 変換で精度が落ちていないか確かめる（できれば）
- 量子化（dynamic_int8）で答えが変わることがある。テスト 400 件を変換後のモデルでも流し、`score.py` で表を出す
- LiteRT-LM の Python（`pip install litert-lm`）か、Colab で動く方法があればそれで。難しければ 20 件だけでもよい
- 変換前と同じ形の `predictions.jsonl`（`name`・`args`）にする

## 3. 受け渡し
- `.litertlm` を Google ドライブの `tsunami-functiongemma-output/litertlm/` に置き、**田口のアカウントに共有**して場所を知らせる（100MB を超えるので GitHub には入れない）
- あわせて知らせてほしいこと（アプリ側で同じ条件にするため）:
  - 変換に使ったチェックポイント（最良の 270 か）
  - システムの指示文と関数の定義が `train_functiongemma.py` の `SYSTEM`・`TOOLS` から変わっていないか
  - 変換の設定（`prefill_seq_len`・`kv_cache_max_len`・量子化）

## 4. 端末での速さ（9:00 以降でもよい）
- タブレットの **Google AI Edge Gallery** →「Mobile Actions」→「Load Model」で `.litertlm` を読み込み、何文か入れて 1 回の返事の秒数を測る（Gallery の関数は懐中電灯などで、こちらの関数とは違うので、答えの中身ではなく**速さと読み込めるか**を見る）
- アプリに入れた後は、アプリの中でも測る

## 5. チームで決めること（田口）
- 危ない間違い 5 件はすべて「分からない（other）→ ボタンへ」だった。違う行動をした間違いは 0 件。これを NG のままにするか、「ボタンに回すのは安全な失敗」として数え方を分けるか
