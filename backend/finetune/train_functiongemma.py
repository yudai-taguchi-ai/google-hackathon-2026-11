"""Fine-tune FunctionGemma to classify evacuation requests as one tool call.

Run on a CUDA machine after accepting the model license and setting HF_TOKEN:
    python -m finetune.train_functiongemma --output finetune/output
The 400 held-out examples are used only for final evaluation, never for training.
"""

import argparse
import json
import os
import random
import re
from collections import Counter, defaultdict
from pathlib import Path


ROOT = Path(__file__).resolve().parent
MODEL_ID = "google/functiongemma-270m-it"
SYSTEM = "You are a model that can do function calling with the following functions. Choose exactly one function for the user's message."


def tool(name, description, properties=None, required=None):
    return {"type": "function", "function": {
        "name": name, "description": description,
        "parameters": {"type": "object", "properties": properties or {}, "required": required or []},
    }}


TOOLS = [
    tool("reselect", "Choose another evacuation site. Use slow when the traveler cannot walk fast; wheelchair for a wheelchair, stroller, or inability to use stairs.",
         {"mobility": {"type": "string", "enum": ["normal", "slow", "wheelchair"], "description": "Traveler mobility"}}, ["mobility"]),
    tool("ask_floor", "Ask which floor to evacuate to."),
    tool("road_blocked", "The route is blocked, flooded, or impassable."),
    tool("lost_family", "The traveler is separated from family or friends."),
    tool("injured", "The traveler is injured, sick, or cannot move."),
    tool("when_to_return", "Ask when it is safe to return or retrieve belongings."),
    tool("repeat", "Repeat or clarify the current guidance."),
    tool("other", "Unclear, unrelated, or unsupported request."),
]
NAMES = {t["function"]["name"] for t in TOOLS}
CALL = re.compile(r"<start_function_call>call:(\w+)\{(.*?)\}<end_function_call>", re.DOTALL)
ARG = re.compile(r"([^:,{}]+?):<escape>(.*?)<escape>", re.DOTALL)


def read_rows(path):
    rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    for i, row in enumerate(rows, 1):
        name, args = row.get("name"), row.get("args")
        if name not in NAMES or not isinstance(row.get("text"), str) or not row["text"].strip():
            raise ValueError(f"{path}:{i}: invalid tool name or text")
        expected = {"mobility"} if name == "reselect" else set()
        if not isinstance(args, dict) or set(args) != expected:
            raise ValueError(f"{path}:{i}: invalid arguments")
        if name == "reselect" and args["mobility"] not in {"normal", "slow", "wheelchair"}:
            raise ValueError(f"{path}:{i}: invalid mobility")
        if row.get("lang") not in {"en", "zh", "ko", "ja"}:
            raise ValueError(f"{path}:{i}: invalid language")
    return rows


def split_train_validation(rows, seed=42):
    """Hold out 10% of each language/tool/argument group for epoch selection."""
    groups = defaultdict(list)
    for row in rows:
        groups[(row["lang"], row["name"], json.dumps(row["args"], sort_keys=True))].append(row)
    rng = random.Random(seed)
    train, validation = [], []
    for group in groups.values():
        rng.shuffle(group)
        n = max(1, round(len(group) * 0.1))
        validation.extend(group[:n])
        train.extend(group[n:])
    rng.shuffle(train)
    return train, validation


def messages(row):
    return [
        {"role": "developer", "content": SYSTEM},
        {"role": "user", "content": row["text"]},
        {"role": "assistant", "tool_calls": [{"type": "function", "function": {
            "name": row["name"], "arguments": row["args"],
        }}]},
    ]


def format_row(tokenizer, row):
    turns = messages(row)
    prompt = tokenizer.apply_chat_template(turns[:-1], tools=TOOLS, tokenize=False, add_generation_prompt=True)
    full = tokenizer.apply_chat_template(turns, tools=TOOLS, tokenize=False, add_generation_prompt=False)
    if not full.startswith(prompt):
        raise ValueError("FunctionGemma chat template does not preserve the prompt prefix")
    completion = full[len(prompt):]
    if "<start_function_call>" not in completion:
        raise ValueError("FunctionGemma chat template did not produce a tool call")
    return {"prompt": prompt, "completion": completion}


def parse_call(text):
    match = CALL.search(text)
    if not match:
        return None, {}
    return match.group(1), dict((key.strip(), value) for key, value in ARG.findall(match.group(2)))


