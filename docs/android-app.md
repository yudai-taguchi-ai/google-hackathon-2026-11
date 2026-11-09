# Android アプリの雛形と作り方

最終更新: 2026-11-09。Android Studio の入れ方と実機のつなぎ方は setup-android.md の 1〜4 章。
版の番号（ライブラリのバージョン）はここに書かない。**各ライブラリの公式ページの最新を使う**。

## 1. 決めたこと
- 言語: **Kotlin**。画面: **Jetpack Compose**
- 置き場所: リポジトリの `android/`（検証用の `android/nav-check/` とは別）
- 実機: Android タブレット（Android 12 以上・GPS の有無を確認）。外ではテザリング
- オンライン: Navigation SDK（徒歩）＋ Firebase AI Logic（Vertex AI の Gemini・Live）
- オフライン: 保存したパック＋自前の計算（planner.py を Kotlin に移す）＋保存した音声＋小さいモデル（LiteRT-LM）＋質問のボタン

## 2. プロジェクトの作り方（最初の 1 人がやって push する）
1. Android Studio → **New Project → Empty Activity**（Compose の雛形）
2. 設定
   - Name: `TsunamiNavi`
   - Package name: `jp.oif.tsunaminavi`
   - Save location: リポジトリの `android/`
   - Language: Kotlin ／ Minimum SDK: **API 31（Android 12）**（タブレットの Android がそれより古ければ下げる）
   - Build configuration language: Kotlin DSL
3. そのまま実機で動かし、「Hello Android」が出たら `feat/android-scaffold` ブランチで push → PR → main

## 3. 足す部品（app/build.gradle.kts の dependencies）
| 部品 | 何のため | 公式の手順 |
|---|---|---|
| Navigation SDK for Android | 通信ありの徒歩の案内（地図も入っている。Maps SDK を別に足さない） | https://developers.google.com/maps/documentation/navigation/android-sdk/set-up-project |
| Secrets Gradle Plugin | Maps の API キーを `local.properties` から読む | https://developers.google.com/maps/documentation/android-sdk/secrets-gradle-plugin |
| Firebase BoM ＋ Firebase AI Logic（`firebase-ai`）＋ App Check | Gemini（Vertex AI）・Live API。App Check は必須（開発中はデバッグ用の provider） | https://firebase.google.com/docs/ai-logic |
| Play services location | 現在地 | https://developer.android.com/develop/sensors-and-location/location |
| LiteRT-LM（Android） | 端末の中で小さいモデルを動かす | https://ai.google.dev/edge/litert-lm/overview |
| Ktor client か Retrofit | Cloud Run の API を呼ぶ | どちらか慣れている方 |
| kotlinx.serialization | API とパックの JSON を読む | https://github.com/Kotlin/kotlinx.serialization |

## 4. 権限（AndroidManifest.xml）
`INTERNET` ／ `ACCESS_NETWORK_STATE` ／ `ACCESS_FINE_LOCATION` ／ `ACCESS_COARSE_LOCATION` ／ `RECORD_AUDIO` ／ `POST_NOTIFICATIONS`

## 5. 秘密の値の置き方（コミットしない）
| 値 | 置く場所 |
|---|---|
| Maps の API キー（Android アプリとパッケージ名で制限） | `android/local.properties` に `MAPS_API_KEY=...` |
| `google-services.json`（Firebase の設定） | `android/app/` に置く。`.gitignore` 済み。メンバーには Drive で渡す |
| Cloud Run の URL | `local.properties` に `API_BASE_URL=...`（秘密ではないが環境で変わる） |

push の前に毎回 `git status` で、上のファイルが入っていないことを見る。

## 6. フォルダの分け方（ぶつからないように担当ごとに分ける）
```
android/app/src/main/java/jp/oif/tsunaminavi/
  MainActivity.kt
  ui/            画面（Compose）。ホーム・案内中・設定           … Android 担当
  data/          API の呼び出し・パックの保存と読み込み          … Android 担当
  online/        Navigation SDK・Gemini Live                   … Android 担当
  offline/
    Planner.kt   避難先を選ぶ計算（planner.py を移す）          … 田口＋Claude Code
    Guidance.kt  方角の矢印と距離                               … 田口＋Claude Code
    Intent.kt    小さいモデルで頼みごとを聞き分ける・ボタン       … 追加学習の担当
  net/           通信が落ちたことの検知（ConnectivityManager）  … Android 担当
```
- 画面から offline/ を呼ぶ形を先に決める:
  - `Planner.plan(lat, lon, minutesLeft, slow, pack): Plan`
  - `Intent.classify(text): IntentResult`（`id` と引数。失敗したら `other`）
- 同じファイルを 2 人で同時に直さない

## 7. 作る順番（Android 担当）
1. 雛形を push（§2）
2. 現在地を取って画面に出す
3. `/api/plan` を呼んで第一候補を出す
4. Navigation SDK で徒歩の案内（V1 を兼ねる）
5. `/api/pack` を保存 → 機内モードで `Planner.plan` を呼ぶ（offline/ は田口側が並行で作る）
6. 通信の検知 → 切り替え（N8）
7. Gemini Live（V9）
8. 小さいモデルの組み込み（追加学習の担当と一緒に）

## 8. 参考
- Navigation SDK のルートの案内: https://developers.google.com/maps/documentation/navigation/android-sdk/route
- 複数の目的地: https://developers.google.cn/maps/documentation/navigation-sdk-android/multi-destination?hl=ja
- 通信の状態を知る: https://developer.android.com/training/basics/network-ops/reading-network-state
