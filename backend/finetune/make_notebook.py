"""Colab で FunctionGemma を追加学習するノートブックを作る（finetune/functiongemma_tsunami.ipynb）。
元にしたもの: google-gemini/gemma-cookbook の FunctionGemma Mobile Actions のノートブック（Apache 2.0）。
"""
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent

CELLS = [
    ("md", """# FunctionGemma 270M を「津波避難の頼みごと」に追加学習する

旅行者が慌てて話す一言（英語・中国語・韓国語・日本語）を、アプリの頼みごと（関数）に聞き分けられるようにする。
学習データは Gemini で作ったもの（`train.jsonl`・`test.jsonl`。テストは学習と別の言い方）。

**始める前に（1回だけ）**
1. 上のメニュー「ランタイム → ランタイムのタイプを変更」で **T4 GPU** を選ぶ
2. Hugging Face で https://huggingface.co/google/functiongemma-270m-it を開き、利用条件に同意する
3. Hugging Face の「Settings → Access Tokens」で Read のトークンを作る
4. Colab の左の鍵のアイコン（シークレット）に、名前 `HF_TOKEN` でトークンを入れ、「ノートブックからのアクセス」をオンにする

そのあと「ランタイム → すべてのセルを実行」。途中でファイルの選択画面が出たら `train.jsonl` と `test.jsonl` を選ぶ。

元にしたもの: google-gemini/gemma-cookbook の FunctionGemma Mobile Actions のノートブック（Apache 2.0）"""),
    ("code", """%pip install torch
%pip install -U transformers==4.57.1 trl==0.25.1 datasets==4.4.1"""),
    ("code", """from google.colab import userdata
from huggingface_hub import login
login(userdata.get('HF_TOKEN'))"""),
    ("md", "## 1. 学習データを読み込む"),
    ("code", """from google.colab import files
import json
uploaded = files.upload()  # train.jsonl と test.jsonl を選ぶ
train_rows = [json.loads(l) for l in open('train.jsonl', encoding='utf-8')]
test_rows = [json.loads(l) for l in open('test.jsonl', encoding='utf-8')]
print(len(train_rows), len(test_rows))
print(train_rows[0])"""),
    ("md", "## 2. 頼みごと（関数）の一覧と、学習の形にする\nアプリ（android/）の頼みごとと同じ一覧。引数は reselect の mobility だけ。"),
    ("code", """def fn(name, description, props=None, required=None):
    return {"type": "function", "function": {"name": name, "description": description,
            "parameters": {"type": "object", "properties": props or {}, "required": required or []}}}

TOOLS = [
    fn("reselect", "Choose another evacuation site. mobility: normal (just want another place), slow (cannot walk fast: child, elderly, injury, heavy luggage), wheelchair (wheelchair, stroller, cannot use stairs).",
       {"mobility": {"type": "string", "enum": ["normal", "slow", "wheelchair"], "description": "How the user can move."}}, ["mobility"]),
    fn("ask_floor", "The user asks how high / which floor to go up to."),
    fn("road_blocked", "The road is blocked, flooded or impassable."),
    fn("lost_family", "The user lost family members or friends."),
    fn("injured", "The user is injured, bleeding, sick or cannot move."),
    fn("when_to_return", "The user asks when they can go back (hotel, belongings) or how long to stay."),
    fn("repeat", "The user did not hear or understand and wants the guidance repeated."),
    fn("other", "Anything else, unrelated, or unclear."),
]
SYSTEM = "You are a tsunami evacuation assistant for travelers. Choose exactly one function for the user's message."

from transformers import AutoTokenizer
gemma_model = "google/functiongemma-270m-it"
tokenizer = AutoTokenizer.from_pretrained(gemma_model)

def to_messages(r):
    return [
        {"role": "developer", "content": SYSTEM},
        {"role": "user", "content": r["text"]},
        {"role": "assistant", "tool_calls": [{"type": "function", "function": {"name": r["name"], "arguments": r["args"]}}]},
    ]

def to_prompt_completion(r):
    msgs = to_messages(r)
    full = tokenizer.apply_chat_template(msgs, tools=TOOLS, tokenize=False, add_generation_prompt=False)
    prompt = tokenizer.apply_chat_template(msgs[:-1], tools=TOOLS, tokenize=False, add_generation_prompt=True)
    return {"prompt": prompt, "completion": full[len(prompt):], "lang": r["lang"], "name": r["name"], "args": json.dumps(r["args"], sort_keys=True)}

from datasets import Dataset
train_ds = Dataset.from_list([to_prompt_completion(r) for r in train_rows])
test_ds = Dataset.from_list([to_prompt_completion(r) for r in test_rows])
longest = max(len(tokenizer.tokenize(x["prompt"] + x["completion"])) for x in train_ds)
max_token_count = longest + 64
print("例:", train_ds[0]["completion"])
print("最長のトークン数:", longest)"""),
    ("md", "## 3. 正答率を測る関数（関数の名前と引数の両方が合えば正解）"),
    ("code", """import re, time
import pandas as pd

def parse_call(text):
    m = re.search(r"<start_function_call>call:(\\w+)\\{(.*?)\\}<end_function_call>", text, re.DOTALL)
    if not m:
        return None, {}
    args = {k.strip(): v for k, v in re.findall(r"([^:,{}]+?):<escape>(.*?)<escape>", m.group(2), re.DOTALL)}
    return m.group(1), args

def evaluate(model, tok, ds, label):
    from transformers import pipeline
    pipe = pipeline("text-generation", model=model, tokenizer=tok, device_map="auto")
    rows = []
    for x in ds:
        t0 = time.time()
        out = pipe(x["prompt"], max_new_tokens=48, do_sample=False, skip_special_tokens=False)[0]["generated_text"][len(x["prompt"]):]
        name, args = parse_call(out)
        rows.append({"lang": x["lang"], "name": name, "args": args, "ok": name == x["name"] and json.dumps(args, sort_keys=True) == x["args"],
                     "expected": f'{x["name"]} {x["args"]}', "got": f"{name} {args}", "sec": time.time() - t0})
    df = pd.DataFrame(rows)
    print(label, "全体の正答率:", round(df.ok.mean() * 100, 1), "%")
    return df"""),
    ("md", "## 4. 学習の前の正答率"),
    ("code", """from transformers import AutoModelForCausalLM
import torch
base_model = AutoModelForCausalLM.from_pretrained(gemma_model, device_map="auto", attn_implementation="eager", dtype=torch.float32)
base_df = evaluate(base_model, tokenizer, test_ds, "学習前")"""),
    ("md", "## 5. 追加学習（T4 は bf16 が使えないので float32 で学習する。数十分）"),
    ("code", """from trl import SFTConfig, SFTTrainer
output_dir = "/content/functiongemma-tsunami"
args = SFTConfig(
    output_dir=output_dir,
    num_train_epochs=3,
    per_device_train_batch_size=8,
    gradient_accumulation_steps=4,
    logging_steps=10,
    eval_strategy="epoch",
    save_strategy="epoch",
    learning_rate=1e-5,
    lr_scheduler_type="cosine",
    max_length=max_token_count,
    gradient_checkpointing=True,
    packing=False,
    optim="adamw_torch_fused",
    bf16=False,
    completion_only_loss=True,
    report_to="none",
)
model = AutoModelForCausalLM.from_pretrained(gemma_model, device_map="auto", attn_implementation="eager", dtype=torch.float32)
model.config.pad_token_id = tokenizer.pad_token_id
trainer = SFTTrainer(model=model, args=args,
                     train_dataset=train_ds.remove_columns(["lang", "name", "args"]),
                     eval_dataset=test_ds.remove_columns(["lang", "name", "args"]))
trainer.train()
trainer.save_model(output_dir)
tokenizer.save_pretrained(output_dir)"""),
    ("md", "## 6. 学習の後の正答率と、言語ごとの表"),
    ("code", """trained = AutoModelForCausalLM.from_pretrained(output_dir, device_map="auto")
trained_df = evaluate(trained, tokenizer, test_ds, "学習後")

table = pd.DataFrame({
    "学習前": base_df.groupby("lang").ok.mean() * 100,
    "学習後": trained_df.groupby("lang").ok.mean() * 100,
}).round(1)
table.loc["全体"] = [round(base_df.ok.mean() * 100, 1), round(trained_df.ok.mean() * 100, 1)]
print(table.to_markdown())
print("1 件あたりの時間（Colab の GPU）:", round(trained_df.sec.mean(), 2), "秒")
print("\\n間違えた例（学習後）")
print(trained_df[~trained_df.ok].head(20).to_string())
table.to_csv("accuracy_by_lang.csv")
# backend/finetune/score.py で採点するための予測（test.jsonl と同じ順）
with open("predictions.jsonl", "w", encoding="utf-8") as f:
    for r in trained_df.itertuples():
        f.write(json.dumps({"name": r.name, "args": r.args}, ensure_ascii=False) + "
")
trained_df.to_json("trained_eval.json", force_ascii=False)"""),
    ("md", "## 7. スマホで動く形（.litertlm）に変換して Google ドライブに保存"),
    ("code", """!pip uninstall -y tensorflow
!pip install ai-edge-torch-nightly --force-reinstall
!pip install ai-edge-litert-nightly"""),
    ("code", """import os
from ai_edge_torch.generative.examples.gemma3 import gemma3
from ai_edge_torch.generative.utilities import converter
from ai_edge_torch.generative.utilities.export_config import ExportConfig
from ai_edge_torch.generative.layers import kv_cache

llm_metadata = r'''start_token: {
    token_ids: {
        ids: [ 2 ]
    }
}
stop_tokens: {
    token_str: "<end_of_turn>"
}
stop_tokens: {
    token_str: "<start_function_response>"
}
llm_model_type: {
    function_gemma: {}
}
'''
out_dir = "/content/litertlm"
os.makedirs(out_dir, exist_ok=True)
meta = os.path.join(out_dir, "base_llm_metadata.textproto")
open(meta, "w").write(llm_metadata)

pytorch_model = gemma3.build_model_270m(output_dir)
export_config = ExportConfig()
export_config.kvcache_layout = kv_cache.KV_LAYOUT_TRANSPOSED
export_config.mask_as_input = True
converter.convert_to_litert(
    pytorch_model,
    output_path=out_dir,
    output_name_prefix="tsunami-intents",
    prefill_seq_len=256,
    kv_cache_max_len=1024,
    quantize="dynamic_int8",
    export_config=export_config,
    tokenizer_model_path=os.path.join(output_dir, "tokenizer.model"),
    base_llm_metadata_path=meta,
    output_format="litertlm",
)
print(os.listdir(out_dir))"""),
    ("code", """from google.colab import drive
drive.mount('/content/drive')
!mkdir -p /content/drive/MyDrive/tsunami-navi/
!cp /content/litertlm/*.litertlm /content/drive/MyDrive/tsunami-navi/
!cp accuracy_by_lang.csv trained_eval.json predictions.jsonl /content/drive/MyDrive/tsunami-navi/
!ls -la /content/drive/MyDrive/tsunami-navi/"""),
    ("md", "終わったら、Google ドライブの `tsunami-navi` フォルダ（`.litertlm`・正答率の表）を Claude Code に知らせる。"),
]


def main() -> None:
    nb = {"nbformat": 4, "nbformat_minor": 5, "metadata": {
        "accelerator": "GPU", "colab": {"provenance": [], "gpuType": "T4"},
        "kernelspec": {"name": "python3", "display_name": "Python 3"}}, "cells": []}
    for kind, src in CELLS:
        cell = {"cell_type": "markdown" if kind == "md" else "code", "metadata": {}, "source": src}
        if kind == "code":
            cell.update({"execution_count": None, "outputs": []})
        nb["cells"].append(cell)
    out = HERE / "functiongemma_tsunami.ipynb"
    out.write_text(json.dumps(nb, ensure_ascii=False, indent=1), encoding="utf-8")
    print(out)


if __name__ == "__main__":
    main()
