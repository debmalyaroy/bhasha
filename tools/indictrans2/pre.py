"""Reference implementation of the text pipeline as it should be ported to C++ (no IndicTransToolkit, no indicnlp,
no sacremoses). Only simple string operations and four ASCII regexes.

preprocess(text, lang)  -> (tagged model input, placeholder list)
postprocess(text, placeholders) -> final English
"""
import re

TAGS = {"hi": "hin_Deva", "bn": "ben_Beng", "kn": "kan_Knda"}
BASE = {"hi": 0x0900, "bn": 0x0980, "kn": 0x0C80}
ASCII_PUNCT = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"
PUNCT = set(ASCII_PUNCT + "\u0964\u0965")

# ASCII-only versions of the toolkit patterns (the toolkit's \w and \d are Unicode-aware; see PORTING.md)
A = re.ASCII
EMAIL = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Z|a-z]{2,}", A)
URL = re.compile(r"\b(?<![\w/.])(?:(?:https?|ftp)://)?(?:(?:[\w-]+\.)+(?!\.))(?:[\w/\-?#&=%.]+)+(?!\.\w+)\b", A)
NUMERAL = re.compile(r"(~?\d+\.?\d*\s?%?\s?-?\s?~?\d+\.?\d*\s?%|~?\d+%|\d+[-\/.,:']\d+[-\/.,:'+]\d+(?:\.\d+)?|\d+[-\/.:'+]\d+(?:\.\d+)?)", A)
OTHER = re.compile(r"[A-Za-z0-9]*[#|@]\w+", A)


def punc_norm(s):
    s = s.replace("\r", "")
    s = re.sub(r"\(\s*", "(", s)
    s = re.sub(r"\s*\)", ")", s)
    s = re.sub(r"\s:\s?", ":", s)
    s = re.sub(r"\s;\s?", ";", s)
    for c in "`\u00b4\u2018\u201a\u2019":
        s = s.replace(c, "'")
    for c in "\u201e\u201c\u201d\u00ab\u00bb":
        s = s.replace(c, '"')
    s = s.replace("\u2013", "-").replace("\u2014", "-")
    s = s.replace("\u00a0%", "%").replace("n\u00ba\u00a0", "n\u00ba ").replace("\u00a0\u00baC", " \u00baC")
    for c in "?!;":
        s = s.replace("\u00a0" + c, c)
    s = s.replace(",\u00a0", ", ")
    s = re.sub(r" {2,}", " ", s)
    s = re.sub(r"\) ([.!:?;,])", r")\1", s)
    s = re.sub(r"(\d) %", r"\1%", s)
    s = re.sub(r"\"([,.]+)", r'\1"', s)
    s = re.sub(r"(\d)\u00a0(\d)", r"\1.\2", s)
    return s.strip()


def digits(s):
    out = []
    for ch in s:
        c = ord(ch)
        if 0x0966 <= c <= 0x096F or 0x09E6 <= c <= 0x09EF or 0x0CE6 <= c <= 0x0CEF:
            ch = chr(0x30 + (c & 0xF) - 6)
        out.append(ch)
    return "".join(out)


def wrap_placeholders(s):
    values = []
    for pat in (EMAIL, URL, NUMERAL, OTHER):
        seen = []
        for m in pat.finditer(s):
            if m.group(0) not in seen:
                seen.append(m.group(0))
        for v in seen:
            if pat is URL and len(v.replace(".", "")) < 4:
                continue
            if pat is NUMERAL and len(v.replace(" ", "").replace(".", "").replace(":", "")) < 4:
                continue
            if v not in s:
                continue
            values.append(v)
            s = s.replace(v, f"<ID{len(values)}>")
    s = re.sub(r"\s+", " ", s).replace(">/", ">").replace("]/", "]")
    return s, values


def script_normalize(s, lang):
    for c in "\ufeff\ufffe\u2060\u00ad\u200c\u200d":
        s = s.replace(c, "")
    s = s.replace("\u200b", " ").replace("\u00a0", " ")
    for c in "\u201e\u201c\u201d":
        s = s.replace(c, '"')
    s = s.replace("\u2013", "-").replace("\u2014", " - ")
    for c in "\u00b4\u2018\u201a\u2019":
        s = s.replace(c, "'")
    s = s.replace("''", '"').replace("\u2026", "...")
    if lang == "hi":
        s = s.replace("\u0972", "\u090f")
        for a, b in (("\u0929", "\u0928"), ("\u0931", "\u0930"), ("\u0934", "\u0933"), ("\u0958", "\u0915"), ("\u0959", "\u0916"), ("\u095a", "\u0917"),
                     ("\u095b", "\u091c"), ("\u095c", "\u0921"), ("\u095d", "\u0922"), ("\u095e", "\u092b"), ("\u095f", "\u092f")):
            s = s.replace(a, b + "\u093c")
        s = s.replace("|", "\u0964")
    elif lang == "bn":
        s = s.replace("\u09dc", "\u09a1\u09bc").replace("\u09dd", "\u09a2\u09bc").replace("\u09df", "\u09af\u09bc")
        s = s.replace("\u09e4", "\u0964").replace("\u09e5", "\u0965").replace("|", "\u0964").replace("\u09f7", "\u0964")
        s = s.replace("\u09c7\u09be", "\u09cb").replace("\u09c7\u09d7", "\u09cc")
    elif lang == "kn":
        s = s.replace("\u0ce4", "\u0964").replace("\u0ce5", "\u0965")
        s = s.replace("\u0cbf\u0cd5", "\u0cc0").replace("\u0cc6\u0cd5", "\u0cc7").replace("\u0cc6\u0cd6", "\u0cc8")
        s = s.replace("\u0cc6\u0cc2", "\u0cca").replace("\u0cca\u0cd5", "\u0ccb")
    return s


