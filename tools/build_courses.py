# SPDX-License-Identifier: AGPL-3.0-or-later
"""Merge content/learn/<code>/*.json into app/src/main/assets/learn/<code>.json and check the result.

Usage: python tools/build_courses.py [--check] [hi bn kn]
Exit status is non-zero when a check fails. See content/learn/SPEC.md for the format.
"""
import json
import pathlib
import re
import sys
import unicodedata

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "content" / "learn"
OUT = ROOT / "app" / "src" / "main" / "assets" / "learn"

# Unicode block of each script: (first, last).
BLOCK = {"hi": (0x0900, 0x097F), "bn": (0x0980, 0x09FF), "kn": (0x0C80, 0x0CFF)}
INDIC = (0x0900, 0x0DFF)
KEEP_IDS = ["greetings", "introductions", "numbers", "food", "directions",
            "shopping", "family", "time", "emergencies", "conversation"]
GROUPS = ["Vowels", "Consonants", "Vowel signs", "Other signs", "Common conjuncts", "Numerals"]


def check_native(code, text, where, errors):
    lo, hi = BLOCK[code]
    if text != unicodedata.normalize("NFC", text):
        errors.append(f"{where}: not NFC-normalised: {text}")
    own = 0
    for ch in text:
        cp = ord(ch)
        if lo <= cp <= hi:
            own += 1
        elif cp in (0x0964, 0x0965, 0x200C, 0x200D):
            pass  # danda, joiners
        elif INDIC[0] <= cp <= INDIC[1]:
            errors.append(f"{where}: character U+{cp:04X} from another script in: {text}")
        elif ch.isalpha():
            errors.append(f"{where}: Latin or other letter '{ch}' in native text: {text}")
    if own == 0:
        errors.append(f"{where}: no {code} letters in native text: {text!r}")


def check_roman(text, where, errors):
    if not text.strip():
        errors.append(f"{where}: empty romanisation")
    for ch in text:
        if ord(ch) > 0x024F and not unicodedata.combining(ch):
            errors.append(f"{where}: non-Latin character '{ch}' in romanisation: {text}")
            break
    if text != text.lower():
        errors.append(f"{where}: romanisation is not lower case: {text}")


def check_phrase(code, p, where, errors, need_literal):
    for key in ("native", "roman", "english"):
        if not isinstance(p.get(key), str) or not p[key].strip():
            errors.append(f"{where}: missing {key}")
            return
    unknown = set(p) - {"native", "roman", "english", "literal", "note"}
    if unknown:
        errors.append(f"{where}: unknown keys {sorted(unknown)}")
    check_native(code, p["native"], where, errors)
    check_roman(p["roman"], where, errors)
    n_native = len(p["native"].split())
    n_roman = len(p["roman"].split())
    if n_native != n_roman:
        errors.append(f"{where}: {n_native} words in native but {n_roman} in romanisation: "
                      f"{p['native']} / {p['roman']}")
    if need_literal and n_native > 1 and not p.get("literal", "").strip():
        errors.append(f"{where}: no word-for-word gloss: {p['native']}")
    if re.search(r"[ऀ-෿]", p["english"]):
        errors.append(f"{where}: native script in the English: {p['english']}")


def check_sections(code, sections, where, errors):
    for i, s in enumerate(sections):
        w = f"{where} section {i + 1}"
        if not s.get("title") or not s.get("text"):
            errors.append(f"{w}: missing title or text")
        for j, ex in enumerate(s.get("examples", [])):
            check_phrase(code, ex, f"{w} example {j + 1}", errors, need_literal=False)


