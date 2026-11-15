# Porting the IndicTrans2 (RoPE, distilled 200M) Indic→English translator to C++ / ONNX Runtime

Scope: Hindi, Bengali and Kannada → English, one sentence (or short utterance) at a time, no Python at run time.
Everything below was derived from and checked against the Python pipeline in `reference/` (`pre.py` = text
processing exactly as specified here, `it2.py` = tokenizer, model definition and decoding loop, `export_onnx.py` =
how the graphs were made); `test_vectors.json` in this folder holds end-to-end expected values produced with the
int8 model.

Model: `prajdabre/rotary-indictrans2-indic-en-dist-200M` (MIT). It is the IndicTrans2 distilled indic→en model with
rotary position embeddings, published ungated by one of the IndicTrans2 authors. The original
`ai4bharat/indictrans2-indic-en-dist-200M` is gated on Hugging Face (login + accepting a form) and was not downloaded.
Tokenizer files and text processing are the same for both; only the ONNX graphs would differ.

## 1. Files in this folder

| File | Size | Needed at run time | What it is |
|---|---|---|---|
| `encoder.int8.onnx` | 124.4 MB | yes | encoder + the decoder's cross-attention key/value projections, int8 dynamic quantisation |
| `decoder.int8.onnx` | 96.1 MB | yes | one decoder step with self-attention cache, int8 dynamic quantisation |
| `encoder.onnx`, `decoder.onnx` | 492.8 MB, 379.4 MB | no | the same graphs in float32 (reference; drop-in replaceable) |
| `model.SRC` | 3.1 MB | yes | SentencePiece model for the source side (BPE, 128 000 pieces, `nmt_nfkc` normaliser) |
| `dict.SRC.json` | 3.2 MB | yes (or the `.txt` table) | piece string → model input id (122 706 entries) |
| `dict.TGT.json` | 0.6 MB | yes (or the `.txt` table) | piece string → model output id (32 296 entries) |
| `src_spm_to_model_id.txt` | 0.7 MB | alternative to `dict.SRC.json` | line *i* (0-based) = model id for SentencePiece id *i* (128 000 lines) |
| `tgt_id_to_piece.txt` | 0.3 MB | alternative to `dict.TGT.json` | line *i* (0-based) = piece string for output id *i* (32 296 lines, UTF-8) |
| `test_vectors.json` | – | no | expected values for validating a port |
| `reference/*.py` | – | no | the Python reference the numbers and vectors came from |

The target SentencePiece model (`model.TGT`) is not needed for indic→en inference and is not copied here.

ONNX facts: IR version 8, opset 17, only standard `ai.onnx` operators (the int8 files add `DynamicQuantizeLinear`,
`MatMulInteger`, `DequantizeLinear`). The `libonnxruntime.so` already shipped with sherpa-onnx in
`engine/third_party/sherpa-onnx` identifies itself as 1.28.2 and contains these kernels, so no second runtime should be
needed; you need matching ONNX Runtime C/C++ headers. (Not load-tested against that binary; the measurements here used
onnxruntime 1.32.0 from pip on Windows x64.)

## 2. The pipeline at a glance

```
text ──► preprocess (§3) ──► "hin_Deva eng_Latn <devanagari text, punctuation spaced out>"
     ──► SentencePiece pieces + 2 tag tokens ──► ids (§4) ──► encoder.onnx (§5) ──► cross_k, cross_v
     ──► decoder.onnx called once per output token (§6) ──► output ids
     ──► pieces ──► string ──► clean-up + detokenise (§7) ──► English
```

All three languages are fed to the model **in Devanagari**: Bengali and Kannada letters are mapped to the Devanagari
code point with the same offset inside the Unicode block. The language tag, not the script, tells the model which
language it is.

## 3. Preprocessing (input text → tagged string)

Work on Unicode code points (decode UTF-8 to `char32_t` first). Apply the steps in this order. Steps marked
**[required]** change the model input for ordinary text in these three languages; steps marked **[typed text]** only
do something when the text contains punctuation, digits, URLs etc., which recogniser output normally does not (see §8
for measurements).