def tokenize(s):
    out = []
    for ch in s.replace("\t", " "):
        out.append(f" {ch} " if ch in PUNCT else ch)
    s = re.sub(r" +", " ", "".join(out)).strip(" ")
    # re-join numbers / dates that the punctuation split broke apart: "3 . 5" -> "3.5"
    return re.sub(r"([0-9]+ [,.:/] )+[0-9]+", lambda m: m.group(0).replace(" ", ""), s)


def to_devanagari(s, lang):
    base = BASE[lang]
    if base == 0x0900:
        return s
    out = []
    for ch in s:
        o = ord(ch) - base
        out.append(chr(0x0900 + o) if 0 <= o <= 0x6F else ch)
    return "".join(out).replace(" \u094d ", "\u094d")


def preprocess(text, lang, placeholders=True, punct=True):
    s = punc_norm(text) if punct else text.strip()
    s = digits(s)
    values = []
    if placeholders:
        s, values = wrap_placeholders(s)
    s = script_normalize(s.strip(), lang)
    s = to_devanagari(tokenize(s), lang).strip()
    return f"{TAGS[lang]} eng_Latn {s}", values


# ----------------------------------------------------------------------------- English post-processing
CURRENCY = set("$\u00a2\u00a3\u00a4\u00a5\u058f\u060b\u09f2\u09f3\u0af1\u0bf9\u0e3f\u17db\u20a0\u20a1\u20a2\u20a3\u20a4\u20a5\u20a6\u20a7\u20a8\u20a9\u20aa\u20ab\u20ac"
               "\u20ad\u20ae\u20af\u20b0\u20b1\u20b2\u20b3\u20b4\u20b5\u20b6\u20b7\u20b8\u20b9\u20ba\u20bd\u20be\u20bf\ufdfc\ufe69\uff04\uffe0\uffe1\uffe5\uffe6")
OPENERS = CURRENCY | set("([{\u00bf\u00a1")
CLOSERS = set(",.?!:;\\%}])")
QUOTES = set("'\"\u201e\u201c`")


def detokenize(s):
    """sacremoses MosesDetokenizer(lang='en').detokenize(s.split(' ')) without the CJK branch."""
    s = (" " + s + " ").replace(" @-@ ", "-")
    for a, b in (("&bar;", "|"), ("&#124;", "|"), ("&lt;", "<"), ("&gt;", ">"), ("&bra;", "["), ("&ket;", "]"), ("&quot;", '"'),
                 ("&apos;", "'"), ("&#91;", "["), ("&#93;", "]"), ("&amp;", "&")):
        s = s.replace(a, b)
    toks = s.split()
    out, sep, counts = "", " ", {}
    for i, t in enumerate(toks):
        if all(c in OPENERS for c in t):
            out += sep + t
            sep = ""
        elif all(c in CLOSERS for c in t):
            out += t
            sep = " "
        elif i > 0 and len(t) > 1 and t[0] == "'" and t[1].isalpha():
            out += t
            sep = " "
        elif all(c in QUOTES for c in t):
            key = '"' if all(c in "\u201e\u201c\u201d" for c in t) else t
            n = counts.get(key, 0)
            if n % 2 == 0:
                if t == "'" and i > 0 and toks[i - 1].endswith("s"):
                    out += t
                    sep = " "
                else:
                    out += sep + t
                    sep = ""
                    counts[key] = n + 1
            else:
                out += t
                sep = " "
                counts[key] = n + 1
        else:
            out += sep + t
            sep = " "
    return re.sub(r" {2,}", " ", out).strip()


def postprocess(text, values):
    for n, v in enumerate(values, 1):
        for form in (f"<ID{n}>", f"< ID{n} >", f"[ID{n}]", f"[ ID{n} ]", f"[ID {n}]", f"<ID{n}]", f"< ID{n}]", f"<ID{n} ]"):
            text = text.replace(form, v).replace(form.replace("ID", "id"), v)
    return detokenize(text)
