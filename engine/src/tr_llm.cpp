// SPDX-License-Identifier: AGPL-3.0-or-later
// Chat / translation LLM on top of llama.cpp.

#include "tr_engine.h"

#include "ggml-backend.h"
#include "llama.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace {

enum class ChatFormat { Gemma4, Gemma, Template };

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

// Number of leading bytes of `s` that form complete UTF-8 sequences.
size_t utf8_complete_prefix(const std::string &s) {
    const size_t n = s.size();
    size_t i = n;
    // Walk back over at most 3 continuation bytes to the last lead byte.
    int back = 0;
    while (i > 0 && back < 4) {
        const unsigned char c = static_cast<unsigned char>(s[i - 1]);
        --i;
        ++back;
        if ((c & 0xC0) != 0x80) {
            int need = 1;
            if ((c & 0xE0) == 0xC0) need = 2;
            else if ((c & 0xF0) == 0xE0) need = 3;
            else if ((c & 0xF8) == 0xF0) need = 4;
            return (back >= need) ? n : i;
        }
    }
    return n;
}

double ms_since(std::chrono::steady_clock::time_point t0) {
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
}

}  // namespace

struct tr_llm {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    llama_batch_ext *batch = nullptr;
    int n_ctx = 0;
    int n_batch = 512;
    ChatFormat format = ChatFormat::Template;
    std::string tmpl;
    std::vector<llama_token> cached;   // tokens currently held in the KV cache (sequence 0)
    std::mutex mu;
    std::atomic<bool> cancel{false};
};

namespace {

std::string format_gemma4(const tr_llm_message *m, int n) {
    std::string out;
    int i = 0;
    if (n > 0 && std::strcmp(m[0].role, "system") == 0) {
        out += "<|turn>system\n" + trim(m[0].content) + "<turn|>\n";
        i = 1;
    }
    for (; i < n; ++i) {
        const bool model = std::strcmp(m[i].role, "assistant") == 0 || std::strcmp(m[i].role, "model") == 0;
        out += model ? "<|turn>model\n" : "<|turn>user\n";
        out += trim(m[i].content);
        out += "<turn|>\n";
    }
    // Thinking stays off unless a <|think|> marker is put in the system turn.
    out += "<|turn>model\n";
    return out;
}

std::string format_gemma(const tr_llm_message *m, int n) {
    std::string out, system;
    for (int i = 0; i < n; ++i) {
        if (std::strcmp(m[i].role, "system") == 0) {
            system = trim(m[i].content);
            continue;
        }
        const bool model = std::strcmp(m[i].role, "assistant") == 0 || std::strcmp(m[i].role, "model") == 0;
        out += model ? "<start_of_turn>model\n" : "<start_of_turn>user\n";
        if (!model && !system.empty()) {   // no system role: fold it into the first user turn
            out += system + "\n\n";
            system.clear();
        }
        out += trim(m[i].content);
        out += "<end_of_turn>\n";
    }
    out += "<start_of_turn>model\n";
    return out;
}

std::string format_template(const tr_llm *llm, const tr_llm_message *m, int n) {
    std::vector<llama_chat_message> msgs(static_cast<size_t>(n));
    size_t chars = 256;
    for (int i = 0; i < n; ++i) {
        msgs[i].role = m[i].role;
        msgs[i].content = m[i].content;
        chars += std::strlen(m[i].content) + 64;
    }
    const char *tmpl = llm->tmpl.empty() ? "chatml" : llm->tmpl.c_str();
    std::vector<char> buf(chars * 2);
    int len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), static_cast<int32_t>(buf.size()));
    if (len > static_cast<int>(buf.size())) {
        buf.resize(static_cast<size_t>(len) + 1);
        len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), static_cast<int32_t>(buf.size()));
    }
    if (len < 0) {
        len = llama_chat_apply_template("chatml", msgs.data(), msgs.size(), true, buf.data(), static_cast<int32_t>(buf.size()));
    }
    return len > 0 ? std::string(buf.data(), static_cast<size_t>(len)) : std::string();
}

