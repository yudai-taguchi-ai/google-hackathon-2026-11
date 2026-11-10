"""Evaluate a converted model with the training tools and the shared scorer."""
import argparse
import json
from pathlib import Path

import litert_lm
from transformers import AutoTokenizer

from finetune.train_functiongemma import format_row, parse_call
from finetune.score import score


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("model")
    parser.add_argument("--tokenizer", required=True)
    parser.add_argument("--limit", type=int, default=400)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not 1 <= args.limit <= 400:
        parser.error("limit must be between 1 and 400")
    rows = [json.loads(line) for line in (Path(__file__).parent / "data/test.jsonl").read_text(encoding="utf-8").splitlines()]
    rows = rows[:args.limit]
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
