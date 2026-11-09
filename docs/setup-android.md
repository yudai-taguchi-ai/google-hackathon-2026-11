# Android の準備と、最初に作る検証アプリ

> 本番のアプリの雛形と作り方は [android-app.md](android-app.md)（2026-11-09）。

最終更新: 2026-11-07

目的: verification.md の **V1**（Navigation SDK で「徒歩」と「複数の経由地」を同時に使えるか）を確かめる。ほかに V2・V10・V13 も同じアプリで確かめる。

細かい手順や版の番号は変わるので、各段階で公式の手順を見ながら進める。

## 1. Android Studio を入れる
- Android Studio（Android アプリを作るための Google の開発ソフト）: https://developer.android.com/studio
- 初回起動のセットアップで、Android SDK（Android 用の部品一式）も入れる

## 2. Android の実機をつなぐ
- 端末の「設定 → デバイス情報 → ビルド番号」を 7 回タップすると「開発者向けオプション」が出る
- 開発者向けオプションで「USB デバッグ」を ON にし、USB で PC につなぐ
- 公式: https://developer.android.com/studio/run/device

## 3. Google Cloud 側の準備
- Google Cloud のプロジェクトで「Navigation SDK」と「Routes API」を有効にする
- API キーを作り、**使える API と Android アプリ（パッケージ名＋署名）に制限をかける**
- 料金・クレジットの扱いは verification.md の V3・V15
- 公式: https://developers.google.com/maps/documentation/navigation/android-sdk/set-up-project

## 4. API キーの置き方（コミットしない）
- キーは `local.properties`（.gitignore 済み）に書き、Secrets Gradle Plugin で読み込む
- `local.properties` やキーの入ったファイルをコミットしていないか、push 前に `git status` で確かめる
- 公式: https://developers.google.com/maps/documentation/android-sdk/secrets-gradle-plugin

## 5. 最初に作る検証アプリ（V1・V2・V10・V13）
画面は地図（ナビ）1 枚だけでよい。

やること:
1. 位置情報の許可をもらう
2. Navigation SDK の利用規約の確認画面を出す（SDK の決まり）
3. 移動手段を **WALKING** にする
4. 経由地 3 か所＋目的地を `setDestinations` で渡す（場所は自宅近くで、実際に歩ける所）
5. 案内を始める（`startGuidance`）
6. 各経由地に着いたら `continueToNextDestination` → `startGuidance` で次へ進む
7. 道が変わったら `RouteChangedListener` でログを出す

確かめること:
| 見ること | 結果を書く先 |
|---|---|
| 徒歩＋経由地 3 か所で道が出るか（エラーなら内容を控える） | V1 |
| 経由地ごとに「到着」になり、次へ進めるか | V1 |
| わざと違う道へ歩いた時、どこへ向かって引き直すか | V2 |
| 案内中に別の音声（音楽アプリなど）を流すとどうなるか | V10 |
| 機内モードにして案内が続くか | V13 |

参考:
- ルートの案内（移動手段の指定）: https://developers.google.com/maps/documentation/navigation/android-sdk/route
- 複数の目的地: https://developers.google.cn/maps/documentation/navigation-sdk-android/multi-destination?hl=ja
- ルートトークン（車・二輪だけ。今回は使わない）: https://developers.google.com/maps/documentation/navigation/android-sdk/v1/route_token

## 6. このアプリのコードの置き場所
本番のアプリと分けるため、検証アプリは `android/nav-check/` に置く。確かめ終えたら、結果を verification.md に書く。