### 3.1 Punctuation normalisation **[typed text]**
In order:
1. delete `\r`
2. `(` followed by any whitespace → `(` ; any whitespace followed by `)` → `)`
3. one whitespace char + `:` + optional one whitespace char → `:` ; same for `;`
   (so `"है : ठीक"` → `"है:ठीक"`; the tokeniser in 3.5 puts the spaces back)
4. each of `` ` ´ ‘ ‚ ’ `` (U+0060, U+00B4, U+2018, U+201A, U+2019) → `'`
5. each of `„ “ ” « »` (U+201E, U+201C, U+201D, U+00AB, U+00BB) → `"`
6. `–` (U+2013) and `—` (U+2014) → `-`
7. NBSP (U+00A0) handling: `NBSP %` → `%`; `nº NBSP` → `nº ` ; `NBSP ºC` → ` ºC`; `NBSP` before `?`, `!` or `;` is
   deleted; `, NBSP` → `, `
8. runs of 2+ spaces → one space
9. `") "` followed by one of `. ! : ? ; ,` → drop the space
10. digit, space, `%` → digit `%`
11. `"` followed by a run of `,`/`.` → move the quote after the run (`",` → `,"`)
12. digit NBSP digit → digit `.` digit
13. trim leading/trailing whitespace

"digit" here includes the native digits because 3.2 has not run yet; running 3.2 first gives the same result and is
simpler.

### 3.2 Digits → ASCII **[required when digits occur]**
Devanagari U+0966–U+096F, Bengali U+09E6–U+09EF, Kannada U+0CE6–U+0CEF → `0`–`9`
(`'0' + (cp & 0xF) - 6`).

### 3.3 Placeholders for things that must be copied, not translated **[typed text, optional]**
The Python toolkit replaces e-mail addresses, URLs, some number formats and `#tag`/`@name` by `<ID1>`, `<ID2>`, … and
puts the original text back after translation. Patterns, tried in this order (ASCII classes: `\w` = `[A-Za-z0-9_]`,
`\d` = `[0-9]`, `\b` = ASCII word boundary):

1. e-mail: `[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Z|a-z]{2,}`
2. URL: `\b(?<![\w/.])(?:(?:https?|ftp)://)?(?:(?:[\w-]+\.)+(?!\.))(?:[\w/\-?#&=%.]+)+(?!\.\w+)\b` — skip matches
   shorter than 4 characters after removing `.`
3. numbers: `~?\d+\.?\d*\s?%?\s?-?\s?~?\d+\.?\d*\s?%` | `~?\d+%` | `\d+[-/.,:']\d+[-/.,:'+]\d+(?:\.\d+)?` |
   `\d+[-/.:'+]\d+(?:\.\d+)?` — skip matches shorter than 4 characters after removing spaces, `.` and `:`
   (so `3,50,000`, `2020-21`, `12/05/2023`, `10:30`, `12.50` are wrapped; `5:30`, `3.5`, `36%`, `9876543210` are not)
4. `[A-Za-z0-9]*[#|@]\w+`

For each pattern: collect the distinct matches in order of first occurrence; for each one that still occurs in the
text, append it to the placeholder list (index *n* starting at 1) and replace **every** occurrence of that substring in
the text with `<IDn>`. Afterwards collapse every whitespace run to one space and replace `>/` by `>` and `]/` by `]`.

`std::regex` has no look-behind; use RE2/PCRE2/a hand-written scanner, or skip this step (§8). If skipped, numbers are
simply translated as text, which the model does well (`7 ವಿಕೆಟ್ … 36 ರನ್` → "7 wickets … 36 runs").

### 3.4 Script normalisation **[required]**
1. delete U+FEFF, U+FFFE, U+2060 (word joiner), U+00AD (soft hyphen), **U+200C (ZWNJ) and U+200D (ZWJ)**
2. U+200B (zero-width space) and U+00A0 → space
3. `„ “ ”` → `"`; `–` → `-`; `—` → ` - `; `´ ‘ ‚ ’` → `'`; `''` → `"`; `…` (U+2026) → `...`
   (mostly already done by 3.1; needed if 3.1 is skipped)
