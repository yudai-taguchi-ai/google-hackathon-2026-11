# Web画面の回帰テスト

Node.js 20以上と npm が必要です。このフォルダーで実行します。

```powershell
npm ci
npx playwright install chromium
npm test
```

実際の `backend/app/static/index.html` をChromiumで開き、API応答だけを模擬します。
APIキー・Python環境・公開サーバーは不要です。外部APIへの課金や訓練警報の変更はありません。

PC幅1280pxとスマホ幅390pxで、警報の状態、条件変更、古い応答の破棄、通信失敗・タイムアウト・再試行、位置情報エラー、Googleマップへの徒歩リンク、横はみ出しを確認します。
`test-results/` に画面のスクリーンショットと失敗時のトレースが出ます。

これは実スマホのGPS・Googleマップアプリ・本番のGemini/気象庁への接続の動作確認を代替しません。
実環境での確認は `docs/member-submission-tasks.md` のW-1〜W-8に沿って行ってください。
