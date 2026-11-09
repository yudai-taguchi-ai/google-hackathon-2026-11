"""気象庁の電文の読み方のテスト（python -X utf8 -m tests.test_alerts）。提出後は直せないので、警報あり・なし・解除・取消・訓練を確かめる。"""
from datetime import datetime, timezone
from pathlib import Path

from app.alerts import live_status

SAMPLES = Path(__file__).resolve().parent.parent / "data" / "samples"
NOW = datetime(2026, 11, 9, 12, 0, tzinfo=timezone.utc)
TRAINING = (SAMPLES / "nankai_training_VTSE41.xml").read_text(encoding="utf-8")
REAL_CHIBA = (SAMPLES / "real_20261106_VTSE41_chiba_minor.xml").read_text(encoding="utf-8")


def as_real(xml: str) -> str:
    return xml.replace("<Status>訓練</Status>", "<Status>通常</Status>")


def test_all() -> None:
    assert live_status(None, NOW)["active"] is False
    assert live_status(TRAINING, NOW)["reason"] == "not_real_telegram"
    r = live_status(REAL_CHIBA, NOW)
    assert r["active"] is False and r["reason"] == "no_warning_for_area", r
    real = live_status(as_real(TRAINING), NOW)
    assert real["active"] is True and "津波警報" in real["alert"].kind, real
    cancelled = as_real(TRAINING).replace("<InfoType>発表</InfoType>", "<InfoType>取消</InfoType>")
    assert live_status(cancelled, NOW)["reason"] == "cancelled"
    lifted = as_real(TRAINING).replace(real["alert"].kind, "警報解除")
    assert live_status(lifted, NOW)["active"] is False
    print("ok: 電文なし・訓練・他の地域だけ・大阪に警報・取消・解除")


if __name__ == "__main__":
    test_all()