4. language specific:
   - **Hindi**: U+0972 → U+090F. Decompose the pre-composed nukta letters into base + U+093C:
     U+0929→0928, U+0931→0930, U+0934→0933, U+0958→0915, U+0959→0916, U+095A→0917, U+095B→091C, U+095C→0921,
     U+095D→0922, U+095E→092B, U+095F→092F. `|` → `।` (U+0964).
   - **Bengali**: decompose U+09DC → U+09A1 U+09BC (ড়), U+09DD → U+09A2 U+09BC (ঢ়), U+09DF → U+09AF U+09BC (য়).
     U+09E4 → U+0964, U+09E5 → U+0965, `|` → U+0964, U+09F7 → U+0964.
     U+09C7 U+09BE → U+09CB (ো), U+09C7 U+09D7 → U+09CC (ৌ).
   - **Kannada**: U+0CE4 → U+0964, U+0CE5 → U+0965.
     U+0CBF U+0CD5 → U+0CC0, U+0CC6 U+0CD5 → U+0CC7, U+0CC6 U+0CD6 → U+0CC8, U+0CC6 U+0CC2 → U+0CCA,
     U+0CCA U+0CD5 → U+0CCB (apply in this order).

Do **not** port the toolkit's "colon → visarga" rule; see §9.

### 3.5 Punctuation tokenisation **[required when punctuation occurs]**
1. tab → space
2. put a space before and after every character in
   ``!"#$%&'()*+,-./:;<=>?@[\]^_`{|}~`` and `।` (U+0964), `॥` (U+0965)
3. collapse runs of spaces to one, trim spaces
4. re-join numbers: every maximal match of `([0-9]+ [,.:/] )+[0-9]+` has its spaces removed
   (`3 . 5` → `3.5`, `12 / 05 / 2023` → `12/05/2023`)

A placeholder `<ID1>` therefore reaches the model as `< ID1 >`.

### 3.6 Script conversion to Devanagari **[required for Bengali and Kannada]**
For every code point `cp` with `0 <= cp - base <= 0x6F`, where `base` is 0x0980 (Bengali) or 0x0C80 (Kannada):
`cp' = 0x0900 + (cp - base)`. Everything else is unchanged (Latin, digits, punctuation, `।`, and block code points
above offset 0x6F such as Bengali `৳` U+09F3 or `ৰ` U+09F0). Hindi is left as is. Then replace `" ् "`
(space, U+094D, space) by `्`.

There is no table: it is pure offset arithmetic, e.g. Bengali `ক` U+0995 → `क` U+0915, Kannada `ಕ` U+0C95 → `क`,
Kannada `ೆ` U+0CC6 → `ॆ` U+0946, Bengali nukta U+09BC → U+093C.

### 3.7 Tags
Final string: `<src_tag> eng_Latn <text>` with src_tag `hin_Deva`, `ben_Beng` or `kan_Knda` (trim `<text>` first).

## 4. Tokenisation (tagged string → ids)

1. Split off the first two space-separated words (the tags). They are **not** passed through SentencePiece.
2. `pieces = SentencePiece(model.SRC).EncodeAsPieces(text)`. Use the real SentencePiece library (Apache-2.0): the model
   is BPE with the `nmt_nfkc` normaliser, `add_dummy_prefix`, `remove_extra_whitespaces`; re-implementing NFKC and BPE
   merging by hand is not worth it.
3. `ids = [id(src_tag), id("eng_Latn")] + [dict.SRC[piece] or 3 if absent for piece in pieces] + [2]`

SentencePiece's own ids are **not** the model's ids. The model ids come from the fairseq dictionary
(`dict.SRC.json`), looked up by piece string; 7 820 of the 128 000 SentencePiece pieces are not in the dictionary and
map to `<unk>` = 3. `src_spm_to_model_id.txt` is that lookup precomputed per SentencePiece id, so a port can call
`EncodeAsIds` and index an `int32` array instead of parsing JSON (checked identical on all 180 evaluation sentences).

| Token | id (source side) | id (target side) |
|---|---|---|
| `<s>` | 0 | 0 |
| `<pad>` | 1 | 1 |
| `</s>` | 2 | 2 |
| `<unk>` | 3 | 3 |
| `eng_Latn` | 4 | – |
| `hin_Deva` | 8 | – |
| `ben_Beng` | 10 | – |
| `kan_Knda` | 14 | – |

No BOS is added on the source side: the sequence is `src_tag, tgt_tag, pieces…, </s>`.
Source vocabulary size 122 706, target vocabulary size 32 296.

