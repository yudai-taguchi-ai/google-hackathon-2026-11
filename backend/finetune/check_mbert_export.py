"""Check the saved mBERT and ONNX predictions against the original test output."""
import argparse
import json
from pathlib import Path

import numpy as np
import onnxruntime as ort
import torch
from transformers import AutoModelForSequenceClassification, AutoTokenizer

from finetune.score import score
from finetune.train_functiongemma import read_rows
from finetune.train_mbert import LABELS, prediction


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("model", type=Path)
    parser.add_argument("onnx", type=Path)
    parser.add_argument("previous", type=Path)
    args = parser.parse_args()
    rows = read_rows(Path(__file__).parent / "data/test.jsonl")
    previous = [json.loads(line) for line in args.previous.read_text(encoding="utf-8").splitlines()]
    if len(previous) != len(rows):
        raise ValueError("Prediction and test counts differ")
    tokenizer = AutoTokenizer.from_pretrained(args.model, local_files_only=True)
    model = AutoModelForSequenceClassification.from_pretrained(args.model, local_files_only=True).eval()
    session = ort.InferenceSession(str(args.onnx), providers=["CPUExecutionProvider"])
    pytorch_predictions, onnx_predictions = [], []
    max_difference = 0.0
    for start in range(0, len(rows), 16):
        batch = tokenizer([row["text"] for row in rows[start:start + 16]],
                          return_tensors="pt", padding=True, truncation=True, max_length=128)
        with torch.inference_mode():
            expected = model(**batch).logits.numpy()
        feeds = {item.name: batch[item.name].numpy() for item in session.get_inputs()}
        actual = session.run(None, feeds)[0]
        max_difference = max(max_difference, float(np.max(np.abs(expected - actual))))
        pytorch_predictions.extend(prediction(LABELS[index]) for index in np.argmax(expected, axis=-1))
        onnx_predictions.extend(prediction(LABELS[index]) for index in np.argmax(actual, axis=-1))
    print(json.dumps({"previous_vs_reloaded_mismatches": sum(a != b for a, b in zip(previous, pytorch_predictions)),
                      "pytorch_vs_onnx_mismatches": sum(a != b for a, b in zip(pytorch_predictions, onnx_predictions)),
                      "maximum_logit_difference": max_difference,
                      "reloaded_score": score(rows, pytorch_predictions),
                      "onnx_score": score(rows, onnx_predictions)}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
