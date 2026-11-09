"""答えの文（翻訳）と音声を先に作って data/answers/ に置く。サーバーは起動のたびに Gemini を呼ばずに済む（提出後は直せないため）。

使い方（backend/ で）: GOOGLE_CLOUD_PROJECT=... ./.venv/Scripts/python -m scripts.prepare_answers en ja
"""
import json
import sys

from app import pack
import gcp_auth

pack._client = lambda: gcp_auth.client()


def main(langs: list[str]) -> None:
    for lang in langs:
        pack.answers.cache_clear()
        texts = gcp_auth.retry(lambda: pack.answers(lang)) if lang != "ja" else dict(pack.ANSWERS_JA)
        (pack.ANSWERS_DIR / "audio" / lang).mkdir(parents=True, exist_ok=True)
        (pack.ANSWERS_DIR / f"{lang}.json").write_text(json.dumps(texts, ensure_ascii=False, indent=1), encoding="utf-8")
        for answer_id, text in texts.items():
            wav = gcp_auth.retry(lambda t=text: pack.synthesize(t))
            (pack.ANSWERS_DIR / "audio" / lang / f"{answer_id}.wav").write_bytes(wav)
            print(lang, answer_id, len(wav))


if __name__ == "__main__":
    main(sys.argv[1:] or ["en", "ja"])