## 5. The ONNX graphs

Model dimensions: 18 encoder layers, 18 decoder layers, hidden 512, 8 heads × 64, FFN 2048, pre-norm, GELU.
In all shapes below `L = 18`, `H = 8`, `Dh = 64`, `B` = batch (1 for greedy), `S` = source length in tokens.

### encoder(.int8).onnx — run once per sentence
| | Name | Type | Shape |
|---|---|---|---|
| in | `input_ids` | int64 | `[B, S]` |
| in | `attention_mask` | int64 | `[B, S]` — 1 for real tokens, 0 for padding (all ones when B = 1) |
| out | `cross_k` | float32 | `[L, B, H, S, Dh]` |
| out | `cross_v` | float32 | `[L, B, H, S, Dh]` |

The encoder output itself is not exposed; `cross_k`/`cross_v` are the per-decoder-layer cross-attention keys and
values, computed once here so that the decoder step does not recompute them for every token.

### decoder(.int8).onnx — run once per output token
| | Name | Type | Shape |
|---|---|---|---|
| in | `input_ids` | int64 | `[B, 1]` — the previous output token (first step: 2) |
| in | `attention_mask` | int64 | `[B, S]` — same as for the encoder |
| in | `cross_k`, `cross_v` | float32 | `[L, B, H, S, Dh]` — encoder outputs, unchanged every step |
| in | `past_k`, `past_v` | float32 | `[L, B, H, T, Dh]` — `T` = number of tokens already fed; **`T = 0` on the first step** (zero-size tensors) |
| out | `logits` | float32 | `[B, 32296]` — unnormalised scores for the next token |
| out | `present_k`, `present_v` | float32 | `[L, B, H, T+1, Dh]` — feed back as `past_k`, `past_v` |