def score(model, tokenizer, rows):
    import torch

    model.eval()
    results = []
    for row in rows:
        prompt = format_row(tokenizer, row)["prompt"]
        batch = tokenizer(prompt, return_tensors="pt").to(model.device)
        with torch.inference_mode():
            output = model.generate(**batch, max_new_tokens=64, do_sample=False,
                                    pad_token_id=tokenizer.eos_token_id)
        generated = tokenizer.decode(output[0, batch["input_ids"].shape[1]:], skip_special_tokens=False)
        name, args = parse_call(generated)
        ok = name == row["name"] and args == row["args"]
        results.append({"name": name, "args": args, "lang": row["lang"], "text": row["text"],
                        "expected": row["name"], "expected_args": row["args"], "ok": ok})
    return results


def summarize(results):
    languages = {}
    for lang in ("en", "zh", "ko", "ja"):
        subset = [r for r in results if r["lang"] == lang]
        languages[lang] = {"correct": sum(r["ok"] for r in subset), "total": len(subset)}
    return {"correct": sum(r["ok"] for r in results), "total": len(results),
            "by_language": languages,
            "errors_by_expected_tool": dict(Counter(r["expected"] for r in results if not r["ok"]))}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "output")
    parser.add_argument("--epochs", type=int, default=3)
    parser.add_argument("--skip-baseline", action="store_true")
    args = parser.parse_args()
    import torch
    if not torch.cuda.is_available():
        raise SystemExit("CUDA GPU が必要です。Google Colab の GPU ランタイムで実行してください。")
    if not os.getenv("HF_TOKEN"):
        raise SystemExit("HF_TOKEN が必要です。FunctionGemma の利用条件に同意してから設定してください。")
    from datasets import Dataset
    from transformers import AutoModelForCausalLM, AutoTokenizer
    from trl import SFTConfig, SFTTrainer

    train_rows = read_rows(ROOT / "data" / "train.jsonl")
    test_rows = read_rows(ROOT / "data" / "test.jsonl")
    train, validation = split_train_validation(train_rows)
    if {r["text"] for r in train_rows} & {r["text"] for r in test_rows}:
        raise ValueError("Exact utterance overlap between train and test")
    args.output.mkdir(parents=True, exist_ok=True)
    tokenizer = AutoTokenizer.from_pretrained(MODEL_ID, token=os.environ["HF_TOKEN"])
    # Keep trainable weights in FP32. Trainer's fp16=True uses autocast and a
    # GradScaler; pre-casting the weights to FP16 makes unscale_ fail on T4.
    model = AutoModelForCausalLM.from_pretrained(MODEL_ID, token=os.environ["HF_TOKEN"],
                                                 dtype=torch.float32, attn_implementation="eager").to("cuda")
    baseline = None if args.skip_baseline else score(model, tokenizer, test_rows)
    train_ds = Dataset.from_list([format_row(tokenizer, row) for row in train])
    validation_ds = Dataset.from_list([format_row(tokenizer, row) for row in validation])
    max_length = min(1024, max(len(tokenizer(x["prompt"] + x["completion"])["input_ids"]) for x in train_ds) + 16)
    config = SFTConfig(output_dir=str(args.output / "checkpoints"), num_train_epochs=args.epochs,
                       per_device_train_batch_size=4, gradient_accumulation_steps=4,
                       per_device_eval_batch_size=4, eval_strategy="epoch", save_strategy="epoch",
                       load_best_model_at_end=True, metric_for_best_model="eval_loss", greater_is_better=False,
                       learning_rate=5e-5, lr_scheduler_type="cosine", max_length=max_length,
                       completion_only_loss=True, packing=False, fp16=True, bf16=False,
                       gradient_checkpointing=False, optim="adamw_torch", report_to="none",
                       save_total_limit=2, logging_steps=20)
    trainer = SFTTrainer(model=model, args=config, train_dataset=train_ds,
                         eval_dataset=validation_ds, processing_class=tokenizer)
    trainer.train()
    final_dir = args.output / "model"
    trainer.save_model(str(final_dir))
    tokenizer.save_pretrained(str(final_dir))
    tuned = score(trainer.model, tokenizer, test_rows)
    report = {"model": MODEL_ID, "train": len(train), "validation": len(validation),
              "test": len(test_rows), "epochs": args.epochs,
              "baseline": summarize(baseline) if baseline is not None else None,
              "fine_tuned": summarize(tuned), "best_checkpoint": trainer.state.best_model_checkpoint}
    (args.output / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    (args.output / "predictions.jsonl").write_text(
        "".join(json.dumps(r, ensure_ascii=False) + "\n" for r in tuned), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
