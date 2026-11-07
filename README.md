# 旅行者向け 津波避難ナビ（仮称）

GDG GoC Japan DevFest Hackathon 2026 ／ カテゴリ：**観光**

津波警報が出たとき、旅行者のいる場所から「どこへ・どう逃げるか」を公式の避難場所データから選び、母語の声で会話しながら道案内するアプリ。

## まず読むもの
| ファイル | 中身 |
|---|---|
| [docs/plan.md](docs/plan.md) | 何を作るか・担当範囲・構成・日程・役割・提出物 |
| [docs/verification.md](docs/verification.md) | **確かめないといけないこと**の一覧（致命的なものから順） |
| [docs/setup-android.md](docs/setup-android.md) | Android Studio の導入と、最初に作る検証アプリ |
| [docs/background.md](docs/background.md) | この案を選んだ理由・Gemini 単体との比較・使うデータ |

## 締切
**2026-11-10（火）17:00** コードフリーズ・提出。

## 守ること
- **API キー・パスワードをコミットしない**（`.env`・`local.properties` に置く。どちらも `.gitignore` 済み）
- 2026-11-05 10:00 より前に書いたコードをこのリポジトリに貼らない（失格条件）
- 技術を選んだら「比べた案・選んだ理由・捨てた理由」を `docs/decisions.md` に残す（提出ドキュメントの選定理由と AI 活用欄にそのまま使う）
- 中核は Google 技術。実行基盤は Cloud Run を使う