def build(code):
    errors, warnings = [], []
    folder = SRC / code
    files = sorted(folder.glob("*.json"))
    if not files or files[0].name != "00-course.json":
        return None, [f"{code}: 00-course.json is missing"], warnings
    course = json.loads(files[0].read_text(encoding="utf-8"))
    units = []
    for f in files[1:]:
        try:
            unit = json.loads(f.read_text(encoding="utf-8"))
        except json.JSONDecodeError as e:
            errors.append(f"{code}/{f.name}: invalid JSON: {e}")
            continue
        unit["_file"] = f.name
        units.append(unit)

    if course.get("code") != code:
        errors.append(f"{code}: 00-course.json has code {course.get('code')}")
    check_sections(code, course.get("about", []), f"{code}/about", errors)
    if len(course.get("about", [])) < 6:
        errors.append(f"{code}: only {len(course.get('about', []))} 'about' sections")
    names = [g.get("group") for g in course.get("letters", [])]
    if names != GROUPS:
        errors.append(f"{code}: letter groups are {names}, expected {GROUPS}")
    seen = set()
    for g in course.get("letters", []):
        for it in g.get("items", []):
            w = f"{code}/letters/{g.get('group')}/{it.get('char')}"
            if it.get("char") in seen:
                errors.append(f"{w}: listed twice")
            seen.add(it.get("char"))
            check_native(code, it.get("char", ""), w, errors)
            check_roman(it.get("roman", ""), w, errors)
            if it.get("example"):
                check_native(code, it["example"], w + " example", errors)
                check_roman(it.get("exampleRoman", ""), w + " example", errors)
                if not it.get("exampleMeaning"):
                    errors.append(f"{w}: example has no meaning")
                if g.get("group") in ("Vowels", "Consonants") and it["char"] not in it["example"]:
                    warnings.append(f"{w}: example {it['example']} does not contain the letter")
            else:
                warnings.append(f"{w}: no example word")

    ids = [u.get("id") for u in units]
    if len(set(ids)) != len(ids):
        errors.append(f"{code}: duplicate unit ids")
    for k in KEEP_IDS:
        if k not in ids:
            errors.append(f"{code}: existing unit id '{k}' is gone")
    total = 0
    for u in units:
        w = f"{code}/{u['_file']}"
        for key in ("id", "level", "title", "description"):
            if not u.get(key):
                errors.append(f"{w}: missing {key}")
        phrases = u.get("phrases", [])
        total += len(phrases)
        if not 12 <= len(phrases) <= 16:
            errors.append(f"{w}: {len(phrases)} phrases (need 12-16)")
        if not 2 <= len(u.get("guide", [])) <= 5:
            errors.append(f"{w}: {len(u.get('guide', []))} guide sections (need 2-5)")
        check_sections(code, u.get("guide", []), w + " guide", errors)
        for key in ("native", "english"):
            values = [p.get(key, "").strip().lower() for p in phrases]
            for v in sorted({v for v in values if values.count(v) > 1}):
                errors.append(f"{w}: {key} appears twice in the unit: {v}")
        for i, p in enumerate(phrases):
            check_phrase(code, p, f"{w} phrase {i + 1}", errors, need_literal=True)

    # The same native sentence must always be romanised and translated the same way.
    by_native = {}
    for u in units:
        for p in u.get("phrases", []) + [e for s in u.get("guide", []) for e in s.get("examples", [])]:
            if "native" in p and "roman" in p:
                by_native.setdefault(p["native"].strip(), set()).add(p["roman"].strip().lower())
    for native, romans in by_native.items():
        if len(romans) > 1:
            errors.append(f"{code}: '{native}' is romanised differently: {sorted(romans)}")

    # A word should be romanised the same way wherever it appears.
    word_forms = {}
    strip = "।.,?!;:\"'()-–—"
    for native, romans in by_native.items():
        for roman in romans:
            nw, rw = native.split(), roman.split()
            if len(nw) == len(rw):
                for a, b in zip(nw, rw):
                    a, b = a.strip(strip), b.strip(strip)
                    if a and b:
                        word_forms.setdefault(a, set()).add(b)
    for word, forms in sorted(word_forms.items()):
        if len(forms) > 1:
            warnings.append(f"{code}: word '{word}' romanised as {sorted(forms)}")

    for u in units:
        del u["_file"]
    course["units"] = units
    print(f"{code}: {len(units)} units, {total} phrases, {sum(len(g['items']) for g in course.get('letters', []))} letters, "
          f"{len(errors)} errors, {len(warnings)} warnings")
    return course, errors, warnings


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    check_only = "--check" in sys.argv
    failed = False
    for code in args or ["hi", "bn", "kn"]:
        course, errors, warnings = build(code)
        for w in warnings:
            print("  warning:", w)
        for e in errors:
            print("  ERROR:", e)
        if errors:
            failed = True
        elif not check_only:
            OUT.mkdir(parents=True, exist_ok=True)
            (OUT / f"{code}.json").write_text(
                json.dumps(course, ensure_ascii=False, indent=1) + "\n", encoding="utf-8", newline="\n")
            print(f"  wrote {OUT / (code + '.json')}")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
