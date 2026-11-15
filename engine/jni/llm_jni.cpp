// SPDX-License-Identifier: AGPL-3.0-or-later
// JNI bindings for com.bhasha.engine.llm.LlmEngine.

#include <jni.h>

#include <cstring>
#include <string>
#include <vector>

#include "tr_engine.h"

namespace {

std::string to_string(JNIEnv *env, jstring s) {
    if (!s) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

// Real UTF-8 crosses the boundary as byte[]: JNI strings use "modified UTF-8",
// which mangles characters outside the BMP.
std::string to_string(JNIEnv *env, jbyteArray a) {
    if (!a) return {};
    const jsize n = env->GetArrayLength(a);
    std::string out(static_cast<size_t>(n), '\0');
    if (n > 0) env->GetByteArrayRegion(a, 0, n, reinterpret_cast<jbyte *>(&out[0]));
    return out;
}

jbyteArray to_bytes(JNIEnv *env, const char *data, int n) {
    jbyteArray arr = env->NewByteArray(n);
    if (arr && n > 0) env->SetByteArrayRegion(arr, 0, n, reinterpret_cast<const jbyte *>(data));
    return arr;
}

struct CallbackCtx {
    JNIEnv *env;
    jobject listener;
    jmethodID on_token;
};

int on_token(const char *utf8, int n, void *user) {
    auto *c = static_cast<CallbackCtx *>(user);
    if (!c->listener) return 1;
    jbyteArray arr = to_bytes(c->env, utf8, n);
    if (!arr) return 0;
    const jboolean go = c->env->CallBooleanMethod(c->listener, c->on_token, arr);
    c->env->DeleteLocalRef(arr);
    if (c->env->ExceptionCheck()) return 0;
    return go ? 1 : 0;
}

tr_llm *handle(jlong h) { return reinterpret_cast<tr_llm *>(h); }

}  // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_bhasha_engine_llm_LlmEngine_nativeInit(JNIEnv *env, jclass, jstring backendDir) {
    const std::string dir = to_string(env, backendDir);
    tr_llm_global_init(dir.empty() ? nullptr : dir.c_str());
}

JNIEXPORT jlong JNICALL
Java_com_bhasha_engine_llm_LlmEngine_nativeOpen(JNIEnv *env, jclass, jstring model, jint nCtx, jint threads) {
    const std::string path = to_string(env, model);
    tr_llm_config cfg = tr_llm_default_config();
    cfg.model_path = path.c_str();
    cfg.n_ctx = nCtx;
    cfg.n_threads = threads;
    return reinterpret_cast<jlong>(tr_llm_open(&cfg));
}

JNIEXPORT void JNICALL
Java_com_bhasha_engine_llm_LlmEngine_nativeClose(JNIEnv *, jclass, jlong h) {
    tr_llm_close(handle(h));
}

// Returns the reply as UTF-8 bytes, or null on failure. `stats` (float[5], optional)
// receives prompt tokens, cached tokens, output tokens, prompt ms, output ms.
JNIEXPORT jbyteArray JNICALL
Java_com_bhasha_engine_llm_LlmEngine_nativeChat(JNIEnv *env, jclass, jlong h, jobjectArray roles,
                                                jobjectArray contents, jint maxTokens, jfloat temperature,
                                                jobject listener, jfloatArray stats) {
    if (!h || !roles || !contents) return nullptr;
    const jsize n = env->GetArrayLength(roles);
    if (n <= 0 || env->GetArrayLength(contents) != n) return nullptr;

    std::vector<std::string> role_s(static_cast<size_t>(n)), content_s(static_cast<size_t>(n));
    std::vector<tr_llm_message> msgs(static_cast<size_t>(n));
    for (jsize i = 0; i < n; ++i) {
        auto r = static_cast<jstring>(env->GetObjectArrayElement(roles, i));
        auto c = static_cast<jbyteArray>(env->GetObjectArrayElement(contents, i));
        role_s[i] = to_string(env, r);
        content_s[i] = to_string(env, c);
        env->DeleteLocalRef(r);
        env->DeleteLocalRef(c);
        msgs[i].role = role_s[i].c_str();
        msgs[i].content = content_s[i].c_str();
    }

    CallbackCtx ctx{env, listener, nullptr};
    if (listener) {
        jclass cls = env->GetObjectClass(listener);
        ctx.on_token = env->GetMethodID(cls, "onToken", "([B)Z");
        if (!ctx.on_token) return nullptr;
    }

    tr_llm_stats st;
    char *reply = tr_llm_chat(handle(h), msgs.data(), n, maxTokens, temperature, on_token, &ctx, &st);
    if (!reply) return nullptr;

    if (stats && env->GetArrayLength(stats) >= 5) {
        const jfloat v[5] = {static_cast<jfloat>(st.prompt_tokens), static_cast<jfloat>(st.cached_tokens),
                             static_cast<jfloat>(st.output_tokens), st.prompt_ms, st.output_ms};
        env->SetFloatArrayRegion(stats, 0, 5, v);
    }
    jbyteArray out = to_bytes(env, reply, static_cast<int>(strlen(reply)));
    tr_free(reply);
    return out;
}

JNIEXPORT void JNICALL
Java_com_bhasha_engine_llm_LlmEngine_nativeCancel(JNIEnv *, jclass, jlong h) {
    tr_llm_cancel(handle(h));
}

}  // extern "C"
