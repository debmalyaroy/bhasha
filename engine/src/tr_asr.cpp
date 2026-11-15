// SPDX-License-Identifier: AGPL-3.0-or-later
// Speech segmentation + recognition: Silero VAD and Whisper through whisper.cpp,
// plus per-language CTC models (IndicConformer) through sherpa-onnx.

#include "tr_engine.h"

#include "whisper.h"

#ifdef TR_WITH_SHERPA
#include "sherpa-onnx/c-api/c-api.h"
#endif

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <map>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace {

constexpr int kRate = TR_SAMPLE_RATE;
constexpr int kWindow = 512;          // Silero frame at 16 kHz (32 ms)
constexpr int kMaxQueued = 6;         // utterances; older ones are dropped if decoding falls behind
constexpr int kMinDecodeSamples = kRate + kRate / 10;  // whisper rejects clips shorter than ~1 s

int ms_to_samples(int ms) { return static_cast<int>(static_cast<int64_t>(ms) * kRate / 1000); }

int default_threads() {
    const unsigned hc = std::thread::hardware_concurrency();
    return static_cast<int>(std::max(2u, std::min(4u, hc ? hc : 4u)));
}

void quiet_log(enum ggml_log_level level, const char *text, void *) {
    if (level == GGML_LOG_LEVEL_ERROR || level == GGML_LOG_LEVEL_WARN) {
        fputs(text, stderr);
    }
}

std::string trim(const std::string &s) {
    size_t b = 0, e = s.size();
    while (b < e && static_cast<unsigned char>(s[b]) <= ' ') ++b;
    while (e > b && static_cast<unsigned char>(s[e - 1]) <= ' ') --e;
    return s.substr(b, e - b);
}

std::vector<std::string> split_langs(const char *lang) {
    std::vector<std::string> out;
    std::string cur;
    for (const char *p = lang; p && *p; ++p) {
        if (*p == ',' || *p == ' ') {
            if (!cur.empty()) out.push_back(cur);
            cur.clear();
        } else {
            cur.push_back(*p);
        }
    }
    if (!cur.empty()) out.push_back(cur);
    return out;
}

}  // namespace

struct tr_asr {
    whisper_context *wctx = nullptr;    // loaded on first use when per-language models exist
    std::string whisper_path;
    bool whisper_failed = false;

    // Per-language recognisers, loaded on first use and kept (dec_mu).
    std::string lang_pattern, lang_tokens;
#ifdef TR_WITH_SHERPA
    std::map<std::string, const SherpaOnnxOfflineRecognizer *> lang_models;
#endif
    whisper_vad_context *vctx = nullptr;
    int n_threads = 4;
    bool fast_encode = true;

    float threshold = 0.5f;
    int min_speech = 0, min_silence = 0, soft_max = 0, hard_max = 0, pad = 0;

    // --- streaming segmentation state (seg_mu)
    std::mutex seg_mu;
    std::vector<float> pending;   // samples not yet forming a full VAD window
    std::vector<float> preroll;   // most recent non-speech audio, at most `pad` samples
    std::vector<float> current;   // utterance being recorded
    bool in_speech = false;
    int silence_run = 0;          // consecutive non-speech samples inside an utterance
    int voiced = 0;               // voiced samples inside the utterance
    std::deque<std::vector<float>> ready;
    float noise_floor = 1e-3f;    // energy fallback only
    // Automatic gain for the voice detector: a far-away speaker is 30-40 dB
    // quieter than one holding the phone, and the detector misses speech that quiet.
    float agc_peak = 0.0f;        // loudest recent window (RMS), decays within seconds
    float agc_floor = 0.0f;       // background noise level (RMS)
    std::vector<float> scaled;    // gained copy of the windows handed to the detector
    std::atomic<float> last_prob{0.0f};

