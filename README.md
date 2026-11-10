# 旅行者向け 津波避難ナビ（仮称）

GDG GoC Japan DevFest Hackathon 2026 ／ カテゴリ：**観光**

津波警報が出たとき、旅行者のいる場所から「どこへ・どう逃げるか」を公式の避難場所データから選び、母語の声で会話しながら道案内するアプリ。

## まず読むもの
| ファイル | 中身 |
|---|---|
| [docs/implementation-plan.md](docs/implementation-plan.md) | **最新の実装計画**（担当・日程・API の約束） |
| [docs/requirements.md](docs/requirements.md) | 要件表（デモの場面ごとの合格の条件） |
| [docs/android-app.md](docs/android-app.md) | Android アプリの雛形と作り方 |
| [docs/modes.md](docs/modes.md) | Web・通信あり・通信なしの動きの違いとルール |
| [docs/intent-model.md](docs/intent-model.md) | 圏外の「聞き分け」モデルを作る担当向けのコンテキスト |
| [docs/functiongemma-next.md](docs/functiongemma-next.md) | FunctionGemma の次の作業（変換・受け渡し・端末での速さ） |
| [docs/member-submission-tasks.md](docs/member-submission-tasks.md) | 提出物（説明ドキュメント・著作物利用シート・フォルダ）と Web 版の確認などの担当向け |
| [docs/plan.md](docs/plan.md) | 何を作るか・担当範囲・構成・日程・役割・提出物 |
| [docs/verification.md](docs/verification.md) | **確かめないといけないこと**の一覧（致命的なものから順） |
| [docs/setup-android.md](docs/setup-android.md) | Android Studio の導入と、最初に作る検証アプリ |
| [docs/background.md](docs/background.md) | この案を選んだ理由・Gemini 単体との比較・使うデータ |

## 聞き分けモデルのテストデータ

`backend/finetune/data/test.jsonl` は、災害時の旅行者の発話を聞き分けるモデルの評価用データです。1 行に `text`（発話）、`lang`（言語）、`name`（分類）、`args`（引数）を JSON で記録します。`reselect` の `args.mobility` は `normal`・`slow`・`wheelchair` のいずれかです。分類の意味と採点方法は [docs/finetune-eval.md](docs/finetune-eval.md) を参照してください。

### 作成の考え方

- 人が手書きした約50件の日本語の例を出発点に、災害時に旅行者が話しそうな内容を考える。英語・中国語・韓国語でも AI に例を作らせ、似た状況の別の言い方を増やす。
- 生成文の言語・意味・分類を確認する。学習用と評価用を混ぜず、同じ文やよく似た文が両方に入らないようにする。
- 人の発話に対する性能も調べられるよう、人が実際に話したテストデータは合成データと区別して扱う。

### 現在のデータと注意点

現在の `test.jsonl` は、従来の400件に、英語・中国語・韓国語の合成データを各960件追加した計3,280件です。追加分は各言語で8種類の `name` を120件ずつ含みます。`reselect` は各言語で3種類の `mobility` を40件ずつ含みます。

追加した2,880件は、災害時の状況を想定した少数の基本文に、呼びかけや句読点を組み合わせて作成しました。基本文の中には似た表現があるため、人間の発話を幅広く代表するとは限りません。また、この追加分が手書きの約50件から直接生成されたかどうかは、このリポジトリからは確認できていません。

テストデータは学習に使わず、作成・調整の際に評価対象に合わせ込まないでください。400件から3,280件に拡張しているため、過去の400件での評価と新しい全件の評価は別の条件として報告します。人が実際に話した文も別途用意し、合成データの結果と併せて確認してください。

## 締切
**2026-11-10（火）17:00** コードフリーズ・提出。

## 守ること
- **API キー・パスワードをコミットしない**（`.env`・`local.properties` に置く。どちらも `.gitignore` 済み）
- 2026-11-05 10:00 より前に書いたコードをこのリポジトリに貼らない（失格条件）
- 技術を選んだら「比べた案・選んだ理由・捨てた理由」を `docs/decisions.md` に残す（提出ドキュメントの選定理由と AI 活用欄にそのまま使う）
- 中核は Google 技術。実行基盤は Cloud Run を使う
