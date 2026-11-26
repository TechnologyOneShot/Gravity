// JNI bridge between Gravity and llama.cpp. The inference itself lives in gravity_core.cpp, so
// the same code is checked on ARM in CI (tools/arm-check) without JNI.

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <mutex>
#include <cstdint>
#include <cstdlib>
#include "gravity_core.h"
#if defined(__aarch64__)
#include <sys/auxv.h>
#endif

// The same source is built twice for arm64 phones: the standard engine (Armv8.0, class
// NativeBase) and a fast engine (Armv8.2 dot product + fp16, class NativeFast). The Kotlin
// class that a build binds to is set by CMake (GRAVITY_JNI_CLASS).
#ifndef GRAVITY_JNI_CLASS
#define GRAVITY_JNI_CLASS NativeBase
#endif
#define GRAVITY_CAT2(a, b) a##b
#define GRAVITY_CAT(a, b) GRAVITY_CAT2(a, b)
#define GRAVITY_JNI(name) GRAVITY_CAT(GRAVITY_CAT(Java_com_gravity_ide_ai_local_, GRAVITY_JNI_CLASS), GRAVITY_CAT(_, name))

#define TAG "GravityLlama"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

std::mutex g_error_mutex;
std::string g_last_error;
std::string g_last_finish;  // why the last generation ended: eos, stop, max, ctx or error

void set_finish(const std::string &why) {
    std::lock_guard<std::mutex> lock(g_error_mutex);
    g_last_finish = why;
}

void set_error(const std::string &msg) {
    std::lock_guard<std::mutex> lock(g_error_mutex);
    g_last_error = msg;
    LOGE("%s", msg.c_str());
}

std::string to_string(JNIEnv *env, jstring s) {
    if (!s) return {};
    const char *chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(s, chars);
    return out;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
GRAVITY_JNI(load)(JNIEnv *env, jobject, jstring jpath, jint n_ctx, jint n_threads, jint n_gpu_layers,
                                                jboolean use_mmap) {
    std::string error;
    gravity::Session *s = gravity::load(to_string(env, jpath), n_ctx, n_threads, n_gpu_layers, use_mmap == JNI_TRUE, error);
    if (!s) {
        set_error(error);
        return 0;
    }
    return reinterpret_cast<jlong>(s);
}

extern "C" JNIEXPORT void JNICALL
GRAVITY_JNI(unload)(JNIEnv *, jobject, jlong handle) {
    gravity::unload(reinterpret_cast<gravity::Session *>(handle));
}

extern "C" JNIEXPORT jint JNICALL
GRAVITY_JNI(generate)(JNIEnv *env, jobject, jlong handle, jobjectArray jroles,
                                                    jobjectArray jcontents, jint max_tokens, jfloat temperature,
                                                    jfloat top_p, jint n_ctx_req, jstring jprefix, jobject callback) {
    const jsize n = env->GetArrayLength(jroles);
    std::vector<std::string> roles(n), contents(n);
    for (jsize i = 0; i < n; ++i) {
        auto jr = (jstring) env->GetObjectArrayElement(jroles, i);
        auto jc = (jstring) env->GetObjectArrayElement(jcontents, i);
        roles[i] = to_string(env, jr);
        contents[i] = to_string(env, jc);
        env->DeleteLocalRef(jr);
        env->DeleteLocalRef(jc);
    }
    jclass cbClass = env->GetObjectClass(callback);
    jmethodID onToken = env->GetMethodID(cbClass, "onToken", "([B)Z");
    auto on_piece = [&](const char *piece, int len) -> bool {
        jbyteArray arr = env->NewByteArray(len);
        env->SetByteArrayRegion(arr, 0, len, reinterpret_cast<const jbyte *>(piece));
        jboolean keepGoing = env->CallBooleanMethod(callback, onToken, arr);
        env->DeleteLocalRef(arr);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return false;
        }
        return keepGoing;
    };
    std::string finish, error;
    int generated = gravity::generate(
        reinterpret_cast<gravity::Session *>(handle), roles, contents, max_tokens, temperature, top_p, n_ctx_req,
        to_string(env, jprefix), on_piece, finish, error);
    if (!error.empty()) set_error(error);
    set_finish(finish);
    return generated;
}

// [n_ctx_train, n_layer, n_embd, n_head, n_head_kv] of a loaded model (for memory planning).
extern "C" JNIEXPORT jintArray JNICALL
GRAVITY_JNI(modelInfo)(JNIEnv *env, jobject, jlong handle) {
    auto *s = reinterpret_cast<gravity::Session *>(handle);
    // [n_ctx_train, n_layer, n_embd, n_head, n_head_kv, tokens reused by the last request, kept context size,
    //  tokens the last request read (its prompt), threads]
    jint values[9] = {0, 0, 0, 0, 0, 0, 0, 0, 0};
    if (s && s->model) {
        values[0] = llama_model_n_ctx_train(s->model);
        values[1] = llama_model_n_layer(s->model);
        values[2] = llama_model_n_embd(s->model);
        values[3] = llama_model_n_head(s->model);
        values[4] = llama_model_n_head_kv(s->model);
        values[5] = s->last_reused;
        values[6] = s->ctx_size;
        values[7] = s->last_prompt;
        values[8] = s->n_threads;
    }
    jintArray out = env->NewIntArray(9);
    env->SetIntArrayRegion(out, 0, 9, values);
    return out;
}

extern "C" JNIEXPORT jstring JNICALL
GRAVITY_JNI(lastFinish)(JNIEnv *env, jobject) {
    std::lock_guard<std::mutex> lock(g_error_mutex);
    return env->NewStringUTF(g_last_finish.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
GRAVITY_JNI(lastError)(JNIEnv *env, jobject) {
    std::lock_guard<std::mutex> lock(g_error_mutex);
    return env->NewStringUTF(g_last_error.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
GRAVITY_JNI(systemInfo)(JNIEnv *env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}

// True if heap pointers carry an Android pointer tag in the top byte (for diagnostics).
extern "C" JNIEXPORT jboolean JNICALL
GRAVITY_JNI(heapTagged)(JNIEnv *, jobject) {
    void *p = std::malloc(64);
    const bool tagged = (reinterpret_cast<uintptr_t>(p) >> 56) != 0;
    std::free(p);
    return tagged ? JNI_TRUE : JNI_FALSE;
}

// The CPU features the kernel reports (AT_HWCAP) on arm64; 0 elsewhere.
extern "C" JNIEXPORT jlong JNICALL
GRAVITY_JNI(cpuHwcap)(JNIEnv *, jobject) {
#if defined(__aarch64__)
    return (jlong) getauxval(AT_HWCAP);
#else
    return 0;
#endif
}