    // --- decoding (dec_mu)
    std::mutex dec_mu;
    std::atomic<bool> abort_flag{false};
    std::vector<float> lang_probs;
    std::map<std::string, std::vector<whisper_token>> foreign_script;   // per language, built on first use
};

namespace {

// Speech probability for each complete window in `in`; falls back to an
// adaptive energy gate when no VAD model is loaded.
void window_probs(tr_asr *a, const float *in, int n_windows, std::vector<float> &probs) {
    probs.assign(n_windows, 0.0f);
    if (a->vctx && whisper_vad_detect_speech_no_reset(a->vctx, in, n_windows * kWindow)) {
        const int n = std::min(n_windows, whisper_vad_n_probs(a->vctx));
        const float *p = whisper_vad_probs(a->vctx);
        std::copy(p, p + n, probs.begin());
        return;
    }
    for (int w = 0; w < n_windows; ++w) {
        double acc = 0.0;
        const float *s = in + static_cast<size_t>(w) * kWindow;
        for (int i = 0; i < kWindow; ++i) acc += static_cast<double>(s[i]) * s[i];
        const float rms = static_cast<float>(std::sqrt(acc / kWindow));
        // Track the floor quickly downwards and slowly upwards.
        a->noise_floor = rms < a->noise_floor ? 0.7f * a->noise_floor + 0.3f * rms
                                              : 0.995f * a->noise_floor + 0.005f * rms;
        const float ratio = rms / std::max(a->noise_floor, 1e-4f);
        probs[w] = std::max(0.0f, std::min(1.0f, (ratio - 1.5f) / 3.0f));
    }
}

// The windows in `pending`, amplified so that the loudest recent speech sits at a
// level the voice detector is reliable at. The recorded audio itself is left
// untouched; each utterance is levelled separately before it is recognised.
const float *detector_input(tr_asr *a, int n_windows) {
    constexpr float kTarget = 0.06f, kMaxGain = 60.0f;
    const size_t total = static_cast<size_t>(n_windows) * kWindow;
    a->scaled.resize(total);
    for (int w = 0; w < n_windows; ++w) {
        const float *in = a->pending.data() + static_cast<size_t>(w) * kWindow;
        double acc = 0.0;
        for (int i = 0; i < kWindow; ++i) acc += static_cast<double>(in[i]) * in[i];
        const float rms = static_cast<float>(std::sqrt(acc / kWindow));
        // Background level: follows quiet windows quickly, creeps up slowly otherwise.
        if (a->agc_floor <= 0.0f || rms < a->agc_floor) {
            a->agc_floor = a->agc_floor <= 0.0f ? rms : 0.9f * a->agc_floor + 0.1f * rms;
        } else {
            a->agc_floor *= 1.0005f;
        }
        // Speech level: jumps up at once and halves in about two seconds, so a quiet
        // speaker answering a loud one is lifted in time.
        a->agc_peak = rms > a->agc_peak ? rms : a->agc_peak * 0.989f;
        // Never lift plain background noise to speech level.
        const float level = std::max({a->agc_peak, 6.0f * a->agc_floor, kTarget / kMaxGain});
        const float gain = std::max(1.0f, kTarget / level);
        float *out = a->scaled.data() + static_cast<size_t>(w) * kWindow;
        for (int i = 0; i < kWindow; ++i) out[i] = std::max(-1.0f, std::min(1.0f, in[i] * gain));
    }
    return a->scaled.data();
}

// Scales an utterance so its loud parts sit at a fixed level, whatever the
// distance to the speaker was. Returns the gain applied.
float level_utterance(std::vector<float> &pcm) {
    if (pcm.empty()) return 1.0f;
    std::vector<float> mag(pcm.size());
    for (size_t i = 0; i < pcm.size(); ++i) mag[i] = std::fabs(pcm[i]);
    const size_t k = mag.size() - 1 - mag.size() / 200;   // 99.5th percentile: ignores clicks
    std::nth_element(mag.begin(), mag.begin() + k, mag.end());
    const float peak = mag[k];
    if (peak < 1e-5f) return 1.0f;
    const float gain = std::min(0.3f / peak, 300.0f);
    for (float &v : pcm) v = std::max(-1.0f, std::min(1.0f, v * gain));
    return gain;
}

void finish_utterance(tr_asr *a) {
    if (a->in_speech && a->voiced >= a->min_speech) {
        // Keep a short tail so the last syllable is not clipped.
        const int keep_tail = std::min(a->silence_run, a->pad);
        const size_t drop = static_cast<size_t>(a->silence_run - keep_tail);
        if (drop < a->current.size()) a->current.resize(a->current.size() - drop);
        a->ready.push_back(std::move(a->current));
        while (static_cast<int>(a->ready.size()) > kMaxQueued) a->ready.pop_front();
    }
    a->current.clear();
    a->in_speech = false;
    a->silence_run = 0;
    a->voiced = 0;
}

void consume_window(tr_asr *a, const float *w, float prob) {
    if (!a->in_speech) {
        if (prob >= a->threshold) {
            a->in_speech = true;
            a->current = a->preroll;
            a->preroll.clear();
            a->current.insert(a->current.end(), w, w + kWindow);
            a->voiced = kWindow;
            a->silence_run = 0;
        } else {
            a->preroll.insert(a->preroll.end(), w, w + kWindow);
            if (static_cast<int>(a->preroll.size()) > a->pad) {
                a->preroll.erase(a->preroll.begin(),
                                 a->preroll.begin() + (a->preroll.size() - a->pad));
            }
        }
        return;
    }

    a->current.insert(a->current.end(), w, w + kWindow);
    // Hysteresis: a slightly lower bar keeps an open segment going.
    if (prob >= a->threshold - 0.15f) {
        a->voiced += kWindow;
        a->silence_run = 0;
    } else {
        a->silence_run += kWindow;
    }

    const int len = static_cast<int>(a->current.size());
    const int need_silence = len >= a->soft_max ? ms_to_samples(200) : a->min_silence;
    if (a->silence_run >= need_silence || len >= a->hard_max) {
        finish_utterance(a);
    }
}

int push(tr_asr *a, const float *pcm, int n) {
    if (!a || n <= 0) return 0;
    std::lock_guard<std::mutex> lock(a->seg_mu);
    a->pending.insert(a->pending.end(), pcm, pcm + n);
    const int n_windows = static_cast<int>(a->pending.size() / kWindow);
    if (n_windows > 0) {
        std::vector<float> probs;
        window_probs(a, detector_input(a, n_windows), n_windows, probs);
        for (int w = 0; w < n_windows; ++w) {
            consume_window(a, a->pending.data() + static_cast<size_t>(w) * kWindow, probs[w]);
        }
        a->last_prob.store(probs.back());
        a->pending.erase(a->pending.begin(), a->pending.begin() + static_cast<size_t>(n_windows) * kWindow);
    }
    return static_cast<int>(a->ready.size());
}

// Pick the most likely language among `candidates` (all languages when empty).
// Costs one full encoder pass.
std::string detect_language(tr_asr *a, const float *pcm, int n, const std::vector<std::string> &candidates) {
    if (whisper_pcm_to_mel(a->wctx, pcm, n, a->n_threads) != 0) return "";
    if (whisper_encode(a->wctx, 0, a->n_threads) != 0) return "";
    a->lang_probs.assign(static_cast<size_t>(whisper_lang_max_id()) + 1, 0.0f);
    const int best_any = whisper_lang_auto_detect(a->wctx, 0, a->n_threads, a->lang_probs.data());
    if (best_any < 0) return "";
    if (candidates.empty()) return whisper_lang_str(best_any);

    std::string best;
    float best_p = -1.0f;
    for (const auto &c : candidates) {
        const int id = whisper_lang_id(c.c_str());
        if (id < 0) continue;
        if (a->lang_probs[id] > best_p) {
            best_p = a->lang_probs[id];
            best = c;
        }
    }
    return best;
}

// Second UTF-8 byte range (after 0xE0) of the script a language is written in.
struct ScriptRange { const char *lang; unsigned char lo, hi; };
const ScriptRange kScripts[] = {
    {"hi", 0xA4, 0xA5}, {"mr", 0xA4, 0xA5}, {"ne", 0xA4, 0xA5},   // Devanagari U+0900
    {"bn", 0xA6, 0xA7}, {"as", 0xA6, 0xA7},                       // Bengali    U+0980
    {"pa", 0xA8, 0xA9}, {"gu", 0xAA, 0xAB},                       // Gurmukhi, Gujarati
    {"ta", 0xAE, 0xAF}, {"te", 0xB0, 0xB1},                       // Tamil, Telugu
    {"kn", 0xB2, 0xB3}, {"ml", 0xB4, 0xB5},                       // Kannada, Malayalam
};

// Text tokens that spell another Indic script, or Arabic script, than `lang`
// is written in. Whisper, told the language, still sometimes writes Bengali or
// Kannada speech in Devanagari and Hindi in Urdu script; masking these tokens
// stops that. Latin letters are deliberately left alone: forbidding them as
// well was tried and made Bengali worse (the decoder fell into repetition
// loops where it would otherwise have romanised a word).
// Empty for languages outside the table.
const std::vector<whisper_token> &foreign_script_tokens(tr_asr *a, const std::string &lang) {
    auto it = a->foreign_script.find(lang);
    if (it != a->foreign_script.end()) return it->second;
    std::vector<whisper_token> &banned = a->foreign_script[lang];
    const ScriptRange *own = nullptr;
    for (const auto &r : kScripts) if (lang == r.lang) own = &r;
    if (!own) return banned;
    const int n_vocab = whisper_n_vocab(a->wctx);
    const whisper_token first_special = whisper_token_eot(a->wctx);
    for (whisper_token id = 0; id < n_vocab && id < first_special; ++id) {
        const char *t = whisper_token_to_str(a->wctx, id);
        if (!t) continue;
        const auto *b = reinterpret_cast<const unsigned char *>(t);
        bool foreign = false;
        for (size_t i = 0; b[i] && !foreign; ++i) {
            if (b[i] == 0xE0 && b[i + 1] >= 0xA4 && b[i + 1] <= 0xB7) {
                const bool danda = b[i + 1] == 0xA5 && (b[i + 2] == 0xA4 || b[i + 2] == 0xA5);   // shared full stop
                foreign = !danda && (b[i + 1] < own->lo || b[i + 1] > own->hi);
            } else if (b[i] >= 0xD8 && b[i] <= 0xDB) {
                foreign = true;   // Arabic script, U+0600..U+06FF
            }
        }
        if (foreign) banned.push_back(id);
    }
    return banned;
}

bool load_whisper(tr_asr *a) {
    if (a->wctx) return true;
    if (a->whisper_failed || a->whisper_path.empty()) return false;
    whisper_context_params wp = whisper_context_default_params();
    wp.use_gpu = false;
    a->wctx = whisper_init_from_file_with_params(a->whisper_path.c_str(), wp);
    a->whisper_failed = a->wctx == nullptr;
    return a->wctx != nullptr;
}

std::string lang_model_path(const tr_asr *a, const std::string &lang) {
    const size_t at = a->lang_pattern.find("{lang}");
    if (at == std::string::npos || lang.empty()) return "";
    std::string path = a->lang_pattern;
    path.replace(at, 6, lang);
    return path;
}

bool file_exists(const std::string &path) {
    if (path.empty()) return false;
    FILE *f = std::fopen(path.c_str(), "rb");
    if (f) std::fclose(f);
    return f != nullptr;
}

bool has_lang_model(const tr_asr *a, const std::string &lang) {
#ifdef TR_WITH_SHERPA
    return a->lang_models.count(lang) > 0 || file_exists(lang_model_path(a, lang));
#else
    (void) a; (void) lang;
    return false;
#endif
}

#ifdef TR_WITH_SHERPA
const SherpaOnnxOfflineRecognizer *lang_model(tr_asr *a, const std::string &lang) {
    auto it = a->lang_models.find(lang);
    if (it != a->lang_models.end()) return it->second;
    const std::string path = lang_model_path(a, lang);
    if (!file_exists(path)) return nullptr;
    SherpaOnnxOfflineRecognizerConfig c;
    std::memset(&c, 0, sizeof(c));
    c.feat_config.sample_rate = kRate;
    c.feat_config.feature_dim = 80;
    c.model_config.nemo_ctc.model = path.c_str();
    c.model_config.tokens = a->lang_tokens.c_str();
    c.model_config.num_threads = a->n_threads;
    c.model_config.provider = "cpu";
    c.decoding_method = "greedy_search";
    const SherpaOnnxOfflineRecognizer *r = SherpaOnnxCreateOfflineRecognizer(&c);
    if (r) a->lang_models[lang] = r;
    return r;
}

// Returns false when the model for `lang` cannot be loaded.
bool decode_lang_model(tr_asr *a, const std::string &lang, const float *pcm, int n, std::string &text) {
    const SherpaOnnxOfflineRecognizer *r = lang_model(a, lang);
    if (!r) return false;
    const SherpaOnnxOfflineStream *s = SherpaOnnxCreateOfflineStream(r);
    if (!s) return false;
    // These models misread an utterance that begins or ends right at a
    // syllable (the first words come out as noise), which is how the VAD cuts
    // them. A lead-in and tail of faint noise restores them; measured on
    // FLEURS clips, 0.6 s in front is enough and digital silence works less well.
    // The speech is levelled first, so the padding stays faint next to it even
    // when the speaker was far from the phone.
    std::vector<float> speech(pcm, pcm + n);
    level_utterance(speech);
    const int lead = ms_to_samples(600), tail = ms_to_samples(300);
    std::vector<float> padded(static_cast<size_t>(lead + n + tail));
    uint32_t seed = 0x9E3779B9u;
    for (size_t i = 0; i < padded.size(); ++i) {
        seed = seed * 1664525u + 1013904223u;
        padded[i] = (static_cast<float>(seed >> 8) / 8388608.0f - 1.0f) * 0.002f;
    }
    std::copy(speech.begin(), speech.end(), padded.begin() + lead);
    SherpaOnnxAcceptWaveformOffline(s, kRate, padded.data(), static_cast<int32_t>(padded.size()));
    SherpaOnnxDecodeOfflineStream(r, s);
    const SherpaOnnxOfflineRecognizerResult *res = SherpaOnnxGetOfflineStreamResult(s);
    text = res && res->text ? trim(res->text) : "";
    if (res) SherpaOnnxDestroyOfflineRecognizerResult(res);
    SherpaOnnxDestroyOfflineStream(s);
    return true;
}
#endif

int finish(tr_asr_result *out, const std::string &text, const std::string &lang, int n_in,
           std::chrono::steady_clock::time_point t0) {
    std::snprintf(out->lang, sizeof(out->lang), "%s", lang.c_str());
    out->text = static_cast<char *>(std::malloc(text.size() + 1));
    if (!out->text) return -3;
    std::memcpy(out->text, text.c_str(), text.size() + 1);
    out->audio_sec = static_cast<float>(n_in) / kRate;
    out->decode_ms = std::chrono::duration<float, std::milli>(std::chrono::steady_clock::now() - t0).count();
    return 1;
}

int decode(tr_asr *a, const float *pcm_in, int n_in, const char *lang, int translate, tr_asr_result *out) {
    if (!a || !out) return -1;
    std::memset(out, 0, sizeof(*out));
    std::lock_guard<std::mutex> lock(a->dec_mu);
    a->abort_flag.store(false);

    const auto t0 = std::chrono::steady_clock::now();

    // A language with its own model is transcribed by it; whisper is only
    // needed to tell languages apart, to translate, and for everything else.
    {
        std::string one = (lang && *lang) ? lang : "";
        const auto candidates = split_langs(one.c_str());
        bool all_have_models = !candidates.empty();
        for (const auto &c : candidates) all_have_models = all_have_models && has_lang_model(a, c);
        if (!translate && all_have_models) {
            if (candidates.size() > 1) {
                one.clear();
                if (load_whisper(a)) {
                    std::vector<float> padded(std::max(n_in, kMinDecodeSamples), 0.0f);
                    std::copy(pcm_in, pcm_in + n_in, padded.begin());
                    one = detect_language(a, padded.data(), static_cast<int>(padded.size()), candidates);
                }
                if (one.empty()) one = candidates.front();
            }
#ifdef TR_WITH_SHERPA
            std::string text;
            if (decode_lang_model(a, one, pcm_in, n_in, text)) return finish(out, text, one, n_in, t0);
#endif
        }
    }
    if (!load_whisper(a)) return -2;

    // whisper refuses very short clips; pad with silence.
    std::vector<float> padded;
    const float *pcm = pcm_in;
    int n = n_in;
    if (n < kMinDecodeSamples) {
        padded.assign(kMinDecodeSamples, 0.0f);
        std::copy(pcm_in, pcm_in + n_in, padded.begin());
        pcm = padded.data();
        n = kMinDecodeSamples;
    }

    std::string use_lang = (lang && *lang) ? lang : "auto";
    const bool multilingual = whisper_is_multilingual(a->wctx) != 0;
    if (!multilingual) {
        use_lang = "en";
    } else if (use_lang.find(',') != std::string::npos) {
        const auto candidates = split_langs(use_lang.c_str());
        const std::string picked = detect_language(a, pcm, n, candidates);
        use_lang = picked.empty() ? candidates.front() : picked;
#ifdef TR_WITH_SHERPA
        // Only some of the candidates have their own model; use it if that is the one spoken.
        std::string own_text;
        if (!translate && has_lang_model(a, use_lang) && decode_lang_model(a, use_lang, pcm_in, n_in, own_text)) {
            return finish(out, own_text, use_lang, n_in, t0);
        }
#endif
    }

    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = a->n_threads;
    p.translate = translate != 0;
    p.language = use_lang.c_str();
    p.detect_language = false;
    p.no_context = true;        // utterances are independent; avoids runaway repetition
    p.no_timestamps = true;
    p.single_segment = true;
    p.print_special = false;
    p.print_progress = false;
    p.print_realtime = false;
    p.print_timestamps = false;
    p.suppress_blank = true;
    p.suppress_nst = true;
    // A doubtful sentence is retried at temperatures 0.4 and 0.8 only. The
    // default (five retries) multiplies the delay when the audio is hard; no
    // retries at all leaves the decoder stuck in the occasional repetition loop.
    p.temperature_inc = 0.4f;
    if (a->fast_encode) {
        // 50 encoder frames per second of audio, plus headroom. A shorter
        // window makes the encoder (the dominant cost) proportionally faster.
        const int frames = n / 320 + 96;
        p.audio_ctx = std::max(384, std::min(1500, frames));
    }
    p.abort_callback = [](void *user) { return static_cast<tr_asr *>(user)->abort_flag.load(); };
    p.abort_callback_user_data = a;
    if (!p.translate && multilingual) {
        const auto &banned = foreign_script_tokens(a, use_lang);
        if (!banned.empty()) {
            p.logits_filter_callback = [](whisper_context *, whisper_state *, const whisper_token_data *, int,
                                          float *logits, void *user) {
                for (whisper_token id : *static_cast<const std::vector<whisper_token> *>(user)) logits[id] = -INFINITY;
            };
            p.logits_filter_callback_user_data = const_cast<std::vector<whisper_token> *>(&banned);
        }
    }

    if (whisper_full(a->wctx, p, pcm, n) != 0) {
        return a->abort_flag.load() ? 0 : -2;
    }

    std::string text;
    const int n_seg = whisper_full_n_segments(a->wctx);
    bool silent = n_seg > 0;
    for (int i = 0; i < n_seg; ++i) {
        if (whisper_full_get_segment_no_speech_prob(a->wctx, i) < 0.8f) silent = false;
        const char *seg = whisper_full_get_segment_text(a->wctx, i);
        if (seg) text += seg;
    }
    if (silent) text.clear();
    text = trim(text);

    const int lang_id = whisper_full_lang_id(a->wctx);
    const char *lang_str = lang_id >= 0 ? whisper_lang_str(lang_id) : use_lang.c_str();
    return finish(out, text, lang_str ? lang_str : "", n_in, t0);
}

}  // namespace

