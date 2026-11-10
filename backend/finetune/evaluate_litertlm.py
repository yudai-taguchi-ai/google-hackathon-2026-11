"""Evaluate a converted model with the training tools and the shared scorer."""
import argparse
import json
from pathlib import Path

from finetune.score import score


def read_test_rows(path: Path, limit: int | None = None) -> list[dict]:
    rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    if not rows:
        raise ValueError("Test dataset is empty")
    if limit is not None and not 1 <= limit <= len(rows):
        raise ValueError(f"limit must be between 1 and {len(rows)}")
    return rows if limit is None else rows[:limit]


def main():
    import litert_lm
    from transformers import AutoTokenizer
    from finetune.train_functiongemma import format_row, parse_call

    parser = argparse.ArgumentParser()
    parser.add_argument("model")
    parser.add_argument("--tokenizer", required=True)
    parser.add_argument("--limit", type=int, default=None, help="Evaluate only the first N rows (default: all)")
    parser.add_argument("--test-data", type=Path, default=Path(__file__).parent / "data/test.jsonl")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        rows = read_test_rows(args.test_data, args.limit)
    except ValueError as error:
        parser.error(str(error))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    predictions = []
    tokenizer = AutoTokenizer.from_pretrained(args.tokenizer, local_files_only=True)
    with litert_lm.Engine(args.model, backend=litert_lm.Backend.CPU(), max_num_tokens=1024) as engine:
        with args.output.open("x", encoding="utf-8") as out:
            for index, row in enumerate(rows):
                prompt = format_row(tokenizer, row)["prompt"]
                with engine.create_session(
                    apply_prompt_template=False,
                    sampler_config=litert_lm.SamplerConfig(top_k=1),
                    max_output_tokens=64,
                ) as session:
                    session.run_prefill([prompt])
                    response = session.run_decode().texts[0]
                name, arguments = parse_call(response)
                prediction = {"name": name, "args": arguments, "raw": response}
                predictions.append(prediction)
                out.write(json.dumps(prediction, ensure_ascii=False) + "\n")
                out.flush()
                print(f"{index + 1}/{len(rows)}", flush=True)
    print(json.dumps(score(rows, predictions), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
