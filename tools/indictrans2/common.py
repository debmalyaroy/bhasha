"""Shared helpers: evaluation data, output files, scoring."""
import json
import os
import re
import sys

ROOT = r"D:\codebase\experiment\translator"
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "out")
os.makedirs(OUT, exist_ok=True)

LANGS = {"bn": ("ben_Beng", "Bengali"), "hi": ("hin_Deva", "Hindi"), "kn": ("kan_Knda", "Kannada")}

sys.stdout.reconfigure(encoding="utf-8")


def load_tsv(path):
    return [l.rstrip("\r\n").split("\t", 1)[1] for l in open(path, encoding="utf-8") if l.strip()]


def sources(lang, kind):
    """kind: 'asr' = the recogniser output Gemma was given (build/mt/<lang>_all.txt), 'ref' = clean FLEURS text."""
    if kind == "asr":
        return [l.rstrip("\r\n") for l in open(os.path.join(ROOT, "build", "mt", f"{lang}_all.txt"), encoding="utf-8") if l.strip()]
    return load_tsv(os.path.join(ROOT, "build", "eval", lang, "refs.tsv"))


def references(lang):
    return load_tsv(os.path.join(ROOT, "build", "eval", lang, "en.tsv"))


def save(system, kind, lang, hyps, secs):
    """One JSON per (system, input kind, language)."""
    with open(os.path.join(OUT, f"{system}__{kind}__{lang}.json"), "w", encoding="utf-8") as f:
        json.dump({"system": system, "kind": kind, "lang": lang, "hyps": hyps, "secs": secs}, f, ensure_ascii=False, indent=1)


def load(system, kind, lang):
    p = os.path.join(OUT, f"{system}__{kind}__{lang}.json")
    return json.load(open(p, encoding="utf-8")) if os.path.exists(p) else None


def gemma_baseline(lang, model="gemma"):
    hyps = [l[5:].strip() for l in open(os.path.join(ROOT, "build", "mt", f"{lang}_all_{model}.txt"), encoding="utf-8") if l.startswith("  => ")]
    log = open(os.path.join(ROOT, "build", "mt", f"{lang}_all_{model}.log"), encoding="utf-8", errors="replace").read()
    secs = [(float(a) + float(b)) / 1000 for a, b in re.findall(r"in ([\d.]+) ms \| output \d+ tok in ([\d.]+) ms", log)]
    return hyps, secs
