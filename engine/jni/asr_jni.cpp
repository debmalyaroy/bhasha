// SPDX-License-Identifier: AGPL-3.0-or-later
// JNI bindings for com.bhasha.engine.asr.AsrEngine.

#include <jni.h>

#include <cstring>
#include <string>

#include "tr_engine.h"

namespace {

std::string to_string(JNIEnv *env, jstring s) {
    if (!s) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

// Builds com.bhasha.engine.asr.AsrResult(byte[] utf8, String lang, float audioSec, float decodeMs).
jobject make_result(JNIEnv *env, tr_asr_result *res) {
    jclass cls = env->FindClass("com/bhasha/engine/asr/AsrResult");
    if (!cls) return nullptr;
    jmethodID ctor = env->GetMethodID(cls, "<init>", "([BLjava/lang/String;FF)V");
    if (!ctor) return nullptr;
    const jsize len = res->text ? static_cast<jsize>(strlen(res->text)) : 0;
    jbyteArray bytes = env->NewByteArray(len);
    if (!bytes) return nullptr;
    if (len > 0) env->SetByteArrayRegion(bytes, 0, len, reinterpret_cast<const jbyte *>(res->text));
    jstring lang = env->NewStringUTF(res->lang);
    return env->NewObject(cls, ctor, bytes, lang, res->audio_sec, res->decode_ms);
}

tr_asr *handle(jlong h) { return reinterpret_cast<tr_asr *>(h); }

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_bhasha_engine_asr_AsrEngine_nativeOpen(JNIEnv *env, jclass, jstring model, jstring vad,
                                                jint threads, jboolean fastEncode,
                                                jstring langModels, jstring langTokens) {
    const std::string model_path = to_string(env, model);
    const std::string vad_path = to_string(env, vad);
    const std::string lang_models = to_string(env, langModels);
    const std::string lang_tokens = to_string(env, langTokens);
    tr_asr_config cfg = tr_asr_default_config();
    cfg.model_path = model_path.empty() ? nullptr : model_path.c_str();
    cfg.lang_model_pattern = lang_models.empty() ? nullptr : lang_models.c_str();
    cfg.lang_model_tokens = lang_tokens.empty() ? nullptr : lang_tokens.c_str();
    cfg.vad_model_path = vad_path.empty() ? nullptr : vad_path.c_str();
    cfg.n_threads = threads;
    cfg.fast_encode = fastEncode ? 1 : 0;
    return reinterpret_cast<jlong>(tr_asr_open(&cfg));
}

JNIEXPORT void JNICALL
Java_com_bhasha_engine_asr_AsrEngine_nativeClose(JNIEnv *, jclass, jlong h) {
    tr_asr_close(handle(h));
}

JNIEXPORT jint JNICALL
Java_com_bhasha_engine_asr_AsrEngine_nativePush(JNIEnv *env, jclass, jlong h, jshortArray pcm, jint n) {
    if (!h || !pcm || n <= 0) return 0;
    jshort *data = env->GetShortArrayElements(pcm, nullptr);
    if (!data) return 0;
    const jint queued = tr_asr_push_pcm16(handle(h), data, n);
    env->ReleaseShortArrayElements(pcm, data, JNI_ABORT);
    return queued;
}

JNIEXPORT jint JNICALL
Java_com_bhasha_engine_asr_AsrEngine_nativeFlush(JNIEnv *, jclass, jlong h) {
    return tr_asr_flush(handle(h));
}

JNIEXPORT void JNICALL
Java_com_bhasha_engine_asr_AsrEngine_nativeReset(JNIEnv *, jclass, jlong h) {
    tr_asr_reset(handle(h));
}

JNIEXPORT jfloat JNICALL
Java_com_bhasha_engine_asr_AsrEngine_nativeSpeechProb(JNIEnv *, jclass, jlong h) {
    return tr_asr_speech_prob(handle(h));
}

JNIEXPORT jobject JNICALL
Java_com_bhasha_engine_asr_AsrEngine_nativeNext(JNIEnv *env, jclass, jlong h, jstring lang, jboolean translate) {
    if (!h) return nullptr;
    const std::string l = to_string(env, lang);
    tr_asr_result res;
    if (tr_asr_next(handle(h), l.c_str(), translate ? 1 : 0, &res) != 1) return nullptr;
    jobject out = make_result(env, &res);
    tr_asr_result_free(&res);
    return out;
}

JNIEXPORT jobject JNICALL
Java_com_bhasha_engine_asr_AsrEngine_nativePartial(JNIEnv *env, jclass, jlong h, jstring lang) {
    if (!h) return nullptr;
    const std::string l = to_string(env, lang);
    tr_asr_result res;
    if (tr_asr_partial(handle(h), l.c_str(), &res) != 1) return nullptr;
    jobject out = make_result(env, &res);
    tr_asr_result_free(&res);
    return out;
}

JNIEXPORT jobject JNICALL
Java_com_bhasha_engine_asr_AsrEngine_nativeTranscribe(JNIEnv *env, jclass, jlong h, jshortArray pcm, jint n,
                                                      jstring lang, jboolean translate) {
    if (!h || !pcm || n <= 0) return nullptr;
    const std::string l = to_string(env, lang);
    jshort *data = env->GetShortArrayElements(pcm, nullptr);
    if (!data) return nullptr;
    tr_asr_result res;
    const int rc = tr_asr_transcribe_pcm16(handle(h), data, n, l.c_str(), translate ? 1 : 0, &res);
    env->ReleaseShortArrayElements(pcm, data, JNI_ABORT);
    if (rc != 1) return nullptr;
    jobject out = make_result(env, &res);
    tr_asr_result_free(&res);
    return out;
}

JNIEXPORT void JNICALL
Java_com_bhasha_engine_asr_AsrEngine_nativeAbort(JNIEnv *, jclass, jlong h) {
    tr_asr_abort(handle(h));
}

}  // extern "C"
