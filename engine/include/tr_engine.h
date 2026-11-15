/* SPDX-License-Identifier: AGPL-3.0-or-later */
/*
 * Bhasha on-device engine: portable C API.
 *
 * Two independent components, deliberately kept in separate translation units
 * (and, on Android, separate shared libraries) because whisper.cpp and
 * llama.cpp each bundle their own copy of ggml:
 *
 *   tr_asr_*  speech segmentation (Silero VAD) + recognition (whisper.cpp, and
 *             per-language IndicConformer models through sherpa-onnx)
 *   tr_llm_*  chat/translation LLM (llama.cpp, GGUF models)
 *
 * The API is plain C so it can be called from JNI (Android) and from Swift
 * through a bridging header (iOS) without any glue beyond type conversion.
 * All strings are UTF-8.
 */
#ifndef TR_ENGINE_H
#define TR_ENGINE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define TR_SAMPLE_RATE 16000

/* ------------------------------------------------------------------ ASR */

typedef struct tr_asr tr_asr;

typedef struct tr_asr_config {
    const char *model_path;      /* whisper ggml model; may be NULL when lang_model_pattern is set */
    const char *vad_model_path;  /* Silero ggml VAD model; NULL = energy-based fallback */
    int n_threads;               /* <= 0: pick automatically */
    int fast_encode;             /* 1: shrink the encoder window to the utterance length (faster, less accurate; off by default) */

    /* Streaming segmentation. Zero / negative values select the defaults. */
    float vad_threshold;         /* speech probability to open a segment (0.5) */
    int min_speech_ms;           /* discard segments with less voiced audio (250) */
    int min_silence_ms;          /* trailing silence that closes a segment (550) */
    int soft_max_ms;             /* past this, a 200 ms pause closes the segment (6000) */
    int hard_max_ms;             /* forced cut (12000) */
    int pad_ms;                  /* audio kept before the first voiced frame (250) */

    /* Per-language recognisers (NeMo CTC ONNX, e.g. AI4Bharat IndicConformer). The pattern
     * contains "{lang}", replaced by the language code: ".../indic-conformer-{lang}.int8.onnx".
     * A language whose file exists is transcribed with it; the others, speech-to-English
     * translation and language detection use the whisper model, loaded when first needed. */
    const char *lang_model_pattern;
    const char *lang_model_tokens;   /* tokens.txt shared by the per-language models */
} tr_asr_config;

typedef struct tr_asr_result {
    char *text;        /* recognised text, may be empty; owned by the result */
    char lang[8];      /* language actually used for decoding, e.g. "hi" */
    float audio_sec;   /* length of the decoded utterance */
    float decode_ms;   /* wall time spent recognising it */
} tr_asr_result;

tr_asr_config tr_asr_default_config(void);

/* Returns NULL on failure (model missing / corrupt). */
tr_asr *tr_asr_open(const tr_asr_config *cfg);
void tr_asr_close(tr_asr *asr);

/*
 * Streaming input: 16 kHz mono PCM. Cheap; safe to call from the audio thread
 * while another thread is inside tr_asr_next(). Returns the number of complete
 * utterances waiting to be decoded.
 */
int tr_asr_push_pcm16(tr_asr *asr, const int16_t *pcm, int n_samples);
int tr_asr_push_f32(tr_asr *asr, const float *pcm, int n_samples);

/* Close the utterance in progress (if any) so it becomes available to tr_asr_next(). */
int tr_asr_flush(tr_asr *asr);

/* Drop all buffered audio and queued utterances. */
void tr_asr_reset(tr_asr *asr);

/* Most recent speech probability (0..1) - handy for a level meter. */
float tr_asr_speech_prob(tr_asr *asr);

/*
 * Decode the oldest queued utterance.
 *   lang      "hi", "bn", "kn", ... to force a language (fastest);
 *             "auto" for unrestricted detection;
 *             "hi,bn,kn" to detect among a fixed set (one extra encoder pass).
 *   translate 0: transcribe in the spoken language, 1: translate to English.
 * Returns 1 when `out` was filled, 0 when the queue is empty, < 0 on error.
 * Release with tr_asr_result_free().
 */
int tr_asr_next(tr_asr *asr, const char *lang, int translate, tr_asr_result *out);

/* One-shot recognition of a complete clip (pronunciation practice, tests). */
int tr_asr_transcribe_pcm16(tr_asr *asr, const int16_t *pcm, int n_samples,
                            const char *lang, int translate, tr_asr_result *out);

void tr_asr_result_free(tr_asr_result *res);

/* Abort a decode in progress from another thread. */
void tr_asr_abort(tr_asr *asr);

/* ------------------------------------------------------------------ LLM */

typedef struct tr_llm tr_llm;

typedef struct tr_llm_config {
    const char *model_path;  /* GGUF model (required) */
    int n_ctx;               /* context window in tokens (<= 0: 4096) */
    int n_threads;           /* <= 0: pick automatically */
    int n_gpu_layers;        /* 0 = CPU only */
} tr_llm_config;

typedef struct tr_llm_message {
    const char *role;     /* "system", "user" or "assistant" */
    const char *content;
} tr_llm_message;

typedef struct tr_llm_stats {
    int prompt_tokens;    /* tokens in the prompt */
    int cached_tokens;    /* of those, reused from the previous call */
    int output_tokens;
    float prompt_ms;
    float output_ms;
} tr_llm_stats;

/* Called with complete UTF-8 sequences as they are generated. Return 0 to stop. */
typedef int (*tr_llm_token_cb)(const char *utf8, int n_bytes, void *user);

/*
 * One-time process initialisation. `backend_dir` is the directory holding the
 * dynamically loaded ggml CPU variants (Android: the app's nativeLibraryDir);
 * pass NULL when the backends are linked in statically (iOS).
 */
void tr_llm_global_init(const char *backend_dir);

tr_llm_config tr_llm_default_config(void);
tr_llm *tr_llm_open(const tr_llm_config *cfg);
void tr_llm_close(tr_llm *llm);

/*
 * Stateless chat completion. The KV cache is kept between calls and the
 * longest common token prefix is reused, so repeating the same system prompt
 * (or extending a conversation) only pays for the new tokens.
 *
 * Returns the full reply (caller frees with tr_free) or NULL on error.
 * temperature <= 0 selects greedy decoding.
 */
char *tr_llm_chat(tr_llm *llm, const tr_llm_message *messages, int n_messages,
                  int max_tokens, float temperature,
                  tr_llm_token_cb on_token, void *user, tr_llm_stats *stats);

/* Stop a generation in progress from another thread. */
void tr_llm_cancel(tr_llm *llm);

void tr_free(void *p);

#ifdef __cplusplus
}
#endif

#endif /* TR_ENGINE_H */
