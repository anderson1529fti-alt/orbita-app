// The bridge between Java (orbita.nova.NovaLlm) and the big brain. Text goes both ways as UTF-8 bytes.
#include <jni.h>
#include <string>
#include "nova_llm.h"

static thread_local std::string g_error;

static std::string bytes(JNIEnv * env, jbyteArray a) {
    if (!a) return std::string();
    const jsize n = env->GetArrayLength(a);
    std::string s((size_t) n, '\0');
    if (n > 0) env->GetByteArrayRegion(a, 0, n, reinterpret_cast<jbyte *>(&s[0]));
    return s;
}

extern "C" {

JNIEXPORT jlong JNICALL Java_orbita_nova_NovaLlm_nLoad(JNIEnv * env, jclass, jstring jpath, jint n_ctx, jint n_threads) {
    const char * p = env->GetStringUTFChars(jpath, nullptr);
    std::string path(p ? p : "");
    if (p) env->ReleaseStringUTFChars(jpath, p);
    g_error.clear();
    NovaLlm * h = nova_load(path.c_str(), n_ctx, n_threads, g_error);
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT jbyteArray JNICALL Java_orbita_nova_NovaLlm_nComplete(JNIEnv * env, jclass, jlong h, jbyteArray prompt, jbyteArray grammar,
                                                                jint max_tokens, jfloat temp, jfloat top_p, jint seed) {
    g_error.clear();
    if (!h) { g_error = "sin modelo"; return nullptr; }
    std::string out = nova_complete(reinterpret_cast<NovaLlm *>(h), bytes(env, prompt), bytes(env, grammar), max_tokens, temp, top_p, (uint32_t) seed, g_error);
    if (!g_error.empty() && out.empty()) return nullptr;
    jbyteArray r = env->NewByteArray((jsize) out.size());
    if (r && !out.empty()) env->SetByteArrayRegion(r, 0, (jsize) out.size(), reinterpret_cast<const jbyte *>(out.data()));
    return r;
}

JNIEXPORT jstring JNICALL Java_orbita_nova_NovaLlm_nLastError(JNIEnv * env, jclass) {
    return env->NewStringUTF(g_error.c_str());
}

JNIEXPORT jstring JNICALL Java_orbita_nova_NovaLlm_nInfo(JNIEnv * env, jclass, jlong h) {
    return env->NewStringUTF(h ? nova_info(reinterpret_cast<NovaLlm *>(h)).c_str() : "{}");
}

JNIEXPORT void JNICALL Java_orbita_nova_NovaLlm_nCancel(JNIEnv *, jclass, jlong h) {
    if (h) nova_cancel(reinterpret_cast<NovaLlm *>(h));
}

JNIEXPORT void JNICALL Java_orbita_nova_NovaLlm_nFree(JNIEnv *, jclass, jlong h) {
    if (h) nova_free(reinterpret_cast<NovaLlm *>(h));
}

}