// Evaluate `n` tokens starting at position `pos`. Logits are produced for the last one.
bool eval(tr_llm *llm, const llama_token *tokens, int n, int pos) {
    llama_batch_ext_clear(llm->batch);
    for (int i = 0; i < n; ++i) {
        const int32_t idx = llama_batch_ext_add_token(llm->batch, 0, tokens[i]);
        if (idx < 0) return false;
        const llama_pos p = pos + i;
        llama_batch_ext_set_pos(llm->batch, idx, &p);
    }
    llama_batch_ext_set_output_logits(llm->batch, n - 1, true);
    return llama_process(llm->ctx, LLAMA_PROCESS_TYPE_DECODE, llm->batch) == 0;
}

}  // namespace

extern "C" {

void tr_llm_global_init(const char *backend_dir) {
    static std::once_flag once;
    std::string dir = backend_dir ? backend_dir : "";
    std::call_once(once, [dir]() {
        llama_log_set(quiet_log, nullptr);
        if (!dir.empty()) ggml_backend_load_all_from_path(dir.c_str());
        llama_backend_init();
    });
}

tr_llm_config tr_llm_default_config(void) {
    tr_llm_config c;
    std::memset(&c, 0, sizeof(c));
    c.n_ctx = 4096;
    return c;
}

tr_llm *tr_llm_open(const tr_llm_config *cfg) {
    if (!cfg || !cfg->model_path) return nullptr;
    tr_llm_global_init(nullptr);   // no-op if the host already initialised with a backend dir

    auto *llm = new tr_llm();

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = cfg->n_gpu_layers;
    llm->model = llama_model_load_from_file(cfg->model_path, mp);
    if (!llm->model) {
        delete llm;
        return nullptr;
    }
    llm->vocab = llama_model_get_vocab(llm->model);

    const int threads = cfg->n_threads > 0 ? cfg->n_threads : default_threads();
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = static_cast<uint32_t>(cfg->n_ctx > 0 ? cfg->n_ctx : 4096);
    cp.n_batch = static_cast<uint32_t>(llm->n_batch);
    cp.n_threads = threads;
    cp.n_threads_batch = threads;
    cp.no_perf = true;
    llm->ctx = llama_init_from_model(llm->model, cp);
    if (!llm->ctx) {
        llama_model_free(llm->model);
        delete llm;
        return nullptr;
    }
    llm->n_ctx = static_cast<int>(llama_n_ctx(llm->ctx));
    llm->n_batch = static_cast<int>(llama_n_batch(llm->ctx));
    llm->batch = llama_batch_ext_init(llm->ctx);

    const char *tmpl = llama_model_chat_template(llm->model, nullptr);
    llm->tmpl = tmpl ? tmpl : "";
    if (llm->tmpl.find("<|turn>") != std::string::npos) {
        llm->format = ChatFormat::Gemma4;
    } else if (llm->tmpl.find("<start_of_turn>") != std::string::npos) {
        llm->format = ChatFormat::Gemma;
    }
    return llm;
}

void tr_llm_close(tr_llm *llm) {
    if (!llm) return;
    llm->cancel.store(true);
    {
        std::lock_guard<std::mutex> lock(llm->mu);
        if (llm->batch) llama_batch_ext_free(llm->batch);
        if (llm->ctx) llama_free(llm->ctx);
        if (llm->model) llama_model_free(llm->model);
    }
    delete llm;
}

char *tr_llm_chat(tr_llm *llm, const tr_llm_message *messages, int n_messages, int max_tokens,
                  float temperature, tr_llm_token_cb on_token, void *user, tr_llm_stats *stats) {
    if (!llm || !messages || n_messages <= 0) return nullptr;
    std::lock_guard<std::mutex> lock(llm->mu);
    llm->cancel.store(false);
    if (stats) std::memset(stats, 0, sizeof(*stats));
    if (max_tokens <= 0) max_tokens = 256;

    std::string prompt;
    switch (llm->format) {
        case ChatFormat::Gemma4: prompt = format_gemma4(messages, n_messages); break;
        case ChatFormat::Gemma: prompt = format_gemma(messages, n_messages); break;
        case ChatFormat::Template: prompt = format_template(llm, messages, n_messages); break;
    }
    if (prompt.empty()) return nullptr;

    // Tokenise (adds BOS when the model wants one; control tokens are parsed).
    int n_prompt = -llama_tokenize(llm->vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()), nullptr, 0, true, true);
    if (n_prompt <= 0) return nullptr;
    std::vector<llama_token> tokens(static_cast<size_t>(n_prompt));
    if (llama_tokenize(llm->vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()), tokens.data(),
                       static_cast<int32_t>(tokens.size()), true, true) < 0) {
        return nullptr;
    }
    if (n_prompt + 8 >= llm->n_ctx) return nullptr;   // caller must shorten the conversation
    max_tokens = std::min(max_tokens, llm->n_ctx - n_prompt - 1);

    // Reuse the longest common prefix already in the KV cache.
    size_t keep = 0;
    while (keep < llm->cached.size() && keep < tokens.size() && llm->cached[keep] == tokens[keep]) ++keep;
    if (keep == tokens.size()) --keep;   // always re-evaluate at least one token to get logits
    llama_memory_t mem = llama_get_memory(llm->ctx);
    if (keep < llm->cached.size()) {
        if (!llama_memory_seq_rm(mem, 0, static_cast<llama_pos>(keep), -1)) {
            llama_memory_clear(mem, true);
            keep = 0;
        }
    }
    llm->cached.resize(keep);

    const auto t_prompt = std::chrono::steady_clock::now();
    for (size_t i = keep; i < tokens.size();) {
        if (llm->cancel.load()) return nullptr;
        const int n = static_cast<int>(std::min(tokens.size() - i, static_cast<size_t>(llm->n_batch)));
        if (!eval(llm, tokens.data() + i, n, static_cast<int>(i))) {
            llama_memory_clear(mem, true);
            llm->cached.clear();
            return nullptr;
        }
        llm->cached.insert(llm->cached.end(), tokens.begin() + i, tokens.begin() + i + n);
        i += static_cast<size_t>(n);
    }
    if (stats) {
        stats->prompt_tokens = n_prompt;
        stats->cached_tokens = static_cast<int>(keep);
        stats->prompt_ms = static_cast<float>(ms_since(t_prompt));
    }

    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.95f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    const auto t_gen = std::chrono::steady_clock::now();
    std::string reply;     // everything generated
    std::string pending;   // bytes not yet delivered (may end mid UTF-8 sequence)
    int n_out = 0;
    bool in_thought = false;
    for (; n_out < max_tokens; ++n_out) {
        if (llm->cancel.load()) break;
        llama_token tok = llama_sampler_sample(smpl, llm->ctx, -1);
        if (llama_vocab_is_eog(llm->vocab, tok)) break;

        char buf[256];
        // Render control tokens too, so a reasoning block (<|channel>...<channel|>,
        // emitted by Gemma 4 if it decides to think) can be recognised and dropped.
        const int n = llama_token_to_piece(llm->vocab, tok, buf, sizeof(buf), 0, true);
        bool visible = n > 0;
        if (n > 0 && llama_vocab_is_control(llm->vocab, tok)) {
            const std::string piece(buf, static_cast<size_t>(n));
            if (piece == "<|channel>") in_thought = true;
            else if (piece == "<channel|>") in_thought = false;
            visible = false;
        }
        if (visible && !in_thought) {
            reply.append(buf, static_cast<size_t>(n));
            pending.append(buf, static_cast<size_t>(n));
            const size_t ok = utf8_complete_prefix(pending);
            if (ok > 0 && on_token) {
                const int go = on_token(pending.data(), static_cast<int>(ok), user);
                pending.erase(0, ok);
                if (!go) break;
            }
        }

        if (!eval(llm, &tok, 1, static_cast<int>(llm->cached.size()))) break;
        llm->cached.push_back(tok);
    }
    if (!pending.empty() && on_token) on_token(pending.data(), static_cast<int>(pending.size()), user);
    llama_sampler_free(smpl);

    if (stats) {
        stats->output_tokens = n_out;
        stats->output_ms = static_cast<float>(ms_since(t_gen));
    }

    reply = trim(reply);
    char *out = static_cast<char *>(std::malloc(reply.size() + 1));
    if (out) std::memcpy(out, reply.c_str(), reply.size() + 1);
    return out;
}

void tr_llm_cancel(tr_llm *llm) {
    if (llm) llm->cancel.store(true);
}

void tr_free(void *p) { std::free(p); }

}  // extern "C"
