"""Score every saved system with one scorer: sacrebleu corpus chrF and BLEU per language."""
import glob
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import json
import os
import re
import statistics

import sacrebleu

import common

rows = {}
for lang in common.LANGS:
    refs = common.references(lang)
    for model, label in (("gemma", "gemma-4-E2B-Q4_0 (existing outputs)"), ("sarvam", "sarvam-translate-Q4_K_M (existing outputs)")):
        hyps, secs = common.gemma_baseline(lang, model)
        rows.setdefault((label, "asr"), {})[lang] = (hyps, secs)
    for p in glob.glob(os.path.join(common.OUT, f"*__*__{lang}.json")):
        d = json.load(open(p, encoding="utf-8"))
        rows.setdefault((d["system"], d["kind"]), {})[lang] = (d["hyps"], d["secs"])

print(f"{'system':46s} {'input':5s} | " + " | ".join(f"{l} chrF  BLEU  s/sent" for l in common.LANGS) + " | avg chrF  BLEU  s/sent  non-EN")
for (system, kind), per in sorted(rows.items(), key=lambda kv: (kv[0][1], kv[0][0])):
    cells, cs, bs, ts, foreign = [], [], [], [], 0
    for lang in common.LANGS:
        if lang not in per:
            cells.append(" " * 21)
            continue
        hyps, secs = per[lang]
        refs = common.references(lang)
        assert len(hyps) == len(refs), (system, kind, lang, len(hyps))
        c = sacrebleu.corpus_chrf(hyps, [refs]).score
        b = sacrebleu.corpus_bleu(hyps, [refs]).score
        t = statistics.mean(secs) if secs else float("nan")
        foreign += sum(1 for h in hyps if re.search(r"[ऀ-෿]", h))
        cs.append(c); bs.append(b); ts.append(t)
        cells.append(f"   {c:5.1f} {b:5.1f} {t:6.2f}")
    print(f"{system:46s} {kind:5s} | " + " | ".join(cells) + f" |    {statistics.mean(cs):5.1f} {statistics.mean(bs):5.1f} {statistics.mean(ts):6.2f}  {foreign}")
