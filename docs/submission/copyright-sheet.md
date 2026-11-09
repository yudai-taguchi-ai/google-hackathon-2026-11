# 著作物利用シート（下書き）

> 公式の様式に合わせて貼り替える。【要確認】は提出前に一次資料で確かめる。

## データ
| 名前 | 提供元 | 使い方 | 条件・出典の書き方 |
|---|---|---|---|
| 指定緊急避難場所データ（大阪市・堺市） | 国土地理院 | 避難先の候補（津波に使える所） | 国土地理院コンテンツ利用規約（出典の明記）【要確認】 |
| 国土数値情報 津波浸水想定（大阪府、2023年版 A40-23_27） | 国土交通省 | 浸水の深さ・階の目安・道の深さの判定。パック用に簡略化 | 大阪府分は商用利用・再配信可、出典の明記が必要 |
| 防災情報 XML（津波警報・訓練用のサンプル電文） | 気象庁 | 警報と到達予想時刻。デモは南海トラフの訓練電文 | 気象庁ホームページの利用規約【要確認】 |
| 津波避難ビル一覧 | 堺市 | 国のデータとの照合（確認だけ。アプリには入れない） | 出典を記載 |
| 津波の到達時間の想定 | 大阪府（泉佐野市の資料経由） | 圏外の到達時刻の既定値（26分） | 出典を記載 |
| 津波避難対策推進マニュアル検討会報告書（平成25年） | 消防庁（志摩市の計画の引用で確認） | 歩く速さの根拠（0.62m/秒・0.5m/秒） | 出典を記載 |
| 学習データ（train.jsonl・test.jsonl） | チームで作成（Gemini で生成） | FunctionGemma の追加学習 | 自作 |

## モデル・AI
| 名前 | 提供元 | 使い方 | ライセンス |
|---|---|---|---|
| Gemini 3.8 Flash（Vertex AI） | Google | 説明・翻訳・音声・学習データ・評価 | Google Cloud の利用規約 |
| Gemini Live（Firebase AI Logic・Gemini Developer API） | Google | 声の会話 | Firebase・Gemini API の利用規約 |
| Gemini 4 Argon | Google | 評価の比べる相手 | Google Cloud の利用規約 |
| FunctionGemma 270M | Google | 端末の中の聞き分け（追加学習） | Gemma Terms of Use【要確認】 |

## コード・ライブラリ
| 名前 | 使い方 | ライセンス |
|---|---|---|
| FunctionGemma 追加学習ノートブック（google-gemini/gemma-cookbook） | Colab のノートブックの元 | Apache 2.0 |
| FastAPI / uvicorn | サーバー | MIT / BSD-3-Clause |
| Shapely | 浸水想定の判定 | BSD-3-Clause |
| requests | API の呼び出し | Apache 2.0 |
| google-genai（Google Gen AI SDK） | Gemini の呼び出し | Apache 2.0 |
| Pillow | 構成図を描く | MIT-CMU（HPND） |
| Android Jetpack（AndroidX・Compose）・Kotlin・kotlinx（serialization・coroutines） | Android アプリ | Apache 2.0 |
| Google Play services（位置情報） | 現在地 | Android Software Development Kit License |
| Firebase Android SDK（AI Logic・App Check） | Gemini Live | Apache 2.0 |
| Navigation SDK for Android【実装中】 | 徒歩の案内 | Google Maps Platform の利用規約 |
| Transformers / TRL / datasets（Hugging Face） | 追加学習 | Apache 2.0 |
| AI Edge Torch / LiteRT | .litertlm への変換 | Apache 2.0 |

## サービス
| 名前 | 使い方 | 条件 |
|---|---|---|
| Routes API・Geocoding API（Google Maps Platform） | 道のり・評価の位置 | Google Maps Platform の利用規約 |
| Cloud Run・Secret Manager・Cloud Build・Artifact Registry | 実行基盤 | Google Cloud の利用規約 |
| GitHub Actions | Android アプリのビルド | GitHub の利用規約 |

## 素材
| 名前 | 使い方 | 条件 |
|---|---|---|
| Google Cloud 公式アイコン（cloud.google.com/icons） | 構成図 | Google の技術を正しく示す目的で使用可【要確認】 |
