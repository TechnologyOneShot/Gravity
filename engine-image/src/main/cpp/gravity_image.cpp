// JNI for com.gravity.ide.ai.image.NativeImage (the on-phone picture engine).
#include <jni.h>
#include <android/log.h>

#include <mutex>
#include <string>
#include <vector>

#include "gravity_image_core.h"

#define TAG "GravityImage"

namespace {
std::mutex g_err_mutex;
std::string g_error;
int g_last_w = 0, g_last_h = 0;

void set_error(const std::string &e) {
    std::lock_guard<std::mutex> lock(g_err_mutex);
    g_error = e;
    if (!e.empty()) __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", e.c_str());
}

std::string str(JNIEnv *env, jstring s) {
    if (!s) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_gravity_ide_ai_image_NativeImage_load(JNIEnv *env, jobject, jstring model, jstring lora, jstring taesd,
                                               jint threads, jstring wtype) {
    std::string error;
    auto *s = gimage::load(str(env, model), str(env, lora), str(env, taesd), threads, str(env, wtype), error);
    set_error(s ? "" : error);
    return reinterpret_cast<jlong>(s);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_gravity_ide_ai_image_NativeImage_generate(JNIEnv *env, jobject, jlong handle, jstring prompt, jstring negative,
                                                   jint width, jint height, jint steps, jfloat cfg, jlong seed,
                                                   jstring method, jfloat lora_strength, jobject callback) {
    jmethodID on_step = nullptr;
    if (callback) on_step = env->GetMethodID(env->GetObjectClass(callback), "onStep", "(II)V");
    std::function<void(int, int)> step = [&](int s, int n) {
        if (callback && on_step) {
            env->CallVoidMethod(callback, on_step, (jint) s, (jint) n);
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
    };
    std::vector<uint8_t> rgb;
    int w = 0, h = 0;
    std::string error;
    const bool ok = gimage::generate(reinterpret_cast<gimage::Session *>(handle), str(env, prompt), str(env, negative),
                                     width, height, steps, cfg, seed, str(env, method), lora_strength, step, rgb, w, h,
                                     error);
    set_error(ok ? "" : error);
    if (!ok) return nullptr;
    g_last_w = w;
    g_last_h = h;
    jbyteArray out = env->NewByteArray((jsize) rgb.size());
    if (out) env->SetByteArrayRegion(out, 0, (jsize) rgb.size(), reinterpret_cast<const jbyte *>(rgb.data()));
    return out;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_gravity_ide_ai_image_NativeImage_lastWidth(JNIEnv *, jobject) { return g_last_w; }

extern "C" JNIEXPORT jint JNICALL
Java_com_gravity_ide_ai_image_NativeImage_lastHeight(JNIEnv *, jobject) { return g_last_h; }

extern "C" JNIEXPORT void JNICALL
Java_com_gravity_ide_ai_image_NativeImage_cancel(JNIEnv *, jobject, jlong handle) {
    gimage::cancel(reinterpret_cast<gimage::Session *>(handle));
}

extern "C" JNIEXPORT void JNICALL
Java_com_gravity_ide_ai_image_NativeImage_unload(JNIEnv *, jobject, jlong handle) {
    gimage::unload(reinterpret_cast<gimage::Session *>(handle));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_gravity_ide_ai_image_NativeImage_lastError(JNIEnv *env, jobject) {
    std::lock_guard<std::mutex> lock(g_err_mutex);
    return env->NewStringUTF(g_error.c_str());
}
