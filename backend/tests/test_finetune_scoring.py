from pathlib import Path
from tempfile import TemporaryDirectory

from finetune.evaluate_litertlm import read_test_rows
from finetune.score import score


def test_critical_mismatches_and_legacy_metric():
    gold = [{"lang": "en", "text": "injured", "name": "injured", "args": {}}]
    wrong_action = score(gold, [{"name": "ask_floor", "args": {}}])
    assert wrong_action["critical_rate"] == 0  # Existing definition remains unchanged.
    assert wrong_action["critical_mismatch_rate"] == 1
    unparsed = score(gold, [{"name": None, "args": {}}])
    assert unparsed["critical_mismatch_rate"] == 1
    correct = score(gold, [{"name": "injured", "args": {}}])
    assert correct["critical_mismatch_rate"] == 0


def test_evaluate_all_rows_or_limit():
    with TemporaryDirectory() as tmp:
        path = Path(tmp) / "test.jsonl"
        path.write_text('{"n":1}\n{"n":2}\n', encoding="utf-8")
        assert len(read_test_rows(path)) == 2
        assert len(read_test_rows(path, 1)) == 1
        for limit in (0, 3):
            try:
                read_test_rows(path, limit)
            except ValueError:
                pass
            else:
                raise AssertionError("Out-of-range limits must fail")


if __name__ == "__main__":
    test_critical_mismatches_and_legacy_metric()
    test_evaluate_all_rows_or_limit()
    print("ok: evaluation coverage and critical mismatches")
