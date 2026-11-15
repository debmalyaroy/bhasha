// SPDX-License-Identifier: AGPL-3.0-or-later
// Runs the translation prompt the app uses over each line of a UTF-8 text file.
//
//   llm_test <model.gguf> <language name> <in.txt> <out.txt> [plain]
//
// Lines are sent as consecutive turns of one conversation, like the live
// translator does, which also exercises KV-cache prefix reuse.

#include "tr_engine.h"

#include <cstdio>
#include <cstring>
#include <fstream>
#include <string>
#include <vector>

static int count_cb(const char *, int n, void *user) {
    *static_cast<int *>(user) += n;
    return 1;
}

int main(int argc, char **argv) {
    if (argc < 5) {
        std::fprintf(stderr, "usage: %s <model.gguf> <language name> <in.txt> <out.txt>\n", argv[0]);
        return 2;
    }
    tr_llm_global_init(nullptr);
    tr_llm_config cfg = tr_llm_default_config();
    cfg.model_path = argv[1];
    cfg.n_threads = 4;
    tr_llm *llm = tr_llm_open(&cfg);
    if (!llm) {
        std::fprintf(stderr, "failed to load model\n");
        return 1;
    }

    // "plain" as a fifth argument: the one-line instruction that dedicated translation
    // models (Sarvam-Translate) were trained with, one sentence per conversation.
    const bool plain = argc > 5 && std::strcmp(argv[5], "plain") == 0;
    const std::string system = plain ? std::string("Translate the text below to English.") :
        std::string("You are a simultaneous interpreter. Each user message is ") + argv[2] +
        " speech that was transcribed by a speech recogniser, so it may contain recognition errors, missing "
        "punctuation or English words mixed in. Reply with a faithful, natural English translation of what was "
        "most likely said. Output only the translation: no notes, no quotation marks, no original text.";

    std::ifstream in(argv[3], std::ios::binary);
    std::ofstream out(argv[4], std::ios::binary);
    std::vector<std::string> turns;   // user, assistant, user, ...
    std::string line;
    int failures = 0;
    while (std::getline(in, line)) {
        while (!line.empty() && (line.back() == '\r' || line.back() == '\n')) line.pop_back();
        if (line.empty()) continue;
        turns.push_back(line);

        std::vector<tr_llm_message> msgs;
        msgs.push_back({"system", system.c_str()});
        for (size_t i = 0; i < turns.size(); ++i) msgs.push_back({i % 2 == 0 ? "user" : "assistant", turns[i].c_str()});

        tr_llm_stats st;
        int streamed = 0;
        char *reply = tr_llm_chat(llm, msgs.data(), static_cast<int>(msgs.size()), 200, 0.0f, count_cb, &streamed, &st);
        if (!reply) {
            std::printf("FAILED\n");
            ++failures;
            turns.pop_back();
            continue;
        }
        out << line << "\n  => " << reply << "\n";
        std::printf("prompt %d tok (%d cached) in %.0f ms | output %d tok in %.0f ms (%.1f tok/s) | streamed %d bytes, reply %zu bytes\n",
                    st.prompt_tokens, st.cached_tokens, st.prompt_ms, st.output_tokens, st.output_ms,
                    st.output_ms > 0 ? st.output_tokens * 1000.0 / st.output_ms : 0.0, streamed, std::strlen(reply));
        std::printf("  EN: %s\n", reply);
        turns.push_back(reply);
        if (plain) turns.clear();
        tr_free(reply);
    }
    tr_llm_close(llm);
    return failures == 0 ? 0 : 1;
}
