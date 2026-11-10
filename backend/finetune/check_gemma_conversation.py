"""Investigate LiteRT-LM's conversation template against the training prompt."""
import argparse
import json
from pathlib import Path

import litert_lm
from transformers import AutoTokenizer

from finetune.train_functiongemma import format_row


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("model")
    parser.add_argument("tokenizer", type=Path)
    parser.add_argument("text")
    args = parser.parse_args()
    tokenizer = AutoTokenizer.from_pretrained(args.tokenizer, local_files_only=True)
    prompt = format_row(tokenizer, {"text": args.text, "name": "other", "args": {}})["prompt"]
    template = "{{ messages[-1]['content'][0]['text'] }}"
    with litert_lm.Engine(args.model, backend=litert_lm.Backend.CPU(), max_num_tokens=1024) as engine:
        with engine.create_conversation(automatic_tool_calling=False, chat_template=template,
                                        sampler_config=litert_lm.SamplerConfig(top_k=1),
                                        max_output_tokens=64) as conversation:
            response = conversation.send_message(prompt)
            print(json.dumps(response.to_json(), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
