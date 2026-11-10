"""FunctionGemma の予測を採点する（docs/finetune-eval.md の物差し）。採点の仕方は変えない。学習する人は予測のファイルを出すだけ。

使い方（backend/ で）:
  python -m finetune.score finetune/data/test.jsonl predictions.jsonl
  python -m finetune.score finetune/data/test.jsonl --baseline      # キーワード方式（比べる相手）だけを採点

predictions.jsonl は test.jsonl と同じ順・同じ行数で、1 行 = {"name": "reselect", "args": {"mobility": "slow"}, "latency_ms": 820}
（name が読めなかった時は null。latency_ms はタブレットで測った時だけ入れる）
"""
import json
import re
import sys
from collections import defaultdict

THRESHOLDS = {
    "overall": 0.90,
    "per_label": 0.85,
    "per_lang": 0.85,
    "critical_max": 0.02,
    "false_action_max": 0.03,
    "parse_fail_max": 0.01,
    "p95_latency_ms": 3000,
}
CRITICAL_GOLD = {("reselect", "slow"), ("reselect", "wheelchair"), ("injured", None), ("road_blocked", None)}

KEYWORDS = [
    ("reselect", {"mobility": "wheelchair"}, r"wheelchair|stroller|pram|cane|stairs|車いす|車椅子|ベビーカー|杖|階段|轮椅|婴儿车|楼梯|휠체어|유모차|계단"),
    ("reselect", {"mobility": "slow"}, r"can'?t (walk|run)|cannot (walk|run)|slow|elderly|old|child|kid|baby|pregnan|走れ|歩けな|遅い|子ども|子供|高齢|妊娠|走不|走不动|老人|孩子|慢|못 (걷|뛰)|느려|아이|노인|임신"),
    ("road_blocked", {}, r"block|closed|flood|can'?t pass|ふさが|塞が|通れ|冠水|堵|过不去|淹|막혔|못 지나|침수"),
    ("ask_floor", {}, r"floor|how high|upstairs|何階|どこまで上|几楼|多高|몇 층|얼마나 높"),
    ("lost_family", {}, r"lost|can'?t find|separated|missing|はぐれ|見つから|いない|走散|找不到|잃어|못 찾|헤어"),
    ("injured", {}, r"injur|hurt|bleed|blood|pain|sick|けが|怪我|血|痛|具合|受伤|流血|疼|다쳤|피|아파"),
    ("when_to_return", {}, r"go back|return|hotel|luggage|how long|戻|帰|荷物|いつまで|回去|行李|多久|돌아가|짐|언제까지"),
    ("repeat", {}, r"again|repeat|didn'?t (hear|understand)|pardon|what\?|もう一度|聞こえ|分から|再说|没听|다시|못 들"),
    ("reselect", {"mobility": "normal"}, r"another|other place|somewhere else|different|full|crowded|別の|他の|混ん|满|别的|其他|다른|붐"),
]


def keyword_baseline(text: str) -> tuple[str, dict]:
    low = text.lower()
    for name, args, pattern in KEYWORDS:
        if re.search(pattern, low):
            return name, args
    return "other", {}


def mobility(name: str | None, args: dict | None) -> str | None:
    return (args or {}).get("mobility") if name == "reselect" else None


def score(gold: list[dict], preds: list[dict]) -> dict:
    assert len(gold) == len(preds), f"行数が違う: test {len(gold)} / 予測 {len(preds)}"
    by_label, by_lang = defaultdict(list), defaultdict(list)
    critical = critical_mismatch = false_action = parse_fail = 0
    n_critical_gold = n_other_gold = 0
    latencies = []
    for g, p in zip(gold, preds):
        pname, pargs = p.get("name"), p.get("args") or {}
        ok = pname == g["name"] and pargs == g["args"]
        label = g["name"] + (f"({g['args']['mobility']})" if g["args"] else "")
        by_label[label].append(ok)
        by_lang[g["lang"]].append(ok)
        if not pname:
            parse_fail += 1
        gkey = (g["name"], mobility(g["name"], g["args"]))
        if gkey in CRITICAL_GOLD:
            n_critical_gold += 1
            if not ok:
                critical_mismatch += 1
            if pname == "other" or (pname == "reselect" and mobility(pname, pargs) == "normal"):
                critical += 1
        if g["name"] == "other":
            n_other_gold += 1
            if pname not in (None, "other", "repeat"):
                false_action += 1
        if p.get("latency_ms") is not None:
            latencies.append(p["latency_ms"])
    all_ok = [x for v in by_lang.values() for x in v]
    latencies.sort()
    p95 = latencies[int(0.95 * (len(latencies) - 1))] if latencies else None
    return {
        "overall": sum(all_ok) / len(all_ok),
        "per_label": {k: sum(v) / len(v) for k, v in sorted(by_label.items())},
        "per_lang": {k: sum(v) / len(v) for k, v in sorted(by_lang.items())},
        "critical_rate": critical / max(1, n_critical_gold),
        "critical_mismatch_rate": critical_mismatch / max(1, n_critical_gold),
        "critical_total": n_critical_gold,
        "false_action_rate": false_action / max(1, n_other_gold),
        "parse_fail_rate": parse_fail / len(gold),
        "p95_latency_ms": p95,
        "n": len(gold),
    }