The position of the current token is taken from `T` (the size of `past_k`'s 4th dimension); there is no position
input. The rotary table inside both graphs has 1024 positions: **source length and output length must each stay
≤ 1024 tokens** (the evaluation sentences are 15–100 source tokens, 34 on average). `past_k` holds keys that are already rotated; treat the
cache as opaque.

Zero-size inputs: create `past_k`/`past_v` with shape `[18, B, 8, 0, 64]` (in the C API pass a non-null dummy data
pointer with element count 0).

## 6. Decoding

Greedy (recommended on the phone; see §8 for what beam search buys):

```
cross_k, cross_v = encoder(input_ids=[ids], attention_mask=[1,1,…])
past_k = past_v = empty [18,1,8,0,64]
tok = 2                               # decoder start token is </s>, not <s>
out = []
repeat at most max_new_tokens (256) times:
    logits, past_k, past_v = decoder([[tok]], mask, cross_k, cross_v, past_k, past_v)
    tok = argmax(logits[0])
    if tok == 2: stop                 # </s>
    out.append(tok)
```

No forced BOS/language token on the target side, no repetition penalty, no length penalty, no n-gram blocking.
A practical cap for speech is `max_new_tokens = min(256, 2 * S + 16)`.

Beam search (optional): run the decoder with `B = beam size`; repeat `cross_k`/`cross_v`/`attention_mask` along the
batch axis (axis 1 for the 5-D tensors), reorder `past_k`/`past_v` along axis 1 after every step according to which
hypothesis each surviving beam extends, rank finished hypotheses by `sum(log_softmax) / (number of generated tokens
including </s>)`. This is what `it2.beam()` does and what the "beam 5" rows in the report measured.

## 7. Post-processing (ids → English)

1. Drop ids 0, 1, 2, 3. Map each remaining id to its piece (`dict.TGT.json` inverted, or `tgt_id_to_piece.txt`).
2. Concatenate the pieces, replace `▁` (U+2581) with a space, trim.
3. "Clean-up" replacements, in this order (this is `clean_up_tokenization_spaces=True` in the Hugging Face decoder):
   `" ."`→`"."`, `" ?"`→`"?"`, `" !"`→`"!"`, `" ,"`→`","`, `" ' "`→`"'"`, `" n't"`→`"n't"`, `" 'm"`→`"'m"`,
   `" 's"`→`"'s"`, `" 've"`→`"'ve"`, `" 're"`→`"'re"`.
4. If placeholders were used: for each *n* replace `<IDn>`, `< IDn >`, `[IDn]`, `[ IDn ]`, `[ID n]`, `<IDn]`,
   `< IDn]`, `<IDn ]` and the same with lower-case `id` by the stored original text.
5. Moses detokenisation for English. The model writes English with punctuation as separate tokens
   (`He said , " no " ( twice ) .`). Split on spaces and walk the tokens, keeping `sep` (initially `" "`) and a count
   per quote token:
   - first replace `" @-@ "` by `-` and unescape `&amp; &lt; &gt; &quot; &apos; &#124; &#91; &#93; &bar; &bra; &ket;`
   - token made only of currency symbols or `( [ { ¿ ¡`: append `sep + token`, then `sep = ""`
   - token made only of `, . ? ! : ; \ % } ] )`: append `token` (no space), then `sep = " "`
   - token (not the first) starting with `'` followed by a letter (`'s`, `'t`, `'re`): append `token`, `sep = " "`
   - token made only of quote characters `' " „ “` or a backtick: if this quote token has been seen an even number
     of times so far → it opens: but if it is `'`, not the first token, and the previous token ends in `s` it is a
     possessive: append `token`, `sep = " "`, do not count it; otherwise append `sep + token`, `sep = ""`, count it.
     If seen an odd number of times → it closes: append `token`, `sep = " "`, count it.
   - anything else: append `sep + token`, `sep = " "`
   - finally collapse multiple spaces and trim.

   Steps 3 and 5 overlap; do both, in this order, to match the reference. `pre.detokenize` implements exactly this
   and produced the same string as `sacremoses.MosesDetokenizer("en")` on all 360 outputs of the int8 runs.

The model does not capitalise the first word reliably when the input has no punctuation, and does not add a final
full stop for unpunctuated recogniser output; the reference pipeline does not fix that either.

## 8. Which steps matter for Hindi / Bengali / Kannada

Measured with `encoder.int8.onnx` / `decoder.int8.onnx`, greedy, 30 sentences per language, sacrebleu chrF
(average of the three languages). "Recogniser output" is what the app's speech recogniser produced for the FLEURS
audio (no punctuation, numbers spelled out); "clean text" is the FLEURS transcript (punctuation, digits).

| Text pipeline | Recogniser output | Clean text |
|---|---|---|
| Everything in §3 | 61.1 | 66.0 |
| §3 without 3.1 (punctuation normalisation) and 3.3 (placeholders) | 61.1 (model input identical for 90/90) | 65.9 (input identical for 86/90) |
| Only 3.6 + 3.7 (script mapping and tags) | 60.9 (input identical for 86/90) | 64.0 (identical for 0/90) |
| IndicTransToolkit as installed (with the bugs in §9), float32 | 60.8 (same as float32 with §3) | 63.9 — Bengali drops from 64.9 to 59.8 |

What that means for a port:

- **Must have:** tags (3.7), script mapping (3.6), punctuation tokenisation (3.5), the Bengali and Kannada two-part
  vowel canonicalisation and ZWJ/ZWNJ removal (3.4), digit mapping (3.2). The model was trained on text in this
  form; skipping 3.5 costs about 2 chrF on punctuated text. 3.4 must run **before** 3.6: after mapping to Devanagari
  the two-part vowels no longer compose.
- **Cheap, keep:** the rest of 3.4 (quote/dash folding, danda variants, nukta decomposition).
- **Can be skipped for speech input:** 3.1 and 3.3. Recogniser output contained nothing they act on (0 of 90
  sentences), and on clean text they changed 4 of 90 sentences for a 0.1 chrF difference. If the app later accepts
  typed or pasted text, 3.3 is what keeps URLs, e-mail addresses and long numbers intact.
- In the recogniser output of this project none of the 90 sentences was changed by 3.4 either (it is already
  decomposed and free of joiners) — but that is a property of the current recogniser, so keep 3.4.
- **Decoding:** beam 5 gave +0.5 chrF (61.6 vs 61.1) on recogniser output and +0.7 (66.7 vs 66.0) on clean text for
  about 5× the decoder time in the Python harness. Start with greedy.
- **Quantisation:** int8 vs float32, greedy: 61.1 vs 60.8 on recogniser output, 66.0 vs 65.6 on clean text — no
  measurable loss on this set (differences of this size are noise at 90 sentences); 220 MB instead of 872 MB on disk,
  about 420 MB instead of 1 060 MB process memory, and 2× faster on x86-64 (encoder 25 ms per sentence, decoder
  6.9 ms per token, 0.21 s per sentence at 4 threads; float32: 42 ms, 14.2 ms, 0.43 s).

## 9. Bugs in the Python reference that must not be ported

Found in `IndicTransToolkit 1.1.1` + `indic-nlp-library-itt 0.1.1` (what `pip install IndicTransToolkit` gives
today). §3 above already describes the corrected behaviour.

1. **Pre-composed nukta letters are corrupted.** The normaliser's nukta constant is the 6-character text `়`
   (resp. `़`) instead of the nukta character, so Bengali `য়` U+09DF, `ড়` U+09DC, `ঢ়` U+09DD and Hindi
   U+0958–U+095F become e.g. `य়` and the translation contains garbage such as `ya\ u09BC`. 22 of the 30 clean
   Bengali evaluation sentences are affected (typed Bengali text normally uses the pre-composed letters); the
   recogniser output in this project is already decomposed and is not affected. Text that is already decomposed
   (base letter + U+09BC / U+093C) passes through correctly. SentencePiece's `nmt_nfkc` normaliser decomposes these
   letters by itself (checked: pre-composed and decomposed Devanagari give identical pieces), so in a port that uses
   the SentencePiece library the decomposition in §3.4 is harmless and strictly redundant — what matters is not
   inserting the literal text.
2. **Colon → visarga rule is broken.** The pattern is the regex text `[\\u0900-\\u097f]:` which, instead of an Indic
   letter before a colon, matches a digit, capital letter or one of `: ; < = > ? @ [ \ u f` before a colon and
   replaces both with the literal text `\1ः`. `5:30` becomes `\1ः30`, `ID: A12` becomes `I\1ः A12`. Skip the rule.
3. **URL placeholder misfires on Indic text.** The toolkit's URL pattern uses Unicode `\w`, so two Indic words joined
   by a full stop or slash (`70ಕಿ.ಮಿ/ಗಂ`, or a missing space after a sentence end) are treated as a URL, hidden from
   the model and pasted untranslated into the English output. Using ASCII classes (§3.3) avoids this. One of the 30
   clean Kannada sentences triggers it.
4. Placeholder numbering in the toolkit follows Python `set` iteration order and is not reproducible; §3.3 uses order
   of first occurrence. This does not change the final text.

With those fixes `pre.py` and the toolkit produce identical model input for 157 of the 180 evaluation inputs; the 23
differences are exactly bug 1 (22 Bengali sentences) and bug 3 (1 Kannada sentence).

## 10. Validating a port

`test_vectors.json` → `sentences[]` (5 per language, 3 clean-text and 2 recogniser-output sentences each):
`input` → `preprocessed` (§3) → `src_pieces` → `src_ids` (§4) → `greedy_output_ids` (§5–6, int8 model, ends with 2)
→ `decoded_before_postprocessing` (§7 steps 1–3) → `english` (§7 steps 4–5).
`extra_preprocessing_cases[]` exercise pre-composed nukta letters, native digits, ZWJ/ZWNJ, quotes/dashes and
placeholders (no model output). `detokenization_cases[]` exercise §7 step 5.

Compare `preprocessed`, `src_pieces` and `src_ids` exactly. `greedy_output_ids` should match exactly on x86-64 with
the same ONNX Runtime; on ARM or another runtime version int8 rounding may flip a near-tie on a rare sentence, so
also accept a different but sensible translation there and compare against the float32 graphs if in doubt.

## 11. If the original (non-RoPE) IndicTrans2 is wanted later

`ai4bharat/indictrans2-indic-en-dist-200M` uses the same `model.SRC`, `dict.SRC.json`, `dict.TGT.json`, tags, special
ids and text processing (stated on the RoPE model card and visible from the identical file names; the gated files
themselves were not compared). The architecture difference is the position encoding: the original adds sinusoidal
position embeddings to the token embeddings instead of rotating queries/keys, so `it2.py` would need that change
(read the original `modeling_indictrans.py` for the exact position offset); the ONNX interface in §5 can stay the
same.
