"""ローカルの作業用スクリプトから Vertex AI を呼ぶための認証。gcloud のトークンを 10 分ごとに取り直す（長い処理の途中で切れるため）。"""
import os
import shutil
import subprocess
import time

from google import genai
from google.oauth2.credentials import Credentials

REFRESH_SEC = 600
_cache: dict = {"at": 0.0, "client": None}


def _token() -> str:
    gcloud = shutil.which("gcloud") or shutil.which("gcloud.cmd")
    return subprocess.run([gcloud, "auth", "print-access-token"], capture_output=True, text=True, check=True).stdout.strip()


def client(force: bool = False) -> genai.Client:
    if force or _cache["client"] is None or time.time() - _cache["at"] > REFRESH_SEC:
        _cache["client"] = genai.Client(
            vertexai=True,
            project=os.environ["GOOGLE_CLOUD_PROJECT"],
            location="global",
            credentials=Credentials(token=_token()),
        )
        _cache["at"] = time.time()
    return _cache["client"]


def call(fn, retries: int = 3):
    """fn(client) を呼ぶ。401 なら取り直して、その他の失敗は少し待ってやり直す。"""
    for i in range(retries):
        try:
            return fn(client(force=i > 0))
        except Exception as e:
            if i == retries - 1:
                raise
            time.sleep(5 if "401" in str(e) else 15)


def retry(fn, retries: int = 4):
    """通信の失敗（接続が切れた など）の時に、少し待ってやり直す。"""
    for i in range(retries):
        try:
            return fn()
        except Exception:
            if i == retries - 1:
                raise
            time.sleep(10 * (i + 1))