def report(name: str, r: dict, baseline: dict | None) -> str:
    t = THRESHOLDS
    mark = lambda ok: "OK" if ok else "NG"
    lines = [f"## {name}（{r['n']} 件）", "", "| 指標 | 値 | 成功の線 | 判定 |", "|---|---|---|---|"]
    lines.append(f"| 正答率（全体） | {r['overall']:.1%} | {t['overall']:.0%} 以上・キーワード方式より上 | "
                 f"{mark(r['overall'] >= t['overall'] and (baseline is None or r['overall'] > baseline['overall']))} |")
    worst_label = min(r["per_label"].items(), key=lambda kv: kv[1])
    lines.append(f"| 頼みごとごと（一番低いもの） | {worst_label[0]} {worst_label[1]:.1%} | どれも {t['per_label']:.0%} 以上 | {mark(worst_label[1] >= t['per_label'])} |")
    worst_lang = min(r["per_lang"].items(), key=lambda kv: kv[1])
    lines.append(f"| 言語ごと（一番低いもの） | {worst_lang[0]} {worst_lang[1]:.1%} | どれも {t['per_lang']:.0%} 以上 | {mark(worst_lang[1] >= t['per_lang'])} |")
    lines.append(f"| 危ない間違い（既存定義） | {r['critical_rate']:.1%} | {t['critical_max']:.0%} 以下 | {mark(r['critical_rate'] <= t['critical_max'])} |")
    lines.append(f"| 危険ケースの全不一致（{r['critical_total']}件） | {r['critical_mismatch_rate']:.1%} | 参考値・基準未設定 | — |")
    lines.append(f"| 関係ない言葉で動いてしまう | {r['false_action_rate']:.1%} | {t['false_action_max']:.0%} 以下 | {mark(r['false_action_rate'] <= t['false_action_max'])} |")
    lines.append(f"| 形の崩れ | {r['parse_fail_rate']:.1%} | {t['parse_fail_max']:.0%} 以下 | {mark(r['parse_fail_rate'] <= t['parse_fail_max'])} |")
    lat = r["p95_latency_ms"]
    lines.append(f"| 速さ（遅いほうから 5%） | {'未計測' if lat is None else f'{lat:.0f} ms'} | {t['p95_latency_ms']} ms 以内 | {'—' if lat is None else mark(lat <= t['p95_latency_ms'])} |")
    lines += ["", "頼みごとごと: " + " / ".join(f"{k} {v:.0%}" for k, v in r["per_label"].items()),
              "言語ごと: " + " / ".join(f"{k} {v:.0%}" for k, v in r["per_lang"].items()), ""]
    return "\n".join(lines)


def main() -> None:
    gold = [json.loads(l) for l in open(sys.argv[1], encoding="utf-8")]
    base_preds = [dict(zip(("name", "args"), keyword_baseline(g["text"]))) for g in gold]
    base = score(gold, base_preds)
    out = [report("キーワード方式（比べる相手）", base, None)]
    if len(sys.argv) > 2 and sys.argv[2] != "--baseline":
        preds = [json.loads(l) for l in open(sys.argv[2], encoding="utf-8")]
        out.append(report("FunctionGemma", score(gold, preds), base))
    print("\n".join(out))


if __name__ == "__main__":
    main()
