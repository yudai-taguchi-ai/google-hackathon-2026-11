# 頼みごと分類の実機比較（開発中）

Android の debug ビルドに独立した比較画面を用意する。結果は画面表示だけで、避難操作は行わない。ルール方式、mBERT の ONNX モデル、FunctionGemma の LiteRT-LM モデルを同じ文で測る。実機検証は未実施。

## ルール方式と BERT を試す

1. Android タブレットに debug APK をインストールする。
2. `backend/finetune/output/mbert/onnx/model.onnx` と同じフォルダの `vocab.txt`、変換済み FunctionGemma の `.litertlm` をタブレットにコピーする。ファイルは Git 管理外。
3. 端末のアプリ一覧から「Intent comparison (debug)」を開く。USB デバッグ接続なら `adb shell am start -a jp.oif.tsunaminavi.INTENT_BENCHMARK -p jp.oif.tsunaminavi` でも開ける。
4. 3ファイルを選択し、文を入力して「同じ文章で測定」を押す。それぞれの判定と処理時間が表示される。

初期化時間はモデルの読み込み、判定時間はウォームアップ後の1回の推論とトークナイズを含む。ファイルのコピー時間は含まない。ルール方式は1回の判定。1回だけの測定はばらつくので繰り返し計測する。録音・音声認識時間は含まない。

## 検証済みと残り

- PyTorch と ONNX の BERT はPCで400件の判定がすべて一致。BERTの正答率88.5%、危険ケースの取りこぼし2.5%。
- Android の BERT トークナイザは4言語の代表文で元のトークナイザのIDと一致。実際のAndroid端末上の推論は未確認。
- FunctionGemma はPCで学習時と同じプロンプトを会話APIに直接渡して1文が成功。Android用比較画面にも同じ方式を実装。端末での推論と400件の一致は未確認。
- 評価は同じテスト400件と `backend/finetune/score.py` を使う。個人情報を含む発話を扱う前に、結果やログの共有範囲を確認する。
