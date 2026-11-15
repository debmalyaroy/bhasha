"""Translate the 90 sentences with a RoPE-IndicTrans2 model.

  python -I run_it2.py torch <model_dir> <system>                     [kinds=asr,ref] [beams=1,5] [--toolkit|--minimal]
  python -I run_it2.py ort   <tok_dir> <system> <enc.onnx> <dec.onnx> [kinds] [beams] [--toolkit|--minimal]

Default text pipeline is pre.py (the port specification). --toolkit uses IndicTransToolkit's IndicProcessor instead.
--minimal uses pre.py without punctuation normalisation and placeholders.
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common
import it2
import pre

args = [a for a in sys.argv[1:] if not a.startswith("--")]
toolkit, minimal = "--toolkit" in sys.argv, "--minimal" in sys.argv
mode, tok_dir, system = args[:3]
rest = args[3:]
t0 = time.time()
if mode == "torch":
    be = it2.TorchBackend(tok_dir, threads=4)
else:
    be = it2.OrtBackend(rest[0], rest[1], threads=4)
    rest = rest[2:]
kinds = (rest[0] if rest else "asr,ref").split(",")
beams = [int(b) for b in (rest[1] if len(rest) > 1 else "1,5").split(",")]
print(f"loaded in {time.time() - t0:.1f}s", flush=True)
tok = it2.Tokenizer(tok_dir)
if toolkit:
    from IndicTransToolkit.processor import IndicProcessor
    ip = IndicProcessor(inference=True)
from sacremoses import MosesDetokenizer
moses = MosesDetokenizer(lang="en")
detok_diff = 0

for nb in beams:
    for kind in kinds:
        for lang, (tag, _) in common.LANGS.items():
            hyps, secs, ntok = [], [], 0
            for s in common.sources(lang, kind):
                t = time.perf_counter()
                if toolkit:
                    tagged = ip.preprocess_batch([s], src_lang=tag, tgt_lang="eng_Latn")[0]
                elif "--raw" in sys.argv:  # only script mapping + tags: no normalisation, no punctuation spacing
                    tagged, values = f"{tag} eng_Latn {pre.to_devanagari(s.strip(), lang)}", []
                else:
                    tagged, values = pre.preprocess(s, lang, placeholders=not minimal, punct=not minimal)
                _, ids = tok.encode(tagged)
                out = it2.greedy(be, ids) if nb == 1 else it2.beam(be, ids, n=nb)
                raw = tok.decode(out)
                hyp = ip.postprocess_batch([raw], lang="eng_Latn")[0] if toolkit else pre.postprocess(raw, values)
                secs.append(time.perf_counter() - t)
                ntok += len(out) + 1
                hyps.append(hyp)
                if not toolkit and not values:
                    detok_diff += moses.detokenize(raw.split(" ")) != hyp
            name = f"{system}-{'greedy' if nb == 1 else 'beam%d' % nb}"
            common.save(name, kind, lang, hyps, secs)
            print(name, kind, lang, f"{sum(secs) / len(secs):.2f} s/sent, {ntok / len(secs):.0f} out tok/sent", "|", hyps[0], flush=True)
print("outputs where pre.detokenize differs from sacremoses:", detok_diff)
