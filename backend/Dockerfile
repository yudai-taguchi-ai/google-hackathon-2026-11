FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app ./app
COPY data ./data
# 浸水想定（Git に入れていない）が無いまま載せると、避難先の計算が全部失敗するので、ここで止める
RUN test -f data/raw/inundation_osaka.geojson
CMD exec uvicorn app.main:app --host 0.0.0.0 --port ${PORT:-8080}
