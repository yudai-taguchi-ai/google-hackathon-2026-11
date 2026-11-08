# 通信が無いときに動く既存サービスの調査（2026-11-08）

## 結論
- オフラインで動くものは、地図・メッシュ通信・衛星・スマホの中の AI のどれもすでにある。「無い＝技術的に厳しい」ではない
- スマホの中の AI（Gemma など）で災害時に案内するアプリは、2025〜2026 の Kaggle ハッカソン作品として複数ある（LIKAS・MeshGemma・Ash・Gemergency）。製品として広く使われているものは見つからなかった
- 日本向け・旅行者向け・避難場所のデータに基づくものは見つからなかった
- 逆風: au Starlink Direct で、au の対応機種は圏外でも Google マップなど一部アプリが衛星で使える（2025-08〜）。ただし旅行者の SIM が対象かは未確認

## 種類ごとの既存サービス
| 種類 | 例 | 通信なしで何ができるか | 弱いところ |
|---|---|---|---|
| オフライン地図 | 東京都防災アプリ、Google マップのオフラインエリア | 事前に保存した地図と避難所を見る | 東京都防災アプリは都内向け。Google マップは避難場所の種類（津波に使えるか）を区別しない（前回の確認） |
| メッシュ通信（スマホ同士をつないで中継） | Bridgefy、Briar、スマホdeリレー（構造計画研究所・東北大） | 近くの人とメッセージをやりとり | 届く距離が短い・周りに使う人が要る。Bridgefy は安全性の指摘あり。スマホdeリレーの今の提供状況は不明 |
| 専用の無線機 | Meshtastic（LoRa） | 1〜4km 程度でメッセージ | 機械を買う必要がある |
| 衛星 | au Starlink Direct（2025-08 からデータ通信・19 アプリ・当面無料）、Pixel の衛星SOS（日本は未対応と見られる） | メッセージ・一部アプリ・緊急通報 | au の契約と対応機種だけ。高速データ・音声は 2027 年の予定 |
| スマホの中の AI で災害支援 | LIKAS（フィリピン・Gemma 4 E2B・経路と避難所の順位づけを端末内で）、MeshGemma（Gemma 4＋メッシュ・iOS）、Ash（iOS・Gemma 4・56 の対応マニュアルに基づく）、Gemergency（Gemma 3n・応急手当）、Off Grid AI（2026-07 にオフライン防災システムを発表・プレスリリースのみ） | 質問に答える・応急手当・経路 | ほとんどがハッカソン作品。実際の災害で使われた記録は見つからない |

## 通信が止まる規模（能登半島地震 2024-01）
- 最大 839 の携帯基地局が停波（うち石川県 799、1/3 時点）。停電の長期化と土砂崩れによる回線の断絶が原因
- 応急復旧は 1/15〜1/17（立入困難地点を除く）＝ 約 2 週間

## 使える Google の技術（端末の中で動くもの）
- Gemma 4（2026-04 公開）E2B / E4B はスマホ向け。Android でも iOS でも動かせる
- Gemini Nano（ML Kit Prompt API）: 今いちばん良く動くのは Pixel 10。Gemini Nano 4（Gemma 4 ベース）は 2026-07 に開発者向けプレビュー。対応機種が限られる
- ML Kit 音声認識: 通常モードは Android 12 以上のほとんどの機種で端末内で動く

## 未確認
- 旅行者（海外の SIM・eSIM）が au Starlink Direct の対象になるか
- LIKAS などの作品が実機でどれくらいの速さ・電池で動くか
- Gemma 4 E2B が日本語・旅行者の母語で、避難の案内に足りる品質か

