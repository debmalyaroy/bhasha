// SPDX-License-Identifier: AGPL-3.0-or-later
// Feeds WAV files through the streaming ASR path exactly as the app does
// (100 ms chunks -> VAD segmentation -> whisper) and prints what comes out.
//
//   asr_test <whisper.bin|-> <vad.bin|-> <lang> <translate 0|1> <out.txt> file.wav...
//
// Per-language models: set BHASHA_LANG_MODELS to a path containing "{lang}" and
// BHASHA_LANG_TOKENS to their tokens.txt.
//
// Output goes to a UTF-8 text file because Windows consoles mangle Indic text.

#include "tr_engine.h"

#include <algorithm>
#include <cstdint>
#include <cstdlib>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

static bool read_wav_pcm16(const char *path, std::vector<int16_t> &out) {
    FILE *f = std::fopen(path, "rb");
    if (!f) return false;
    std::vector<unsigned char> data;
    unsigned char buf[65536];
    size_t n;
    while ((n = std::fread(buf, 1, sizeof(buf), f)) > 0) data.insert(data.end(), buf, buf + n);
    std::fclose(f);
    if (data.size() < 44 || std::memcmp(data.data(), "RIFF", 4) != 0) return false;
    size_t pos = 12;
    while (pos + 8 <= data.size()) {
        uint32_t size;
        std::memcpy(&size, &data[pos + 4], 4);
        if (std::memcmp(&data[pos], "data", 4) == 0) {
            size = static_cast<uint32_t>(std::min<size_t>(size, data.size() - pos - 8));
            out.resize(size / 2);
            std::memcpy(out.data(), &data[pos + 8], out.size() * 2);
            return true;
        }
        pos += 8 + size + (size & 1);
    }
    return false;
}

static int drain(tr_asr *asr, const char *lang, int translate, FILE *out, double *decode_ms, double *audio_s) {
    int count = 0;
    tr_asr_result res;
    while (tr_asr_next(asr, lang, translate, &res) == 1) {
        std::fprintf(out, "  [%s %.1fs audio, %.0f ms] %s\n", res.lang, res.audio_sec, res.decode_ms, res.text);
        std::printf("  segment: %.1f s audio decoded in %.0f ms (lang %s, %zu bytes of text)\n",
                    res.audio_sec, res.decode_ms, res.lang, std::strlen(res.text));
        *decode_ms += res.decode_ms;
        *audio_s += res.audio_sec;
        tr_asr_result_free(&res);
        ++count;
    }
    return count;
}

int main(int argc, char **argv) {
    if (argc < 7) {
        std::fprintf(stderr, "usage: %s <whisper.bin> <vad.bin|-> <lang> <translate 0|1> <out.txt> file.wav...\n", argv[0]);
        return 2;
    }
    tr_asr_config cfg = tr_asr_default_config();
    cfg.model_path = std::strcmp(argv[1], "-") == 0 ? nullptr : argv[1];
    cfg.lang_model_pattern = std::getenv("BHASHA_LANG_MODELS");
    cfg.lang_model_tokens = std::getenv("BHASHA_LANG_TOKENS");
    cfg.vad_model_path = std::strcmp(argv[2], "-") == 0 ? nullptr : argv[2];
    cfg.n_threads = 4;
    if (const char *fast = std::getenv("BHASHA_FAST")) cfg.fast_encode = std::atoi(fast);
    if (const char *soft = std::getenv("BHASHA_SOFT_MAX_MS")) cfg.soft_max_ms = std::atoi(soft);
    if (const char *hard = std::getenv("BHASHA_HARD_MAX_MS")) cfg.hard_max_ms = std::atoi(hard);
    const char *lang = argv[3];
    const int translate = std::atoi(argv[4]);

    tr_asr *asr = tr_asr_open(&cfg);
    if (!asr) {
        std::fprintf(stderr, "failed to open models\n");
        return 1;
    }
    FILE *out = std::fopen(argv[5], "wb");
    if (!out) return 1;

    double decode_ms = 0, audio_s = 0;
    int segments = 0;
    for (int i = 6; i < argc; ++i) {
        std::vector<int16_t> pcm;
        if (!read_wav_pcm16(argv[i], pcm)) {
            std::fprintf(stderr, "cannot read %s\n", argv[i]);
            continue;
        }
        std::printf("%s (%.1f s)\n", argv[i], pcm.size() / 16000.0);
        std::fprintf(out, "%s\n", argv[i]);
        tr_asr_reset(asr);
        const size_t chunk = 1600;
        for (size_t p = 0; p < pcm.size(); p += chunk) {
            const int n = static_cast<int>(std::min(chunk, pcm.size() - p));
            if (tr_asr_push_pcm16(asr, pcm.data() + p, n) > 0) {
                segments += drain(asr, lang, translate, out, &decode_ms, &audio_s);
            }
        }
        tr_asr_flush(asr);
        segments += drain(asr, lang, translate, out, &decode_ms, &audio_s);
    }
    std::printf("TOTAL: %d segments, %.1f s speech decoded in %.1f s (real-time factor %.2f)\n", segments, audio_s,
                decode_ms / 1000.0, audio_s > 0 ? decode_ms / 1000.0 / audio_s : 0.0);
    std::fclose(out);
    tr_asr_close(asr);
    return segments > 0 ? 0 : 1;
}
