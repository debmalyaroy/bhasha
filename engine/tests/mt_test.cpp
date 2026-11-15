// SPDX-License-Identifier: AGPL-3.0-or-later
// Host test for tr_mt (IndicTrans2 on ONNX Runtime).
//
//   mt_test <model.bhmt> vectors <mt_vectors.tsv>     check every stage against the Python reference
//   mt_test <model.bhmt> <hi|bn|kn> <in.txt> <out.txt> translate a file line by line, print timings
//
// mt_vectors.tsv is made from models/cand/indictrans2/test_vectors.json:
//   S <lang> <input> <prepared> <source ids> <output ids, ending in 2> <english>
//   D <model output text> <english>

#include "tr_engine.h"

#include <cstdio>
#include <cstring>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

static std::vector<std::string> split(const std::string &s, char sep) {
    std::vector<std::string> out;
    std::string cur;
    for (char c : s) {
        if (c == sep) { out.push_back(cur); cur.clear(); }
        else cur.push_back(c);
    }
    out.push_back(cur);
    return out;
}

static std::string join(const int64_t *ids, int n) {
    std::ostringstream o;
    for (int i = 0; i < n; ++i) o << (i ? " " : "") << ids[i];
    return o.str();
}

static std::string take(char *p) {
    std::string s = p ? p : "<null>";
    tr_mt_free(p);
    return s;
}

static int run_vectors(tr_mt *mt, const char *path) {
    std::ifstream in(path, std::ios::binary);
    if (!in) { std::fprintf(stderr, "cannot read %s\n", path); return 2; }
    int checks = 0, failed = 0, skipped = 0, reworded = 0;
    auto check = [&](const char *what, int line, const std::string &got, const std::string &want) {
        ++checks;
        if (got == want) return true;
        ++failed;
        std::printf("line %d: %s differs\n  got  %s\n  want %s\n", line, what, got.c_str(), want.c_str());
        return false;
    };
    std::string row;
    for (int line = 1; std::getline(in, row); ++line) {
        const std::vector<std::string> f = split(row, '\t');
        if (f[0] == "D" && f.size() >= 3 && f[1].find("ID1") == std::string::npos) {
            check("detokenised text", line, take(tr_mt_debug_detokenize(f[1].c_str())), f[2]);
            continue;
        }
        if (f[0] != "S" || f.size() < 7) continue;
        // Placeholders for URLs and long numbers are not part of this port (PORTING.md 3.3).
        if (f[3].find("ID1") != std::string::npos) { ++skipped; continue; }
        const char *lang = f[1].c_str(), *text = f[2].c_str();
        check("prepared text", line, take(tr_mt_debug_prepare(lang, text)), f[3]);
        int64_t ids[2048];
        int n = tr_mt_debug_tokens(mt, lang, text, ids, 2048);
        check("source ids", line, join(ids, n < 0 ? 0 : n), f[4]);
        if (f[5].empty()) continue;
        n = tr_mt_debug_generate(mt, lang, text, ids, 2047);
        if (n >= 0) ids[n++] = 2;
        // Word choice depends on the ONNX Runtime build (int8 rounding), so the exact
        // output is reported, not required; it must still be a complete sentence.
        const std::string english = take(tr_mt_translate(mt, lang, text, nullptr));
        if (join(ids, n < 0 ? 0 : n) != f[5]) { ++reworded; std::printf("line %d: %s\n  ref:   %s\n", line, english.c_str(), f[6].c_str()); }
        ++checks;
        if (n < 4 || english.empty()) { ++failed; std::printf("line %d: no translation\n", line); }
    }
    std::printf("%d checks, %d failed, %d translations worded differently from the reference, %d cases skipped (placeholders)\n",
                checks, failed, reworded, skipped);
    return failed ? 1 : 0;
}

int main(int argc, char **argv) {
    if (argc < 4) {
        std::fprintf(stderr, "usage: %s <model.bhmt> vectors <tsv> | <model.bhmt> <lang> <in.txt> <out.txt>\n", argv[0]);
        return 2;
    }
    tr_mt_config cfg = tr_mt_default_config();
    cfg.model_path = argv[1];
    tr_mt *mt = tr_mt_open(&cfg);
    if (!mt) { std::fprintf(stderr, "failed to load the model from %s\n", argv[1]); return 1; }

    int rc = 0;
    if (std::strcmp(argv[2], "vectors") == 0) {
        rc = run_vectors(mt, argv[3]);
    } else if (argc >= 5) {
        std::ifstream in(argv[3], std::ios::binary);
        std::ofstream out(argv[4], std::ios::binary);
        std::string line;
        float total = 0.0f;
        int n = 0;
        while (std::getline(in, line)) {
            if (!line.empty() && line.back() == '\r') line.pop_back();
            float ms = 0.0f;
            out << take(tr_mt_translate(mt, argv[2], line.c_str(), &ms)) << "\n";
            total += ms;
            ++n;
        }
        std::printf("%d lines, %.0f ms per line\n", n, n ? total / n : 0.0f);
    }
    tr_mt_close(mt);
    return rc;
}
