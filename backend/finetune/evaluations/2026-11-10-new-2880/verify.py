"""Re-score the stored predictions against the added test rows; no models required."""
import hashlib
import json
import sys
from collections import defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
BACKEND = HERE.parents[2]
sys.path.insert(0, str(BACKEND))
from finetune.score import keyword_baseline, report, score

EXPECTED_DATA_SHA256 = "2296d2367b078ae1ab668fdf1ccf06a0846743e5883db79caf0a724c7c7789f0"
PREFIXES = {
    "en": ("Excuse me, ", "Please, ", "Listen, ", "I need to know: "),
    "zh": ("请问，", "不好意思，", "听我说，", "麻烦你，"),
    "ko": ("저기요, ", "실례합니다, ", "잠깐만요, ", "부탁인데요, "),
}


def read_rows(path):
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def family(row):
    text = row["text"]
    for prefix in PREFIXES[row["lang"]]:
        if text.startswith(prefix):
            text = text[len(prefix):]
            break
    return (row["lang"], row["name"], json.dumps(row["args"], sort_keys=True), text.rstrip(".!?。！？… "))


def main():
    data = BACKEND / "finetune/data/test.jsonl"
    assert hashlib.sha256(data.read_bytes()).hexdigest() == EXPECTED_DATA_SHA256, "The test file has changed; check the dataset revision before comparing"
    rows = read_rows(data)[400:]
    assert len(rows) == 2880
    predictions = {name: read_rows(HERE / f"{name}.jsonl") for name in ("rule", "bert", "functiongemma")}
    expected_rule = [dict(zip(("name", "args"), keyword_baseline(row["text"]))) for row in rows]
    assert predictions["rule"] == expected_rule, "Rule predictions do not match the current scorer"
    results = {name: score(rows, pred) for name, pred in predictions.items()}
    for name, result in results.items():
        print(report(name, result, None if name == "rule" else results["rule"]))
        grouped = defaultdict(list)
        for row, pred in zip(rows, predictions[name]):
            grouped[family(row)].append(row["name"] == pred["name"] and row["args"] == pred["args"])
        print(f"normalized groups: {len(grouped)}; all correct: {sum(all(v) for v in grouped.values())}; "
              f"all wrong: {sum(not any(v) for v in grouped.values())}; mixed: {sum(any(v) and not all(v) for v in grouped.values())}")


if __name__ == "__main__":
    main()
