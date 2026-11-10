# backend（一連の流れの試作）

警報 → 避難先を選ぶ → Gemini が母語で説明、を1回の API 呼び出しで返す。今は気象庁の**訓練電文**（南海トラフ想定・大阪府に大津波警報・残り 17 分）で動く。

```
気象庁 XML（訓練電文） ─→ alerts.py：大阪府（510）の到達予想時刻 → 残り何分か
現在地 ─────────────────→ planner.py：公式の津波避難場所から、間に合う所をプログラムで選ぶ
                                       （区域の外へ／建物の上の階へ／区域の外にいるならそのまま内陸へ）
                          explain.py：Gemini が、選ばれた候補だけを使って母語で説明
                                       （候補に無い id を使ったら捨てて、決まった文を出す）
```

## 動かし方（Windows・Git Bash）
```bash
cd backend
python -m venv .venv
./.venv/Scripts/python -m pip install -r requirements.txt
./.venv/Scripts/python scripts/prepare_data.py        # 避難場所と浸水想定を取得（初回だけ・数分）
cp .env.example .env                                  # .env に GEMINI_API_KEY を入れる（コミットしない）
set -a; source .env; set +a
./.venv/Scripts/python -m uvicorn app.main:app --port 8765
```
ブラウザで http://127.0.0.1:8765 を開き、「海遊館」「USJ」「堺・大浜公園」を押す。

## ファイル
| ファイル | 役割 |
|---|---|
| `scripts/prepare_data.py` | 国土地理院の津波避難場所（大阪市・堺市）に、浸水想定の深さを付けて `data/shelters_osaka.json` を作る |
| `app/alerts.py` | 気象庁の津波警報の電文から、大阪府の到達予想時刻などを取り出す |
| `app/walking.py` | 歩く時間。`GOOGLE_MAPS_API_KEY` があれば Routes API、無ければ直線距離×1.3（回り道）。どちらも距離を歩く速さで割る：通常 0.62m/秒（東日本大震災の平均）・歩くのが難しい時 0.5m/秒（docs/decisions.md） |
| `app/planner.py` | 避難先を選ぶ（Gemini は使わない） |
| `app/explain.py` | Gemini 3.8 Flash が説明文を作る |
| `app/main.py` | API（`POST /api/plan`）と画面 |
| `app/static/index.html` | 試作の画面 |
| `data/samples/nankai_training_VTSE41.xml` | 気象庁のサンプル電文集にある訓練電文 |

## 今わかっている限界
- 歩く時間は、Maps のキーが無いと直線距離からの目安
- 屋外（公園など）か建物かは、名前から推測している（国のデータに列が無い）
- 「何階以上」は浸水の深さからの目安（津波避難ビルの指定基準の 3 階以上を下限にし、深い所は 1 階 3m として浸水の深さから上げる）
- 浸水想定は 2023 年版。USJ は区画の外と判定されたが、座標はおおよそで、理由（盛土・防潮堤など）は未確認

## Cloud Run（本番）
- URL: https://tsunami-navi-129745156402.asia-northeast1.run.app （11/14 まで止めない）
- プロジェクト: `project-288752b0-6771-48a6-bf3`（OIF のアカウント・GCP 無料トライアル）／地域: 東京（asia-northeast1）
- Gemini は Vertex AI 経由（`GOOGLE_CLOUD_PROJECT`・`GOOGLE_CLOUD_LOCATION=global`）。Routes API のキーは Secret Manager の `routes-api-key`
- 載せ直し（**元の作業フォルダ `google-hackathon-2026-11/backend` で**。GitHub から作り直したフォルダには `data/raw/` が無い）:
```bash
git switch main && git pull
gcloud run deploy tsunami-navi --source . --region asia-northeast1 --quiet
curl -s -X POST https://tsunami-navi-129745156402.asia-northeast1.run.app/api/plan -H 'Content-Type: application/json' -d '{"lat":34.5813,"lon":135.4673,"explain":false}' | head -c 200
```
- `.gcloudignore` で `data/raw/`（浸水想定）を含めて送る（Git には入れていない）。無い時は Dockerfile の `test -f` で組み立てが止まる（2026-11-10 に無いまま載せて計算が全部失敗したため）
- 最後の curl で避難先の名前が返れば成功。`Internal Server Error` なら載せ直す
- 常に 1 台起こしておく（`--min-instances=1`）。提出後〜11/14 に最初の人が約 10 秒待たないように
- 予算アラート: 約 45,000 円（≒$300）の 20/50/80/100% で通知（自動では止まらない）

## API（2026-11-09）
| API | 中身 |
|---|---|
| `GET /api/alert` | 今の警報（訓練電文）。`minutes_left`・`arrival_time`・`replaying` |
| `POST /api/alert/replay` | 訓練電文を「今」発表されたものとして流し直す（残り時間は電文と同じ 17 分から減っていく）。Cloud Run は 1 台で動かしている（状態を共有するため） |
| `POST /api/plan` | `{lat, lon, lang, slow}` → 避難先・`route`（浸水想定の浅い道・`encoded_polyline`）・`waypoints`（Navigation SDK に渡す経由地・最大8点）・Gemini の説明（`explain:false` なら省いて約 0.4 秒で返す）・`plan_id` |
| `GET /api/explain?plan_id=…` | 先に返した避難先の Gemini の説明（Web は避難先を先に出し、説明を後から出す。約 5 秒） |
| `GET /api/pack?area=osaka-bay&lang=en&expires_at=YYYY-MM-DD` | オフライン用のパック（避難先 2,335 件・簡略化した浸水想定・答えの文・到達時刻の既定値 26 分・歩く速さ）。gzip で約 1MB |
| `GET /api/pack/audio?lang=en&id=reselect_slow` | 答えの音声（WAV）。Gemini の音声生成で作る |

- 答えの元の文（日本語）は `app/pack.py` の `ANSWERS_JA`。他の言語は Gemini で訳す
- 浸水想定のパック用データは `scripts/prepare_pack.py` で作る（`data/inundation_pack.geojson`）