## 出典
- [LIKAS（DEV Community）](https://dev.to/jpcurada/likas-an-offline-disaster-companion-for-the-philippines-powered-by-on-device-gemma-4-e2b-ao1)
- [MeshGemma（Kaggle Gemma 4 Good Hackathon）](https://www.kaggle.com/competitions/gemma-4-good-hackathon/writeups/new-writeup-1778607604484)
- [Gemergency](https://charming-whaley.github.io/gemergency_ios_app_code)
- [Google：Gemma 3n の活用例](https://blog.google/technology/developers/developers-changing-lives-with-gemma-3n/)
- [GIGAZINE：Off Grid](https://www.gigazine.net/news/20260401-off-grid-mobile-ai/)
- [Android Developers Blog：端末内推論（2026-07）](https://android-developers.googleblog.com/2026/07/android-on-device-inference.html)
- [ML Kit Prompt API](https://developer.android.com/blog/posts/ml-kit-s-prompt-api-unlock-custom-on-device-gemini-nano-experiences)
- [au：Starlink Direct のコラム](https://www.au.com/column/network/starlink/article-0001/)
- [ビジネスネットワーク：au Starlink Direct のデータ通信](https://businessnetwork.jp/?p=29774)
- [ITmedia：東京都防災アプリのオフライン地図](https://www.itmedia.co.jp/news/article/1910/11/1191011127/)
- [EE Times Japan：スマホdeリレー](https://eetimes.itmedia.co.jp/ee/articles/1606/01/news053.html)
- [TechCrunch：Bridgefy](https://techcrunch.com/?p=2067907)
- [arXiv：オフラインメッセージアプリの比較](https://arxiv.org/pdf/2509.22568)
- [総務省 令和6年版 情報通信白書（移動通信）](https://www.soumu.go.jp/johotsusintokei/whitepaper/ja/r06/html/nd112120.html)
- [lifehacker：Pixel の緊急機能](https://www.lifehacker.jp/article/2609-google-pixel-emergency-features/)

---

# 追記：海外作品から取り入れる技術・オンラインとオフラインの比較（2026-11-08）

チームの方針: 「気をつける点」の解決策（AI が無いとできない部分に絞る／日本の公式データ×旅行者×到達時間で差を出す／旅行者の SIM が衛星の対象かを確かめる／Gemma 4 E2B を自前のアプリに入れる）はすべて採用。

## 1. 海外作品の作り方と、取り入れるもの
| 作品 | 作り方（公開情報） | ライセンス |
|---|---|---|
| LIKAS | Gemma 4 E2B を LoRA で調整→Q4_K_M GGUF（約1.8GB）。llama.rn（llama.cpp）でアプリ内実行。React Native＋MapLibre。地図・避難所・歩行者用の道のグラフ（OSM から事前計算）・モデルをアプリに同梱し、ダイクストラ法で経路。道具は4つ（手順を出す・最寄りの避難先へ経路・近くの施設・利用者情報）。GBNF 文法で「道具の名前と引数の選択肢」以外を出せなくする。温度 0.4。ユーザーが避難を頼んだのにモデルが道具を呼ばなければ、決まった処理で直接呼ぶ。モデルが読み込めない・電池15%未満なら、キーワードで振り分ける仕組みに切り替え。手順は公式機関の文をそのまま引用 | 記載なし（コードは使わず、考え方だけ） |
| MeshGemma | iPhone 同士を Bluetooth／Wi-Fi Direct でつなぐ。報告に Ed25519 署名＋Lamport 時計。中継して届け、つながり直した端末に未着分を送る。Gemma 4 E2B（IQ2_M・約2.3GB＋画像用 約1GB）で最大50件の報告を200バイト以下に要約。無線送信は模擬（画面に明記）。Expo＋llama.rn＋MapLibre | CC-BY 4.0 |
| Ash | iOS。Gemma 4 E2B/E4B を端末内で、文字・画像・声。56 の対応マニュアルに基づいて答える。iPhone 15 Pro 以上、初回に約1.4GB を落とす | Apache 2.0 |

取り入れるもの:
1. 道具の呼び出しを文法で縛る（モデルは決まった道具と、データベースにある避難先の番号しか出せない）→ 今の「Gemini に場所を作らせない」をオフラインでも守れる
2. 決まった処理での救済と、モデル無しの予備の振り分け（電池が少ない・モデルが落ちた時）
3. 歩行者用の道のグラフを事前に作ってアプリに入れ、端末内で経路を出す
4. 手順は公式の文をそのまま出す（モデルに言い換えさせない）
5. 実際には動かしていない部分は画面に明記する（MeshGemma。大会の「動いていない画面を動いているように見せる編集は失格」にも合う）

取り入れないもの: メッシュ通信（目的が「連絡」で、今回の「避難先に着く」と違う）、モデルの追加学習（LIKAS は過学習で苦労。まず文法の縛りと指示で足りるか試す）

実行の仕組みは llama.cpp ではなく Google の LiteRT-LM を第一候補にする。Gemma 4 の関数呼び出しに公式対応。E2B で GPU 52 トークン/秒・CPU 46.9 トークン/秒（Galaxy S26 Ultra・公式の数字）。中核を Google 技術にする大会の条件にも合う。

## 2. オンラインとオフラインで機能がどう変わるか
| 機能（plan.md の ID） | オンライン（今の計画） | オフライン（通信なし） | 落ちるもの |
|---|---|---|---|
| 警報を知る（B3） | Cloud Run が気象庁 XML を見て通知 | アプリの通知は届かない。基地局が生きていれば緊急速報メールは届く。基地局が止まれば何も届かない → 利用者が「揺れた／警報を見た」を押して始める | 自動で始まらない |
| 到達予想時刻 | 気象庁 XML の予報区ごとの時刻 | 事前に入れた想定（南海トラフの想定到達時間など）の一番早い値で計算 | その時の実際の値ではない（安全側に倒す） |
| 間に合う避難先を選ぶ（B2） | Routes API の歩く時間 | 端末内の道のグラフで歩く時間（LIKAS 方式）。避難先・浸水想定は事前保存 | 道のデータが OSM になり、精度が少し落ちる可能性 |
| 地図（W1） | Google マップ | 事前保存の地図（MapLibre＋地図の画像） | Google の地図ではなくなる |
| Google マップで案内（W2） | URL で Google マップのナビを開く | オフラインエリアを入れていれば徒歩も動いた報告あり（公式は運転のみ明記） | 未確認 |
| アプリ内ナビ（A1・Navigation SDK） | 徒歩・経由地つき | オフラインで経路を引けるかは未確認（引けない前提で考える）→ 自前の案内（GPS は通信なしで動く）| 曲がり角ごとの案内の質 |
| 声の会話（W3） | Gemini 3.8 Live | Gemma 4 E2B（LiteRT-LM）＋端末内の音声認識（ML Kit 通常モード）＋端末の読み上げ | 速さ・言語の数・会話の自然さ |
| 状況に合わせて選び直す（「子どもが走れない」） | Live が関数を呼ぶ | Gemma が文法で縛った道具を呼ぶ → 同じ選ぶ処理を端末内で | ほぼ同じ（処理は同じ）|
| 宿の避難経路図（W4） | 撮る→Argon で手順に | 事前に作った手順を表示・読み上げ。その場で撮るなら Gemma 4 の画像入力（精度は未確認） | その場で撮った場合の精度 |
| 自治体 PDF（B5）・データ更新 | 行き先ごとに取得 | 旅行前に取得済みのものを更新日つきで使う | 最新でない可能性（日付を出す）|
| Web（QR から開く） | 全部使える | 事前に開いて保存していれば地図と経路だけ。AI は動かない（スマホの Chrome の端末内 AI は公式に記載なし） | AI 全部 |

## 3. 競合はオンラインとオフラインで違う
| | オンラインの時 | オフラインの時 |
|---|---|---|
| 主な競合 | Google マップの Gemini（マップに相談・ナビ中の Gemini）、Gemini アプリ、Safety tips（通知・流れ図）、NERV 防災・Yahoo!防災速報（通知） | Google マップのオフラインエリア（地図だけ・避難先の種類を区別しない）、東京都防災アプリ（オフライン地図・都内だけ）、Safety tips の流れ図（オフラインで開けるかは未確認）、海外の端末内 AI 作品（LIKAS・Ash・Gridsend。日本のデータ無し） |
| 一番強い競合 | Google 自身（Maps＋Gemini） | 無し（Gemini は動かない）。ただし au Starlink Direct で「オフラインでなくなる」人がいる |
| 差の出し方 | 精度（公式データ・浸水想定・到達時刻で採点して Gemini より正しい） | 動くこと自体＋公式データに基づく判断＋母語の会話 |
| 審査の「Gemini で代わりが効かない」 | 精度の差で示す必要がある（評価実験 E1） | 通信が無いと Gemini は使えない、で示せる |

---

# 追記2：Navigation SDK のオフライン・ハッカソン後に出た技術（2026-11-08）

## Navigation SDK はオフラインで経路を引けるか
- 公式 FAQ（Android）:「オフラインモードは今のところ無い。ただし、その旅程の情報は先に保存する」「保存されるのは 15〜20 分ぶんの経路情報と、道を外れたときの別の経路」。電波が悪い間は GPS とセンサーで位置を推定する
- 通信が無い状態からは経路を引けない。通信があるうちに案内を始めれば、15〜20 分ぶんは途切れても続く見込み。道を外れたときの引き直しが通信なしで動くかは書かれていない
- → 警報直後に通信があれば Navigation SDK で始める。最初から通信が無ければ、端末内の道のグラフで自前の案内。機内モードでの実機確認が要る
- 参考: Mapbox の Navigation SDK はオフラインの経路探索に対応（Google 技術ではない）

## Gemma 4 Good Hackathon の締切（2026-05-18）より後に出た技術
| 技術 | 公開 | 改善できる所 | 注意 |
|---|---|---|---|
| LiteRT-LM の MTP（複数の文字を一度に予測） | 公式ブログ 2026-05-19（v0.11.0 は 05-07） | 端末内 AI の出力が GPU で最大 2.2 倍・CPU で最大 1.5 倍速く | E2B の CPU では自由な文章が遅くなることがある。道具呼び出しの検証では速くならなかった報告あり（下） |
| LiteRT-LM の制約付きデコード（JSON Schema・正規表現・文法） | 2026-05 のブログで紹介 | LIKAS が自作した「文法で縛る」を公式の機能で | 標準の関数呼び出しの出力に効くかは未確認（利用者が要望を出している） |
| ML Kit Prompt API の Structured Output（型・選択肢で出力を縛る） | I/O 2026 で予告、2026-07-21 のブログでコード | Gemini Nano で、避難先の番号を選択肢として縛る | Gemini Nano が動く機種だけ |
| Prompt API の prefix caching（共通の指示文の計算を使い回す） | I/O 2026 で予告 | 避難先データの長い指示文を毎回計算しない → 速く | 提供状況は未確認 |
| ML Kit GenAI 音声認識 | 2026-07（alpha） | 端末内で、話している途中から文字にする。通常モードは Android 12 以上のほとんどの機種 | 高品質モードは Pixel 10 だけ。対応言語の一覧は未確認 |
| Gemini Nano 4（Gemma 4 ベース） | 4月予告→ 2026-08 の Pixel 11 から | 140 以上の言語・音声入力・画像の読み取り | 対応は Pixel 11 系と Galaxy Z Fold8/Flip8 系の 7 機種だけ（報道） |
| Firebase AI Logic のハイブリッド推論 | 2026-04 試験提供、07 にブログ | PREFER_IN_CLOUD で「普段はクラウドの Gemini、オフラインなら端末内」に自動で切り替え | 端末側は Gemini Nano（機種が限られる）。試験提供。Live API の声の会話は対象外の見込み |
| ADK for Android・AppFunctions | I/O 2026（試験提供） | 端末とクラウドをまたぐエージェント | 今回は使わない |

## 外部の検証データ（Pixel 10a・Gemma 4 E2B・LiteRT-LM・GPU）
Google AI フォーラムの投稿（利用者による検証）:
- 道具の呼び出し 357 件で合格 71.4%。引数 0〜1 個で 87〜100%、2 個で 78〜88%、文字の引数 3 個で 43〜67%、4 個で 0%
- JSON が壊れる 8.9%。GPU で数字が崩れる（1029.3 → 1.0,293）。CPU では起きなかった
- かかる時間: 中央値 9.3 秒、遅い方の 5% は 26.4 秒以上。MTP を入れても速くならなかった
→ この案への影響:
  - AI を「最初の避難先を出す」流れに入れない。警報→避難先と経路は決まった処理で即座に出し、AI は後からの質問（「子どもが走れない」など）だけに使う
  - 道具は引数 1〜2 個・避難先は番号で渡す。座標などの数字を AI に書かせない
  - 壊れた出力は捨てて決まった処理に戻す（LIKAS の救済と同じ）

## オフラインで確かめること（優先順）
| ID | 確かめること | 方法 |
|---|---|---|
| O1 | チームの Android で Gemma 4 E2B（LiteRT-LM）がどれくらいの速さで動くか | AI Edge Gallery で試す → 自分のアプリで計測 |
| O2 | 自分たちの道具（1〜2 引数）を英語・日本語で正しく呼ぶ割合 | 30〜50 問の質問で採点 |
| O3 | Navigation SDK で案内を始めてから機内モードにして、何分・どこまで続くか。道を外れたら | 実機（堺市で歩く時にも） |
| O4 | 端末内の音声認識と読み上げが、英語・日本語・中国語・韓国語で通信なしで動くか | 実機で機内モード |
| O5 | 端末内の道のグラフ（OSM）で出した歩く時間と、Routes API との差 | 30 地点で比べる |

## 出典（追記2）
- [Navigation SDK for Android FAQ](https://developers.google.com/maps/documentation/navigation/android-sdk/faq)
- [Mapbox Navigation SDK：オフライン](https://docs.mapbox.com/android/navigation/guides/advanced/offline/)
- [Gemma 4 Good Challenge の受賞作（Google、2026-08-24）](https://blog.google/innovation-and-ai/technology/developers-tools/winning-entries-gemma-4-good-challenge/)
- [Blazing fast on-device GenAI with LiteRT-LM（2026-05-19）](https://developers.googleblog.com/en/blazing-fast-on-device-genai-with-litert-lm/)
- [LiteRT-LM：Gemma 4](https://developers.google.com/edge/litert-lm/models/gemma-4)
- [LiteRT-LM 概要](https://ai.google.dev/edge/litert-lm/overview?hl=en)
- [I/O '26 の Android の AI 更新](https://developer.android.com/blog/posts/top-ai-on-android-updates-for-building-intelligent-experiences-from-google-i-o-26)
- [Android 端末内推論（2026-07-21）](https://android-developers.googleblog.com/2026/07/android-on-device-inference.html)
- [ハイブリッド推論（2026-07）](https://android-developers.googleblog.com/2026/07/build-intelligent-android-apps-cloud-hybrid-inference.html?m=1)
- [Gemini Nano 4 の対応機種（Android Authority）](https://androidauthority.com/gemini-nano-4-supported-devices-3704226)
- [9to5Google：Nano 4 の最初の機種](https://9to5google.com/2026/08/27/gemini-intelligence-nano-4/)
- [Google AI フォーラム：Gemma 4 E2B の道具呼び出しの検証](https://discuss.ai.google.dev/t/gemma-4-e2b-litert-lm-0-10-x-on-mali-gpu-patterns-from-a-structured-tool-calling-eval/143976)

---

# 追記3：技術的に厳しい所と、既存サービスを参考にした対策（2026-11-08）

結論: 全体が厳しいのではなく、厳しいのは「通信なしの AI の会話」の1点。既存のオフラインのサービスは、どれも AI ではなく「事前に用意したデータ」で動いている。AI は無くても止まらない上乗せにする。

| 厳しい所 | 何が厳しいか | 参考にする既存サービス | 対策 |
|---|---|---|---|
| 1. 端末内 AI の遅さと誤り | 返事に中央値 9.3 秒、道具の呼び出し 71%（Pixel 10a の検証） | LIKAS（決まった処理での救済・キーワードの予備）、Safety tips（コミュニケーションカード＝決まった質問を多言語で表示） | 最初の避難先は決まった処理で出す。AI には「用意した答えのどれか」を選ばせるだけ（引数 0〜1 個＝87〜100% の帯）。答えの文と音声は旅行前に Cloud Run の Gemini で、その地域・その人向けに作っておく |
| 2. 通信なしの道案内 | Navigation SDK にオフラインモードが無い | Navigation SDK の 15〜20 分の事前保存、Google マップのオフラインエリア、東京都防災アプリ（区市町村ごとに地図を事前保存） | 通信があるうちに案内を開始。圏外からは端末内の道のグラフ（LIKAS）。地図と道は行き先の地域だけ保存 |
| 3. 警報が届かない | 基地局が止まるとアプリの通知も緊急速報メールも来ない | 気象庁の呼びかけ「強い揺れを感じたら、警報を待たずに避難」 | 大きな「揺れた／警報を見た」ボタンで始める。到達時刻は事前に入れた想定の一番早い値で計算 |
| 4. 多言語の声 | 端末内の音声認識・読み上げの言語が機種で違う | Safety tips（15 言語の決まった表示） | 読み上げる文の音声は旅行前に作って保存。聞き取りは端末内の音声認識が使える言語だけ、無理なら画面のボタンで選ぶ |
| 5. モデルの大きさ・電池 | Gemma 4 E2B は約 1.4〜2.3GB。電池を食う | Ash（初回に約 1.4GB をダウンロード）、LIKAS（電池 15% 未満で AI を止める） | Wi-Fi のある宿でダウンロード。電池が少なければ AI を止めて決まった処理だけ |
| 6. 機種の差 | Gemini Nano 4 は 7 機種だけ。古い機種は遅い | Ash（対応機種を明記・iPhone 15 Pro 以上） | Gemma 4 E2B を LiteRT-LM で入れる。動かない機種は AI なしでも全部の避難機能が使える |
| 7. デモで本当の圏外を見せにくい | 本物の災害は再現できない | MeshGemma（模擬の部分を画面に明記） | 機内モードで実演。模擬（過去の警報の流し直し）は画面と動画に明記 |
