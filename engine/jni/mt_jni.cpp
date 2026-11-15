// SPDX-License-Identifier: AGPL-3.0-or-later
// JNI bindings for com.bhasha.engine.asr.MtEngine.

#include <jni.h>

#include <cstring>
#include <string>

#include "tr_engine.h"

namespace {

// Real UTF-8 crosses the boundary as byte[]: JNI strings use "modified UTF-8".
std::string bytes_to_string(JNIEnv *env, jbyteArray a) {
    if (!a) return {};
    const jsize n = env->GetArrayLength(a);
    std::string out(static_cast<size_t>(n), '\0');
    if (n > 0) env->GetByteArrayRegion(a, 0, n, reinterpret_cast<jbyte *>(&out[0]));
    return out;
}

tr_mt *mt(jlong h) { return reinterpret_cast<tr_mt *>(h); }

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_bhasha_engine_asr_MtEngine_nativeOpen(JNIEnv *env, jclass, jbyteArray path, jint threads) {
    const std::string p = bytes_to_string(env, path);
    tr_mt_config cfg = tr_mt_default_config();
    cfg.model_path = p.c_str();
    cfg.n_threads = threads;
    return reinterpret_cast<jlong>(tr_mt_open(&cfg));
}

JNIEXPORT void JNICALL
Java_com_bhasha_engine_asr_MtEngine_nativeClose(JNIEnv *, jclass, jlong h) {
    tr_mt_close(mt(h));
}

JNIEXPORT jbyteArray JNICALL
Java_com_bhasha_engine_asr_MtEngine_nativeTranslate(JNIEnv *env, jclass, jlong h, jbyteArray lang, jbyteArray text) {
    const std::string l = bytes_to_string(env, lang), t = bytes_to_string(env, text);
    char *english = tr_mt_translate(mt(h), l.c_str(), t.c_str(), nullptr);
    if (!english) return nullptr;
    const jsize n = static_cast<jsize>(std::strlen(english));
    jbyteArray out = env->NewByteArray(n);
    if (out && n > 0) env->SetByteArrayRegion(out, 0, n, reinterpret_cast<const jbyte *>(english));
    tr_mt_free(english);
    return out;
}

JNIEXPORT void JNICALL
Java_com_bhasha_engine_asr_MtEngine_nativeCancel(JNIEnv *, jclass, jlong h) {
    tr_mt_cancel(mt(h));
}

}  // extern "C"