extern "C" {

tr_asr_config tr_asr_default_config(void) {
    tr_asr_config c;
    std::memset(&c, 0, sizeof(c));
    c.fast_encode = 0;   // shrinking the window costs large-v3-turbo a lot of accuracy
    return c;
}

tr_asr *tr_asr_open(const tr_asr_config *cfg) {
    const bool has_whisper = cfg && cfg->model_path && *cfg->model_path;
    const bool has_lang_models = cfg && cfg->lang_model_pattern && *cfg->lang_model_pattern &&
                                 cfg->lang_model_tokens && *cfg->lang_model_tokens;
    if (!has_whisper && !has_lang_models) return nullptr;
    whisper_log_set(quiet_log, nullptr);

    auto *a = new tr_asr();
    a->n_threads = cfg->n_threads > 0 ? cfg->n_threads : default_threads();
    a->fast_encode = cfg->fast_encode != 0;
    a->threshold = cfg->vad_threshold > 0.0f ? cfg->vad_threshold : 0.5f;
    a->min_speech = ms_to_samples(cfg->min_speech_ms > 0 ? cfg->min_speech_ms : 250);
    a->min_silence = ms_to_samples(cfg->min_silence_ms > 0 ? cfg->min_silence_ms : 550);
    a->soft_max = ms_to_samples(cfg->soft_max_ms > 0 ? cfg->soft_max_ms : 10000);
    a->hard_max = ms_to_samples(cfg->hard_max_ms > 0 ? cfg->hard_max_ms : 18000);
    a->pad = ms_to_samples(cfg->pad_ms > 0 ? cfg->pad_ms : 250);

    if (has_whisper) a->whisper_path = cfg->model_path;
    if (has_lang_models) {
        a->lang_pattern = cfg->lang_model_pattern;
        a->lang_tokens = cfg->lang_model_tokens;
    }
    // With per-language models whisper may never be needed, so it is loaded on
    // first use; without them it is the only recogniser and must load now.
    if (!has_lang_models && !load_whisper(a)) {
        delete a;
        return nullptr;
    }

    if (cfg->vad_model_path && *cfg->vad_model_path) {
        whisper_vad_context_params vp = whisper_vad_default_context_params();
        vp.n_threads = 1;   // the model is tiny; one thread avoids contending with the decoder
        vp.use_gpu = false;
        a->vctx = whisper_vad_init_from_file_with_params(cfg->vad_model_path, vp);
        // A missing VAD model is not fatal: the energy gate takes over.
    }
    return a;
}

void tr_asr_close(tr_asr *a) {
    if (!a) return;
    a->abort_flag.store(true);
    {
        std::lock_guard<std::mutex> l1(a->dec_mu);
        std::lock_guard<std::mutex> l2(a->seg_mu);
        if (a->vctx) whisper_vad_free(a->vctx);
        if (a->wctx) whisper_free(a->wctx);
#ifdef TR_WITH_SHERPA
        for (auto &m : a->lang_models) SherpaOnnxDestroyOfflineRecognizer(m.second);
        a->lang_models.clear();
#endif
        a->vctx = nullptr;
        a->wctx = nullptr;
    }
    delete a;
}

int tr_asr_push_f32(tr_asr *a, const float *pcm, int n) { return push(a, pcm, n); }

int tr_asr_push_pcm16(tr_asr *a, const int16_t *pcm, int n) {
    if (!a || n <= 0) return 0;
    std::vector<float> f(static_cast<size_t>(n));
    for (int i = 0; i < n; ++i) f[i] = static_cast<float>(pcm[i]) / 32768.0f;
    return push(a, f.data(), n);
}

int tr_asr_flush(tr_asr *a) {
    if (!a) return 0;
    std::lock_guard<std::mutex> lock(a->seg_mu);
    a->pending.clear();
    finish_utterance(a);
    return static_cast<int>(a->ready.size());
}

void tr_asr_reset(tr_asr *a) {
    if (!a) return;
    std::lock_guard<std::mutex> lock(a->seg_mu);
    a->pending.clear();
    a->preroll.clear();
    a->current.clear();
    a->ready.clear();
    a->in_speech = false;
    a->silence_run = 0;
    a->voiced = 0;
    a->last_prob.store(0.0f);
    a->agc_peak = 0.0f;
    a->agc_floor = 0.0f;
    if (a->vctx) whisper_vad_reset_state(a->vctx);
}

float tr_asr_speech_prob(tr_asr *a) { return a ? a->last_prob.load() : 0.0f; }

int tr_asr_next(tr_asr *a, const char *lang, int translate, tr_asr_result *out) {
    if (!a || !out) return -1;
    std::vector<float> pcm;
    {
        std::lock_guard<std::mutex> lock(a->seg_mu);
        if (a->ready.empty()) return 0;
        pcm = std::move(a->ready.front());
        a->ready.pop_front();
    }
    return decode(a, pcm.data(), static_cast<int>(pcm.size()), lang, translate, out);
}

int tr_asr_partial(tr_asr *a, const char *lang, tr_asr_result *out) {
    if (!a || !out) return -1;
    std::memset(out, 0, sizeof(*out));
#ifdef TR_WITH_SHERPA
    const std::string one = lang ? lang : "";
    if (one.empty() || one.find(',') != std::string::npos || !has_lang_model(a, one)) return 0;
    std::vector<float> pcm;
    {
        std::lock_guard<std::mutex> lock(a->seg_mu);
        if (!a->in_speech || a->voiced < kRate / 2) return 0;
        pcm = a->current;
    }
    std::lock_guard<std::mutex> lock(a->dec_mu);
    const auto t0 = std::chrono::steady_clock::now();
    std::string text;
    const int n = static_cast<int>(pcm.size());
    if (!decode_lang_model(a, one, pcm.data(), n, text)) return 0;
    return finish(out, text, one, n, t0);
#else
    (void) lang;
    return 0;
#endif
}

int tr_asr_transcribe_pcm16(tr_asr *a, const int16_t *pcm, int n, const char *lang, int translate,
                            tr_asr_result *out) {
    if (!a || !pcm || n <= 0) return -1;
    std::vector<float> f(static_cast<size_t>(n));
    for (int i = 0; i < n; ++i) f[i] = static_cast<float>(pcm[i]) / 32768.0f;
    return decode(a, f.data(), n, lang, translate, out);
}

void tr_asr_result_free(tr_asr_result *res) {
    if (!res) return;
    std::free(res->text);
    res->text = nullptr;
}

void tr_asr_abort(tr_asr *a) {
    if (a) a->abort_flag.store(true);
}

}  // extern "C"
