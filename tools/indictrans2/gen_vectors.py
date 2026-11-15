"""Write tokenizer tables and test_vectors.json for the C++ port, using the int8 ONNX model.

  python -I gen_vectors.py <tok_dir> <out_dir>
"""
import json
import os
import shutil
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common
import it2
import pre

tok_dir, out_dir = sys.argv[1], sys.argv[2]
for f in ("model.SRC", "dict.SRC.json", "dict.TGT.json"):
    shutil.copyfile(os.path.join(tok_dir, f), os.path.join(out_dir, f))
tok = it2.Tokenizer(out_dir)

# flat tables so that a port needs no JSON parser
sp = tok.spm
src_map = [tok.src.get(sp.IdToPiece(i), it2.UNK) for i in range(sp.GetPieceSize())]
src_map[sp.unk_id()] = it2.UNK
with open(os.path.join(out_dir, "src_spm_to_model_id.txt"), "w", encoding="utf-8", newline="\n") as f:
    f.write("\n".join(map(str, src_map)) + "\n")
with open(os.path.join(out_dir, "tgt_id_to_piece.txt"), "w", encoding="utf-8", newline="\n") as f:
    f.write("\n".join(tok.tgt[i] for i in range(len(tok.tgt))) + "\n")
assert all("\n" not in p and p for p in tok.tgt.values())

# the id-table shortcut must agree with the piece-string lookup on every evaluation sentence
mism = 0
for lang in common.LANGS:
    for kind in ("asr", "ref"):
        for s in common.sources(lang, kind):
            tagged, _ = pre.preprocess(s, lang)
            text = tagged.split(" ", 2)[2]
            a = tok.encode(tagged)[1]
            b = [tok.src[pre.TAGS[lang]], tok.src["eng_Latn"]] + [src_map[i] for i in sp.EncodeAsIds(text)] + [it2.EOS]
            mism += a != b
print("sentences where the spm-id table gives different ids than the piece lookup:", mism)

be = it2.OrtBackend(os.path.join(out_dir, "encoder.int8.onnx"), os.path.join(out_dir, "decoder.int8.onnx"))
pick = {"bn": [("ref", 0), ("ref", 1), ("ref", 5), ("asr", 2), ("asr", 3)],
        "hi": [("ref", 0), ("ref", 1), ("ref", 5), ("asr", 2), ("asr", 3)],
        "kn": [("ref", 0), ("ref", 1), ("ref", 5), ("asr", 2), ("asr", 3)]}
# make sure each language has one sentence that exercises placeholders, if the data has one
for lang in pick:
    for i, s in enumerate(common.sources(lang, "ref")):
        if pre.preprocess(s, lang)[1]:
            pick[lang][2] = ("ref", i)
            break

vectors = []
for lang, items in pick.items():
    for kind, i in items:
        s = common.sources(lang, kind)[i]
        tagged, values = pre.preprocess(s, lang)
        pieces, ids = tok.encode(tagged)
        out = it2.greedy(be, ids)
        raw = tok.decode(out)
        vectors.append({"lang": lang, "src_tag": pre.TAGS[lang], "tgt_tag": "eng_Latn", "source": "clean text" if kind == "ref" else "speech recogniser output",
                        "input": s, "preprocessed": tagged, "placeholders": {f"<ID{n}>": v for n, v in enumerate(values, 1)},
                        "src_pieces": pieces, "src_ids": ids, "greedy_output_ids": out + [it2.EOS],
                        "decoded_before_postprocessing": raw, "english": pre.postprocess(raw, values)})
        print(lang, kind, i, "|", vectors[-1]["english"])

extra = []
for lang, s in (("bn", "বড় বাড়িতে ১২৩ জন আছেন। দাম ৫.৫ টাকা, ঠিক?"),
                ("hi", "क़लम और ज़मीन – “ठीक” है | १०:३० बजे (आज) आना।"),
                ("kn", "ರನ್‍‌ಗಳಿಗೆ ೩೬ ಓಟ; ಬೆಲೆ 1,25,000 ರೂ. ಇದೆ!"),
                ("hi", "mail test.user@example.com, site www.example.com, 12/05/2023, 50 % और #tag")):
    tagged, values = pre.preprocess(s, lang)
    pieces, ids = tok.encode(tagged)
    extra.append({"lang": lang, "input": s, "input_codepoints": " ".join(f"{ord(c):04X}" for c in s), "preprocessed": tagged,
                  "placeholders": {f"<ID{n}>": v for n, v in enumerate(values, 1)}, "src_pieces": pieces, "src_ids": ids})
detok = [{"decoded_before_postprocessing": a, "english": pre.postprocess(a, [])} for a in (
    "He said , \" I don 't know , \" and left ( quickly ) .", "The boys ' team won $ 5 million ; it 's 30 % more !", "the cost is < ID1 > per unit")]
detok[-1]["placeholders"] = {"<ID1>": "1,25,000"}
detok[-1]["english"] = pre.postprocess(detok[-1]["decoded_before_postprocessing"], ["1,25,000"])

doc = {"model": "prajdabre/rotary-indictrans2-indic-en-dist-200M, exported to encoder.int8.onnx + decoder.int8.onnx (dynamic int8)",
       "notes": ["src_ids end with </s> (2). Decoding starts by feeding </s> (2); greedy_output_ids are the tokens produced after it, ending with </s> (2).",
                 "greedy = argmax of logits at every step; ties did not occur.",
                 "Text pipeline is the one specified in PORTING.md (pre.py), not IndicTransToolkit; they differ only where PORTING.md says so.",
                 "Token ids must match exactly on the same onnxruntime build; on another CPU/ORT version int8 rounding can flip a near-tie, "
                 "so compare preprocessed text, pieces and src_ids strictly and treat output ids as expected-to-match."],
       "sentences": vectors, "extra_preprocessing_cases": extra, "detokenization_cases": detok}
with open(os.path.join(out_dir, "test_vectors.json"), "w", encoding="utf-8", newline="\n") as f:
    json.dump(doc, f, ensure_ascii=False, indent=1)
print("wrote", len(vectors), "vectors")
