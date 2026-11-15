// SPDX-License-Identifier: AGPL-3.0-or-later
//
// tr_mt: Hindi / Bengali / Kannada -> English with IndicTrans2 (distilled 200M,
// rotary positions, int8 ONNX) on ONNX Runtime, the runtime sherpa-onnx already ships.
//
//   text -> script clean-up -> punctuation spaced out -> Devanagari -> language tags
//        -> SentencePiece -> encoder (once) -> decoder (once per word piece, greedy)
//        -> pieces -> English detokenisation
//
// The text handling follows models/cand/indictrans2/PORTING.md and is checked
// against the vectors there by engine/tests/mt_test.cpp.

#include "tr_engine.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <map>
#include <mutex>
#include <string>
#include <vector>

#include "onnxruntime_c_api.h"
#include "sentencepiece_processor.h"

#ifdef __ANDROID__
#include <android/log.h>
#define MT_LOG(...) __android_log_print(ANDROID_LOG_WARN, "bhasha_mt", __VA_ARGS__)
#else
#define MT_LOG(...) (std::fprintf(stderr, __VA_ARGS__), std::fputc('\n', stderr))
#endif

namespace {

using u32 = std::u32string;

constexpr int kLayers = 18, kHeads = 8, kHeadDim = 64;
constexpr int64_t kEos = 2, kUnk = 3, kTagEnglish = 4;
constexpr size_t kMaxSourceTokens = 400;

// ---------------------------------------------------------------- UTF-8

u32 decode(const std::string &s) {
    u32 out;
    out.reserve(s.size());
    size_t i = 0;
    while (i < s.size()) {
        const unsigned char c = static_cast<unsigned char>(s[i]);
        char32_t cp = 0xFFFD;
        int extra = 0;
        if (c < 0x80) { cp = c; }
        else if ((c >> 5) == 0x6) { cp = c & 0x1F; extra = 1; }
        else if ((c >> 4) == 0xE) { cp = c & 0x0F; extra = 2; }
        else if ((c >> 3) == 0x1E) { cp = c & 0x07; extra = 3; }
        ++i;
        for (; extra > 0 && i < s.size() && (static_cast<unsigned char>(s[i]) & 0xC0) == 0x80; --extra, ++i) {
            cp = (cp << 6) | (static_cast<unsigned char>(s[i]) & 0x3F);
        }
        out.push_back(extra == 0 ? cp : 0xFFFD);
    }
    return out;
}

void append_utf8(std::string &out, char32_t cp) {
    if (cp < 0x80) {
        out.push_back(static_cast<char>(cp));
    } else if (cp < 0x800) {
        out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else if (cp < 0x10000) {
        out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else {
        out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    }
}

std::string encode(const u32 &s) {
    std::string out;
    out.reserve(s.size() * 3);
    for (char32_t cp : s) append_utf8(out, cp);
    return out;
}

void replace_all(u32 &s, const u32 &from, const u32 &to) {
    for (size_t at = s.find(from); at != u32::npos; at = s.find(from, at + to.size())) s.replace(at, from.size(), to);
}

void replace_all(std::string &s, const std::string &from, const std::string &to) {
    for (size_t at = s.find(from); at != std::string::npos; at = s.find(from, at + to.size())) {
        s.replace(at, from.size(), to);
    }
}

bool is_space(char32_t c) { return c == U' ' || c == U'\t' || c == U'\n' || c == U'\r' || c == 0x0B || c == 0x0C; }
bool is_digit(char32_t c) { return c >= U'0' && c <= U'9'; }

u32 trim(const u32 &s) {
    size_t a = 0, b = s.size();
    while (a < b && is_space(s[a])) ++a;
    while (b > a && is_space(s[b - 1])) --b;
    return s.substr(a, b - a);
}

// ---------------------------------------------------------------- source text

struct Lang {
    const char *code;
    char32_t base;     // start of the script's Unicode block
    int64_t tag;       // id of the language tag on the source side
};

const Lang kLangs[] = {{"hi", 0x0900, 8}, {"bn", 0x0980, 10}, {"kn", 0x0C80, 14}};

const Lang *find_lang(const char *code) {
    for (const Lang &l : kLangs) {
        if (code && std::strcmp(code, l.code) == 0) return &l;
    }
    return nullptr;
}

// Native digits to ASCII, invisible characters out, quotes and dashes folded,
// and each script's spelling variants reduced to the one the model was trained on.
u32 normalize_script(const u32 &in, const Lang &lang) {
    u32 s;
    s.reserve(in.size());
    for (char32_t c : in) {
        if ((c >= 0x0966 && c <= 0x096F) || (c >= 0x09E6 && c <= 0x09EF) || (c >= 0x0CE6 && c <= 0x0CEF)) {
            c = U'0' + (c & 0xF) - 6;
        }
        switch (c) {
            case 0xFEFF: case 0xFFFE: case 0x2060: case 0x00AD: case 0x200C: case 0x200D: continue;
            case 0x200B: case 0x00A0: s.push_back(U' '); continue;
            case 0x201E: case 0x201C: case 0x201D: s.push_back(U'"'); continue;
            case 0x2013: s.push_back(U'-'); continue;
            case 0x2014: s += U" - "; continue;
            case 0x00B4: case 0x2018: case 0x201A: case 0x2019: s.push_back(U'\''); continue;
            case 0x2026: s += U"..."; continue;
            default: s.push_back(c);
        }
    }
    replace_all(s, U"''", U"\"");
    u32 out;
    out.reserve(s.size() + 8);
    if (lang.base == 0x0900) {
        static const char32_t nukta[][2] = {{0x0929, 0x0928}, {0x0931, 0x0930}, {0x0934, 0x0933}, {0x0958, 0x0915},
                                            {0x0959, 0x0916}, {0x095A, 0x0917}, {0x095B, 0x091C}, {0x095C, 0x0921},
                                            {0x095D, 0x0922}, {0x095E, 0x092B}, {0x095F, 0x092F}};
        for (char32_t c : s) {
            if (c == 0x0972) { out.push_back(0x090F); continue; }
            if (c == U'|') { out.push_back(0x0964); continue; }
            bool done = false;
            for (const auto &n : nukta) {
                if (c == n[0]) { out.push_back(n[1]); out.push_back(0x093C); done = true; break; }
            }
            if (!done) out.push_back(c);
        }
    } else if (lang.base == 0x0980) {
        for (char32_t c : s) {
            switch (c) {
                case 0x09DC: out.push_back(0x09A1); out.push_back(0x09BC); break;
                case 0x09DD: out.push_back(0x09A2); out.push_back(0x09BC); break;
                case 0x09DF: out.push_back(0x09AF); out.push_back(0x09BC); break;
                case 0x09E4: case U'|': case 0x09F7: out.push_back(0x0964); break;
                case 0x09E5: out.push_back(0x0965); break;
                default: out.push_back(c);
            }
        }
        replace_all(out, U"ো", U"ো");
        replace_all(out, U"ৌ", U"ৌ");
    } else {
        for (char32_t c : s) out.push_back(c == 0x0CE4 ? 0x0964 : c == 0x0CE5 ? 0x0965 : c);
        replace_all(out, U"ೀ", U"ೀ");
        replace_all(out, U"ೇ", U"ೇ");
        replace_all(out, U"ೈ", U"ೈ");
        replace_all(out, U"ೊ", U"ೊ");
        replace_all(out, U"ೋ", U"ೋ");
    }
    return out;
}

bool is_punct(char32_t c) {
    return c == 0x0964 || c == 0x0965 || (c < 0x80 && std::strchr("!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~", static_cast<int>(c)) && c != 0);
}

// Every punctuation mark becomes a word of its own; numbers such as "3.5" and "12/05/2023" stay whole.
u32 space_out_punctuation(const u32 &in) {
    u32 spaced;
    spaced.reserve(in.size() * 2);
    for (char32_t c : in) {
        if (c == U'\t') c = U' ';
        if (is_punct(c)) { spaced.push_back(U' '); spaced.push_back(c); spaced.push_back(U' '); }
        else spaced.push_back(c);
    }
    u32 s;
    s.reserve(spaced.size());
    for (char32_t c : spaced) {
        if (c == U' ' && (s.empty() || s.back() == U' ')) continue;
        s.push_back(c);
    }
    while (!s.empty() && s.back() == U' ') s.pop_back();

    u32 out;
    out.reserve(s.size());
    size_t i = 0;
    while (i < s.size()) {
        if (!is_digit(s[i])) { out.push_back(s[i++]); continue; }
        while (i < s.size() && is_digit(s[i])) out.push_back(s[i++]);
        // "<digits> <sep> <digits>": drop the two spaces around the separator.
        while (i + 3 < s.size() && s[i] == U' ' && (s[i + 1] == U',' || s[i + 1] == U'.' || s[i + 1] == U':' || s[i + 1] == U'/')
               && s[i + 2] == U' ' && is_digit(s[i + 3])) {
            out.push_back(s[i + 1]);
            i += 3;
            while (i < s.size() && is_digit(s[i])) out.push_back(s[i++]);
        }
    }
    return out;
}

// Bengali and Kannada letters sit at the same offsets in their blocks as their Devanagari counterparts.
u32 to_devanagari(u32 s, const Lang &lang) {
    if (lang.base != 0x0900) {
        for (char32_t &c : s) {
            if (c >= lang.base && c <= lang.base + 0x6F) c = 0x0900 + (c - lang.base);
        }
        replace_all(s, U" ् ", U"्");
    }
    return trim(s);
}

std::string prepare_source(const std::string &text, const Lang &lang) {
    return encode(to_devanagari(space_out_punctuation(normalize_script(trim(decode(text)), lang)), lang));
}

// ---------------------------------------------------------------- English output

bool all_in(const u32 &t, const u32 &set) {
    for (char32_t c : t) {
        if (set.find(c) == u32::npos) return false;
    }
    return !t.empty();
}

// Moses detokenisation for English: the model writes `He said , " no " ( twice ) .`
std::string detokenize(const std::string &text) {
    static const u32 currency =
        U"$¢£¤¥֏؋৲৳૱௹฿៛₠₡₢₣₤₥₦"
        U"₧₨₩₪₫€₭₮₯₰₱₲₳₴₵₶₷₸₹"
        U"₺₽₾₿﷼﹩＄￠￡￥￦";
    static const u32 openers = currency + U"([{¿¡";
    static const u32 closers = U",.?!:;\\%}])";
    static const u32 quotes = U"'\"„“`";

    std::string s = " " + text + " ";
    replace_all(s, " @-@ ", "-");
    static const char *entities[][2] = {{"&bar;", "|"}, {"&#124;", "|"}, {"&lt;", "<"}, {"&gt;", ">"}, {"&bra;", "["},
                                        {"&ket;", "]"}, {"&quot;", "\""}, {"&apos;", "'"}, {"&#91;", "["}, {"&#93;", "]"},
                                        {"&amp;", "&"}};
    for (const auto &e : entities) replace_all(s, e[0], e[1]);

    std::vector<u32> toks;
    {
        u32 cur;
        for (char32_t c : decode(s)) {
            if (is_space(c)) {
                if (!cur.empty()) toks.push_back(cur);
                cur.clear();
            } else {
                cur.push_back(c);
            }
        }
        if (!cur.empty()) toks.push_back(cur);
    }

    u32 out, sep = U" ";
    std::map<u32, int> seen;
    for (size_t i = 0; i < toks.size(); ++i) {
        const u32 &t = toks[i];
        const bool letter_after_apostrophe = t.size() > 1 && t[0] == U'\'' &&
            ((t[1] >= U'a' && t[1] <= U'z') || (t[1] >= U'A' && t[1] <= U'Z') || t[1] >= 0xC0);
        if (all_in(t, openers)) {
            out += sep + t;
            sep.clear();
        } else if (all_in(t, closers)) {
            out += t;
            sep = U" ";
        } else if (i > 0 && letter_after_apostrophe) {
            out += t;
            sep = U" ";
        } else if (all_in(t, quotes)) {
            const u32 key = all_in(t, U"„“”") ? u32(U"\"") : t;
            int &n = seen[key];
            if (n % 2 == 0) {
                if (t == U"'" && i > 0 && toks[i - 1].back() == U's') {   // possessive: the boys '
                    out += t;
                    sep = U" ";
                } else {
                    out += sep + t;
                    sep.clear();
                    ++n;
                }
            } else {
                out += t;
                sep = U" ";
                ++n;
            }
        } else {
            out += sep + t;
            sep = U" ";
        }
    }
    u32 single;
    for (char32_t c : out) {
        if (c == U' ' && (single.empty() || single.back() == U' ')) continue;
        single.push_back(c);
    }
    return encode(trim(single));
}

std::string clean_up(std::string s) {
    static const char *rules[][2] = {{" .", "."}, {" ?", "?"}, {" !", "!"}, {" ,", ","}, {" ' ", "'"}, {" n't", "n't"},
                                     {" 'm", "'m"}, {" 's", "'s"}, {" 've", "'ve"}, {" 're", "'re"}};
    for (const auto &r : rules) replace_all(s, r[0], r[1]);
    return s;
}

// ---------------------------------------------------------------- ONNX Runtime

const OrtApi *g_ort = nullptr;

bool ok(OrtStatus *st, const char *what) {
    if (!st) return true;
    MT_LOG("tr_mt: %s: %s", what, g_ort->GetErrorMessage(st));
    g_ort->ReleaseStatus(st);
    return false;
}

OrtSession *open_session(OrtEnv *env, const std::string &graph, int threads) {
    OrtSessionOptions *opts = nullptr;
    if (!ok(g_ort->CreateSessionOptions(&opts), "session options")) return nullptr;
    g_ort->SetIntraOpNumThreads(opts, threads);
    g_ort->SetInterOpNumThreads(opts, 1);
    g_ort->SetSessionGraphOptimizationLevel(opts, ORT_ENABLE_ALL);
    // Worker threads sleep between operators instead of spinning: slightly slower,
    // but a spinning pool keeps several cores awake for nothing.
    g_ort->AddSessionConfigEntry(opts, "session.intra_op.allow_spinning", "0");
    OrtSession *session = nullptr;
    const bool created = ok(g_ort->CreateSessionFromArray(env, graph.data(), graph.size(), opts, &session), "session");
    g_ort->ReleaseSessionOptions(opts);
    return created ? session : nullptr;
}

std::vector<std::string> lines_of(const std::string &text) {
    std::vector<std::string> out;
    size_t at = 0;
    while (at < text.size()) {
        size_t end = text.find('\n', at);
        if (end == std::string::npos) end = text.size();
        out.push_back(text.substr(at, end - at));
        if (!out.back().empty() && out.back().back() == '\r') out.back().pop_back();
        at = end + 1;
    }
    return out;
}

// The model file is a plain container (tools/make_mt_pack.py): "BHMT0001", then for
// every part a 32-byte name, a 64-bit little-endian size and the bytes.
bool read_part(std::ifstream &in, std::string &name, std::string &data) {
    char head[40];
    if (!in.read(head, sizeof(head))) return false;
    name.assign(head, strnlen(head, 32));
    uint64_t size = 0;
    for (int i = 0; i < 8; ++i) size |= static_cast<uint64_t>(static_cast<unsigned char>(head[32 + i])) << (8 * i);
    if (size > (1ull << 31)) return false;
    data.resize(static_cast<size_t>(size));
    return static_cast<bool>(in.read(&data[0], static_cast<std::streamsize>(size)));
}

}  // namespace

struct tr_mt {
    OrtEnv *env = nullptr;
    OrtSession *encoder = nullptr;
    OrtSession *decoder = nullptr;
    OrtMemoryInfo *cpu = nullptr;
    sentencepiece::SentencePieceProcessor spm;
    std::vector<int32_t> source_ids;        // SentencePiece id -> model id
    std::vector<std::string> target_pieces; // model id -> piece
    std::mutex mutex;
    std::atomic<bool> cancel{false};
};

namespace {

OrtValue *tensor(tr_mt *m, void *data, size_t bytes, const std::vector<int64_t> &shape, ONNXTensorElementDataType type) {
    OrtValue *v = nullptr;
    ok(g_ort->CreateTensorWithDataAsOrtValue(m->cpu, data, bytes, shape.data(), shape.size(), type, &v), "tensor");
    return v;
}

// Source token ids for one sentence: language tags, pieces, end mark.
std::vector<int64_t> source_tokens(tr_mt *m, const std::string &prepared, const Lang &lang) {
    std::vector<int> pieces;
    m->spm.Encode(prepared, &pieces);
    std::vector<int64_t> ids = {lang.tag, kTagEnglish};
    for (int p : pieces) {
        if (ids.size() > kMaxSourceTokens) break;
        ids.push_back(p >= 0 && static_cast<size_t>(p) < m->source_ids.size() ? m->source_ids[static_cast<size_t>(p)] : kUnk);
    }
    ids.push_back(kEos);
    return ids;
}

// Greedy decoding. Returns false when the runtime failed; `out` holds the ids without the end mark.
bool generate(tr_mt *m, std::vector<int64_t> &ids, std::vector<int64_t> &out) {
    const int64_t S = static_cast<int64_t>(ids.size());
    std::vector<int64_t> mask(ids.size(), 1);
    const std::vector<int64_t> seq_shape = {1, S};
    OrtValue *in_ids = tensor(m, ids.data(), ids.size() * sizeof(int64_t), seq_shape, ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64);
    OrtValue *in_mask = tensor(m, mask.data(), mask.size() * sizeof(int64_t), seq_shape, ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64);

    OrtValue *cross[2] = {nullptr, nullptr};
    OrtValue *past[2] = {nullptr, nullptr};
    OrtValue *in_tok = nullptr;
    bool good = in_ids && in_mask;
    if (good) {
        const char *enc_in[] = {"input_ids", "attention_mask"};
        const char *enc_out[] = {"cross_k", "cross_v"};
        const OrtValue *inputs[] = {in_ids, in_mask};
        good = ok(g_ort->Run(m->encoder, nullptr, enc_in, inputs, 2, enc_out, 2, cross), "encoder");
    }

    float nothing = 0.0f;
    int64_t tok = kEos;   // the decoder starts from the end mark
    if (good) {
        const std::vector<int64_t> empty = {kLayers, 1, kHeads, 0, kHeadDim};
        past[0] = tensor(m, &nothing, 0, empty, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT);
        past[1] = tensor(m, &nothing, 0, empty, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT);
        in_tok = tensor(m, &tok, sizeof(tok), {1, 1}, ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64);
        good = past[0] && past[1] && in_tok;
    }

    const int max_new = static_cast<int>(std::min<int64_t>(256, 2 * S + 16));
    const int vocab = static_cast<int>(m->target_pieces.size());
    for (int step = 0; good && step < max_new && !m->cancel.load(); ++step) {
        const char *dec_in[] = {"input_ids", "attention_mask", "cross_k", "cross_v", "past_k", "past_v"};
        const char *dec_out[] = {"logits", "present_k", "present_v"};
        const OrtValue *inputs[] = {in_tok, in_mask, cross[0], cross[1], past[0], past[1]};
        OrtValue *outputs[3] = {nullptr, nullptr, nullptr};
        good = ok(g_ort->Run(m->decoder, nullptr, dec_in, inputs, 6, dec_out, 3, outputs), "decoder");
        if (!good) break;
        float *logits = nullptr;
        good = ok(g_ort->GetTensorMutableData(outputs[0], reinterpret_cast<void **>(&logits)), "logits");
        if (good) tok = std::max_element(logits, logits + vocab) - logits;
        g_ort->ReleaseValue(outputs[0]);
        g_ort->ReleaseValue(past[0]);
        g_ort->ReleaseValue(past[1]);
        past[0] = outputs[1];
        past[1] = outputs[2];
        if (!good || tok == kEos) break;
        out.push_back(tok);
    }

    for (OrtValue *v : {in_ids, in_mask, in_tok, cross[0], cross[1], past[0], past[1]}) {
        if (v) g_ort->ReleaseValue(v);
    }
    return good;
}

std::string pieces_to_text(tr_mt *m, const std::vector<int64_t> &ids) {
    std::string joined;
    for (int64_t id : ids) {
        if (id > kUnk && static_cast<size_t>(id) < m->target_pieces.size()) joined += m->target_pieces[static_cast<size_t>(id)];
    }
    replace_all(joined, "\xE2\x96\x81", " ");   // U+2581, SentencePiece's word-start mark
    return clean_up(encode(trim(decode(joined))));
}

char *copy(const std::string &s) {
    char *out = static_cast<char *>(std::malloc(s.size() + 1));
    if (out) std::memcpy(out, s.c_str(), s.size() + 1);
    return out;
}

}  // namespace

extern "C" {

tr_mt_config tr_mt_default_config(void) {
    tr_mt_config cfg = {};
    cfg.n_threads = 4;
    return cfg;
}

tr_mt *tr_mt_open(const tr_mt_config *cfg) {
    if (!cfg || !cfg->model_path) return nullptr;
    if (!g_ort) g_ort = OrtGetApiBase()->GetApi(ORT_API_VERSION);
    if (!g_ort) {
        MT_LOG("tr_mt: ONNX Runtime is older than the headers this was built with");
        return nullptr;
    }
    std::ifstream in(cfg->model_path, std::ios::binary);
    char magic[8] = {};
    if (!in.read(magic, 8) || std::memcmp(magic, "BHMT0001", 8) != 0) {
        MT_LOG("tr_mt: %s is not a translation model", cfg->model_path);
        return nullptr;
    }
    auto *m = new tr_mt();
    const int threads = cfg->n_threads > 0 ? cfg->n_threads : 4;
    bool good = ok(g_ort->CreateEnv(ORT_LOGGING_LEVEL_ERROR, "bhasha_mt", &m->env), "environment")
        && ok(g_ort->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &m->cpu), "memory info");
    bool tokenizer = false;
    std::string name, data;   // one part at a time, so the file is never in memory twice
    while (good && read_part(in, name, data)) {
        if (name == "encoder.int8.onnx") {
            good = (m->encoder = open_session(m->env, data, threads)) != nullptr;
        } else if (name == "decoder.int8.onnx") {
            good = (m->decoder = open_session(m->env, data, threads)) != nullptr;
        } else if (name == "model.SRC") {
            good = tokenizer = m->spm.LoadFromSerializedProto(data).ok();
        } else if (name == "src_spm_to_model_id.txt") {
            for (const std::string &l : lines_of(data)) m->source_ids.push_back(static_cast<int32_t>(std::atoi(l.c_str())));
        } else if (name == "tgt_id_to_piece.txt") {
            m->target_pieces = lines_of(data);
        }
    }
    if (good && !(m->encoder && m->decoder && tokenizer && !m->source_ids.empty() && !m->target_pieces.empty())) {
        MT_LOG("tr_mt: %s is incomplete", cfg->model_path);
        good = false;
    }
    if (!good) {
        tr_mt_close(m);
        return nullptr;
    }
    return m;
}

void tr_mt_close(tr_mt *m) {
    if (!m) return;
    if (g_ort) {
        if (m->encoder) g_ort->ReleaseSession(m->encoder);
        if (m->decoder) g_ort->ReleaseSession(m->decoder);
        if (m->cpu) g_ort->ReleaseMemoryInfo(m->cpu);
        if (m->env) g_ort->ReleaseEnv(m->env);
    }
    delete m;
}

int tr_mt_supports(const char *lang) { return find_lang(lang) ? 1 : 0; }

char *tr_mt_translate(tr_mt *m, const char *lang, const char *text, float *ms) {
    const Lang *l = find_lang(lang);
    if (!m || !l || !text) return nullptr;
    std::lock_guard<std::mutex> lock(m->mutex);
    m->cancel.store(false);
    const auto t0 = std::chrono::steady_clock::now();
    const std::string prepared = prepare_source(text, *l);
    if (prepared.empty()) return copy("");
    std::vector<int64_t> ids = source_tokens(m, prepared, *l), out;
    if (!generate(m, ids, out)) return nullptr;
    const std::string english = detokenize(pieces_to_text(m, out));
    if (ms) *ms = std::chrono::duration<float, std::milli>(std::chrono::steady_clock::now() - t0).count();
    return copy(english);
}

void tr_mt_cancel(tr_mt *m) {
    if (m) m->cancel.store(true);
}

void tr_mt_free(void *p) { std::free(p); }

/* Test hooks: the stages of the pipeline, one at a time. Results are freed with tr_mt_free(). */

char *tr_mt_debug_prepare(const char *lang, const char *text) {
    const Lang *l = find_lang(lang);
    return l && text ? copy(std::string(l->code == std::string("hi") ? "hin_Deva" : l->code == std::string("bn") ? "ben_Beng" : "kan_Knda")
                            + " eng_Latn " + prepare_source(text, *l)) : nullptr;
}

char *tr_mt_debug_detokenize(const char *text) { return text ? copy(detokenize(text)) : nullptr; }

int tr_mt_debug_tokens(tr_mt *m, const char *lang, const char *text, int64_t *out, int capacity) {
    const Lang *l = find_lang(lang);
    if (!m || !l || !text) return -1;
    const std::vector<int64_t> ids = source_tokens(m, prepare_source(text, *l), *l);
    for (size_t i = 0; i < ids.size() && static_cast<int>(i) < capacity; ++i) out[i] = ids[i];
    return static_cast<int>(ids.size());
}

int tr_mt_debug_generate(tr_mt *m, const char *lang, const char *text, int64_t *out, int capacity) {
    const Lang *l = find_lang(lang);
    if (!m || !l || !text) return -1;
    std::lock_guard<std::mutex> lock(m->mutex);
    m->cancel.store(false);
    std::vector<int64_t> ids = source_tokens(m, prepare_source(text, *l), *l), gen;
    if (!generate(m, ids, gen)) return -1;
    for (size_t i = 0; i < gen.size() && static_cast<int>(i) < capacity; ++i) out[i] = gen[i];
    return static_cast<int>(gen.size());
}

}  // extern "C"
