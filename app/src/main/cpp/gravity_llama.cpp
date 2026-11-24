// JNI bridge between Gravity and llama.cpp. The inference itself lives in gravity_core.cpp, so
// the same code is checked on ARM in CI (tools/arm-check) without JNI.

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <mutex>
#include "gravity_core.h"

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
Java_com_gravity_ide_ai_local_LlamaBridge_load(JNIEnv *env, jobject, jstring jpath, jint n_ctx, jint n_threads, jint n_gpu_layers) {
    std::string error;
    gravity::Session *s = gravity::load(to_string(env, jpath), n_ctx, n_threads, n_gpu_layers, error);
    if (!s) {
        set_error(error);
        return 0;
    }
    return reinterpret_cast<jlong>(s);
}

extern "C" JNIEXPORT void JNICALL
Java_com_gravity_ide_ai_local_LlamaBridge_unload(JNIEnv *, jobject, jlong handle) {
    gravity::unload(reinterpret_cast<gravity::Session *>(handle));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_gravity_ide_ai_local_LlamaBridge_generate(JNIEnv *env, jobject, jlong handle, jobjectArray jroles,
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
Java_com_gravity_ide_ai_local_LlamaBridge_modelInfo(JNIEnv *env, jobject, jlong handle) {
    auto *s = reinterpret_cast<gravity::Session *>(handle);
    jint values[5] = {0, 0, 0, 0, 0};
    if (s && s->model) {
        values[0] = llama_model_n_ctx_train(s->model);
        values[1] = llama_model_n_layer(s->model);
        values[2] = llama_model_n_embd(s->model);
        values[3] = llama_model_n_head(s->model);
        values[4] = llama_model_n_head_kv(s->model);
    }
    jintArray out = env->NewIntArray(5);
    env->SetIntArrayRegion(out, 0, 5, values);
    return out;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_gravity_ide_ai_local_LlamaBridge_lastFinish(JNIEnv *env, jobject) {
    std::lock_guard<std::mutex> lock(g_error_mutex);
    return env->NewStringUTF(g_last_finish.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_gravity_ide_ai_local_LlamaBridge_lastError(JNIEnv *env, jobject) {
    std::lock_guard<std::mutex> lock(g_error_mutex);
    return env->NewStringUTF(g_last_error.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_gravity_ide_ai_local_LlamaBridge_systemInfo(JNIEnv *env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}
