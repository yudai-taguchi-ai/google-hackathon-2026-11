"""Train multilingual BERT on the same split and score the held-out test set."""
import argparse
import json
from pathlib import Path

import numpy as np
import torch
from datasets import Dataset
from transformers import (AutoModelForSequenceClassification, AutoTokenizer,
                          DataCollatorWithPadding, Trainer, TrainingArguments, set_seed)

from finetune.score import keyword_baseline, report, score
from finetune.train_functiongemma import read_rows, split_train_validation

ROOT = Path(__file__).parent
LABELS = ["reselect", "reselect_slow", "reselect_wheelchair", "ask_floor",
          "road_blocked", "lost_family", "injured", "when_to_return", "repeat", "other"]


def row_label(row):
    if row["name"] == "reselect" and row["args"]["mobility"] != "normal":
        return "reselect_" + row["args"]["mobility"]
    return row["name"]


def prediction(label):
    if label in ("reselect_slow", "reselect_wheelchair"):
        return {"name": "reselect", "args": {"mobility": label.removeprefix("reselect_")}}
    if label == "reselect":
        return {"name": label, "args": {"mobility": "normal"}}
    return {"name": label, "args": {}}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", default="google-bert/bert-base-multilingual-cased")
    parser.add_argument("--output", type=Path, default=ROOT / "output/mbert")
    parser.add_argument("--epochs", type=int, default=3)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    set_seed(42)
    train, validation = split_train_validation(read_rows(ROOT / "data/train.jsonl"))
    test = read_rows(ROOT / "data/test.jsonl")
    tokenizer = AutoTokenizer.from_pretrained(args.model)
    def prepare(rows):
        dataset = Dataset.from_list([{"text": row["text"], "label": LABELS.index(row_label(row))} for row in rows])
        return dataset.map(lambda batch: tokenizer(batch["text"], truncation=True, max_length=128), batched=True, remove_columns=["text"])
    model = AutoModelForSequenceClassification.from_pretrained(
        args.model, num_labels=len(LABELS), id2label=dict(enumerate(LABELS)),
        label2id={label: i for i, label in enumerate(LABELS)})
    training_args = TrainingArguments(
        output_dir=str(args.output / "checkpoints"), num_train_epochs=args.epochs,
        per_device_train_batch_size=8, per_device_eval_batch_size=16,
        learning_rate=2e-5, weight_decay=0.01, eval_strategy="epoch", save_strategy="epoch",
        load_best_model_at_end=True, metric_for_best_model="eval_loss", save_total_limit=1,
        report_to="none", seed=42, use_cpu=not torch.cuda.is_available())
    trainer = Trainer(model=model, args=training_args, train_dataset=prepare(train),
                      eval_dataset=prepare(validation), data_collator=DataCollatorWithPadding(tokenizer))
    trainer.train()
    trainer.save_model(str(args.output / "model"))
    tokenizer.save_pretrained(str(args.output / "model"))
    predictions = [prediction(LABELS[index]) for index in np.argmax(trainer.predict(prepare(test)).predictions, axis=-1)]
    (args.output / "predictions.jsonl").write_text("".join(json.dumps(p, ensure_ascii=False) + "\n" for p in predictions), encoding="utf-8")
    baseline = score(test, [dict(zip(("name", "args"), keyword_baseline(row["text"]))) for row in test])
    result = score(test, predictions)
    (args.output / "report.md").write_text(report("キーワード方式", baseline, None) + "\n" + report("mBERT", result, baseline), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
