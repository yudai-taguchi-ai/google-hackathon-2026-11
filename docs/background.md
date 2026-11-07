# この案を選んだ理由と、調べたこと

最終更新: 2026-11-07

## 案を選ぶ条件（チームで決めたこと）
- 観光カテゴリに入る
- Google 技術で、これまでできなかったことができる
- 既存のデータ・道具を広げる形や、ニーズに合った使いやすい形で価値を出せばよい（公開データは Gemini も取りに行けるので、「アプリだけが持つデータ」は条件にしない）
- 軽さ・作りやすさは選ぶ理由にしない

## 困りごと
- 訪日客は 2025 年に 4,268 万人（過去最多）。旅行者には土地勘が無く、日本語の防災情報も読みにくい
- 2025 年 7 月、カムチャツカ沖の地震による津波警報で、日本の太平洋沿岸の約 200 万人に避難指示
- 2025 年 12 月、青森県東方沖の地震（震度 6 強）で「後発地震注意情報」を初めて発表
- 警報は届いても、「自分は今どこへ、どう逃げるか」が分からない

## 既存の道具と足りないところ
| 既存 | できること | 足りないところ |
|---|---|---|
| Safety tips（観光庁監修・15 言語） | 警報の通知、対応の流れ図、決まった質問を多言語で見せるカード、津波情報の地図 | 避難所は外部アプリへのリンク。AI・会話なし。App Store の評価 2.7（95 件） |
| Google 検索・マップの警報表示（2013〜） | 地震・津波の警報を表示 | 災害の種類に合った避難場所へ案内する機能は、公式資料では見つからなかった |
| Google マップの Gemini（マップに相談 2026-08-07〜、ナビ中の Gemini） | 場所の質問、ナビ中の会話 | 津波に使える避難場所を区別できるかは未確認（verification.md の V7） |
| Gemini 単体 | それらしい避難の助言 | 下の比較のとおり、間違いを含む |

## Gemini 単体との比較（参考）
「由比ヶ浜にいる。津波警報。どこへ逃げる？」を Gemini Argon（思考 高）と Gemini 3.8 Flash（思考 標準）に聞き、鎌倉市の地区別津波避難計画（2016 年 3 月）と照らした。

| Gemini の答え | 鎌倉市の資料 | 判定 |
|---|---|---|
| 長谷寺・鎌倉文学館・高徳院・御成中学校（両方） | 避難場所に載っている | 合っている |
| 鎌倉消防署・鎌倉わかみや（両方） | 避難ビルに載っている | 合っている |
| Argon：ダイヤモンド鎌倉別邸ソサエティ「由比ガ浜 4-6-46」 | 由比ガ浜 3-12-25 | 住所が違う |
| Argon：ウエリス鎌倉由比ヶ浜「由比ガ浜 3-11-48」 | 由比ガ浜四丁目 1136-26 | 住所が違う |
| Argon：ザ・パークハウス鎌倉若宮大路「由比ガ浜 2-10-4」 | 一覧に無い。この住所は鎌倉女学院（避難ビル） | 載っていない建物 |
| Flash：御成小学校「海抜約 30m」を高台として案内 | 「御成小学校（想定浸水範囲内）」 | 浸水が想定される場所を高台として案内 |
| 両方 | 浜のそばの鎌倉女学院・第一小学校・由比ガ浜ハイツなど | 触れていない |
| 両方 | 鎌倉市の想定は約 8 分で津波が到達 | 歩いて間に合うかを考えていない |

**注意**：資料は 2016 年のもので、最新の一覧はネット上で見つからなかった。今の指定と違う可能性があるため、この比較は参考扱い。本番の評価は、国のデータが新しい大阪市・堺市で行う（plan.md の 8）。

考え方：Gemini は災害が起きてから聞かれ、何も準備の無い状態で答える。その人の場所・宿・地域の資料が手元に無いので間違える。このアプリは事前に準備し、場所はプログラムが公式データから選ぶ。

あわせて分かったこと：国土地理院の全国データに、鎌倉市は「指定避難所のみ」を出しており、津波の避難場所は入っていない（同じ扱いの市町村が全国で 27）。鎌倉市の PDF は文字が正しく埋め込まれておらず普通には読めないが、Gemini に PDF のまま読ませると、避難ビル 27 件・避難場所 18 件を表にできた。

## 使うデータ
| データ | 中身 | 状態 |
|---|---|---|
| 国土地理院 指定緊急避難場所 | CSV・GeoJSON、緯度経度つき、災害の種類 8 つごとに使えるか | 確認済 |
| 大阪市 | 3,269 件（うち津波 2,184 件）。USJ のある此花区桜島に 2 件、天保山に 1 件 | 確認済 |
| 堺市 | 294 件（うち津波 151 件）。大浜の周りに 6 件 | 確認済 |
| 気象庁 防災情報 XML | Atom フィード、登録不要。津波警報の電文あり | 動作確認済 |
| 浸水想定区域 | 国・府の公開データ | 未確認（V4） |

## 出典
- 観光庁 Safety tips: https://www.mlit.go.jp/kankocho/seisaku_seido/kihonkeikaku/jizoku_kankochi/anzenkakuho/inbound/tool.html
- App Store Safety tips: https://apps.apple.com/jp/app/safety-tips/id858357174
- Google Public Alerts（日本、2013）: https://blog.google/topics/crisis-response/public-alerts-for-google-search-google
- Google Japan ブログ「マップに相談」: https://blog.google/intl/ja-jp/products/connect-communicate/japanese-ask-maps/
- 気象庁 防災情報 XML: https://www.data.jma.go.jp/developer/xml/feed/eqvol_l.xml
- 国土地理院 指定緊急避難場所データ: https://www.gsi.go.jp/bousaichiri/hinanbasho.html
- 鎌倉市 地区別津波避難計画（由比ガ浜・材木座）: https://www.city.kamakura.kanagawa.jp/sougoubousai/documents/01zaimokuza.pdf
- 鎌倉市 地区別津波避難計画（長谷・坂ノ下・稲村ガ崎）: https://www.city.kamakura.kanagawa.jp/sougoubousai/documents/01hase.pdf
- Google Maps URLs: https://developers.google.com/maps/documentation/urls/get-started
- 訪日客 4,268 万人（訪日ラボ）: https://honichi.com/news/2026/01/21/inbound202512/
- 2025 年の津波の避難指示（CTIF）: https://ctif.org/news/two-million-under-evacuation-advisory-when-2025-earthquake-triggered-pacific-tsunami-japan
- 後発地震注意情報の初発表（秋田魁新報）: https://www.sakigake.jp/news/article/20251209CO0071/
