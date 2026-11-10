# 端末内の意図分類 比較アプリ

避難アプリとは別の Android アプリ（`jp.oif.intentbenchmark`）です。同じ文章をルール方式・mBERT・FunctionGemma で分類し、判定結果と時間を表示します。避難アプリを変更せず、避難操作も実行しません。実機での動作と速度は未確認です。

## 使い方

1. GitHub Actions の `intent-benchmark-apk` をダウンロードして、タブレットにインストールします。
2. タブレットに [BERTモデル](https://drive.google.com/file/d/1jf0VhkLy5whcsC6xGM2ZTnmFRHjFPmGd/view?usp=drivesdk)、[BERT辞書](https://drive.google.com/file/d/1fVKqXUHjl8EAoK6FTzt7n-PWW4I0hL-4/view?usp=drivesdk)、[FunctionGemmaモデル](https://drive.google.com/file/d/1AgfRlp5D_7u489kOO1BNC0ztVF-4L5cz/view?usp=drivesdk)を保存します。モデルは APK に含まれません。
3. 「Intent Benchmark」を開き、各ファイルを選択して文章を入力し、「同じ文章で測定」を押します。モデルを選ばなくてもルール方式を測れます。
4. 各方式の判定と時間を記録します。結果が出るまでは数十秒以上かかる場合があります。

判定時間はウォームアップ後１回の処理（トークン化を含む）です。初期化・ファイルのコピー・音声認識は判定時間に含みません。初期化は別表示です。ルール方式は１回の実行時間で、短すぎて計測の揺れが大きくなります。モデルは各回読み直すので、初期化時間にはキャッシュなどの影響があります。速度を比較する際は同じ端末で複数回測ってください。

FunctionGemma は避難アプリと同じプロンプト・CPU・LiteRT-LM Session API を使います。ただし実機での一致は未検証です。BERT は分類用の ONNX モデルと同じ辞書が必要です。

## 開発者向け

Java 17、Android SDK 35 と Gradle 8.11.1 が必要です。リポジトリのルートから `gradle -p intent-benchmark :app:testDebugUnitTest :app:assembleDebug` を実行します。

元の比較 PR で追加した BERT 学習・検証用スクリプトは `backend/finetune/` にあります。機械学習モデルと辞書は Git に含まれません。
