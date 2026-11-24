// JNI bridge between Gravity and llama.cpp.
// Written against the llama.cpp C API (llama_model_load_from_file / llama_init_from_model /
// llama_vocab / sampler chains). Pin the llama.cpp checkout to the tag listed in
// docs/OFFLINE_SETUP.md: the C API changes between releases.

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <mutex>
#include "llama.h"

#define TAG "GravityLlama"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct Session {
    llama_model *model = nullptr;
    int n_ctx = 4096;
    int n_threads = 4;
};

std::mutex g_error_mutex;
std::string g_last_error;
bool g_backend_ready = false;

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
    if (!g_backend_ready) {
        llama_backend_init();
        g_backend_ready = true;
    }
    std::string path = to_string(env, jpath);
    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = n_gpu_layers;
    llama_model *model = llama_model_load_from_file(path.c_str(), mparams);
    if (!model) {
        set_error("llama_model_load_from_file failed for " + path);
        return 0;
    }
    auto *s = new Session();
    s->model = model;
    s->n_ctx = n_ctx > 0 ? n_ctx : 4096;
    s->n_threads = n_threads > 0 ? n_threads : 4;
    return reinterpret_cast<jlong>(s);
}

extern "C" JNIEXPORT void JNICALL
Java_com_gravity_ide_ai_local_LlamaBridge_unload(JNIEnv *, jobject, jlong handle) {
    auto *s = reinterpret_cast<Session *>(handle);
    if (!s) return;
    if (s->model) llama_model_free(s->model);
    delete s;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_gravity_ide_ai_local_LlamaBridge_generate(JNIEnv *env, jobject, jlong handle, jobjectArray jroles,
                                                    jobjectArray jcontents, jint max_tokens, jfloat temperature,
                                                    jfloat top_p, jint n_ctx_req, jstring jprefix, jobject callback) {
    auto *s = reinterpret_cast<Session *>(handle);
    if (!s || !s->model) {
        set_error("Model not loaded");
        return -1;
    }
    const llama_vocab *vocab = llama_model_get_vocab(s->model);

    // 1. Build the prompt with the model's own chat template (GGUF metadata).
    const jsize n = env->GetArrayLength(jroles);
    std::vector<std::string> roles(n), contents(n);
    std::vector<llama_chat_message> chat(n);
    for (jsize i = 0; i < n; ++i) {
        roles[i] = to_string(env, (jstring) env->GetObjectArrayElement(jroles, i));
        contents[i] = to_string(env, (jstring) env->GetObjectArrayElement(jcontents, i));
    }
    for (jsize i = 0; i < n; ++i) chat[i] = {roles[i].c_str(), contents[i].c_str()};

    const char *tmpl = llama_model_chat_template(s->model, nullptr);
    if (!tmpl) tmpl = "chatml";  // GGUF without an embedded template: fall back to ChatML
    std::vector<char> buf(16 * 1024);
    int32_t len = llama_chat_apply_template(tmpl, chat.data(), chat.size(), true, buf.data(), (int32_t) buf.size());
    if (len > (int32_t) buf.size()) {
        buf.resize(len + 1);
        len = llama_chat_apply_template(tmpl, chat.data(), chat.size(), true, buf.data(), (int32_t) buf.size());
    }
    if (len < 0) {
        set_error("llama_chat_apply_template failed (model has no usable chat template)");
        return -1;
    }
    std::string prompt(buf.data(), len);
    // Optional start of the assistant's reply (e.g. "```html"), so the model continues from it.
    prompt += to_string(env, jprefix);
    // Context for this request: the size the model was loaded with, or a larger one asked for
    // by the caller (contexts are created per request, so this costs memory only while generating).
    const int n_ctx = n_ctx_req > s->n_ctx ? n_ctx_req : s->n_ctx;

    // 2. Tokenize with the model's tokenizer.
    int32_t n_tokens = -llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(), nullptr, 0, true, true);
    std::vector<llama_token> tokens(n_tokens);
    if (llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(), tokens.data(), n_tokens, true, true) < 0) {
        set_error("Tokenization failed");
        return -1;
    }
    if (n_tokens >= n_ctx) {
        set_error("Prompt (" + std::to_string(n_tokens) + " tokens) exceeds context size " + std::to_string(n_ctx));
        return -1;
    }

    // 3. Fresh context per request (simple and robust across llama.cpp versions).
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = n_ctx;
    cparams.n_batch = n_ctx;
    cparams.n_threads = s->n_threads;
    cparams.n_threads_batch = s->n_threads;
    llama_context *ctx = llama_init_from_model(s->model, cparams);
    if (!ctx) {
        set_error("llama_init_from_model failed (out of memory?)");
        return -1;
    }

    // Mild repetition penalty and min-p keep small models from looping on one token.
    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(64, 1.05f, 0.0f, 0.0f));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(top_p, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_min_p(0.05f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    jclass cbClass = env->GetObjectClass(callback);
    jmethodID onToken = env->GetMethodID(cbClass, "onToken", "([B)Z");

    int generated = 0;
    llama_batch batch = llama_batch_get_one(tokens.data(), n_tokens);
    llama_token next = 0;
    int n_past = n_tokens;
    while (generated < max_tokens) {
        if (llama_decode(ctx, batch) != 0) {
            set_error("llama_decode failed");
            generated = generated > 0 ? generated : -1;
            break;
        }
        next = llama_sampler_sample(smpl, ctx, -1);
        if (llama_vocab_is_eog(vocab, next)) break;

        char piece[256];
        int plen = llama_token_to_piece(vocab, next, piece, sizeof(piece), 0, true);
        if (plen > 0) {
            jbyteArray arr = env->NewByteArray(plen);
            env->SetByteArrayRegion(arr, 0, plen, reinterpret_cast<const jbyte *>(piece));
            jboolean keepGoing = env->CallBooleanMethod(callback, onToken, arr);
            env->DeleteLocalRef(arr);
            if (env->ExceptionCheck() || !keepGoing) {
                env->ExceptionClear();
                generated++;
                break;
            }
        }
        generated++;
        if (++n_past >= n_ctx) break;
        batch = llama_batch_get_one(&next, 1);
    }

    llama_sampler_free(smpl);
    llama_free(ctx);
    return generated;
}

// [n_ctx_train, n_layer, n_embd, n_head, n_head_kv] of a loaded model (for memory planning).
extern "C" JNIEXPORT jintArray JNICALL
Java_com_gravity_ide_ai_local_LlamaBridge_modelInfo(JNIEnv *env, jobject, jlong handle) {
    auto *s = reinterpret_cast<Session *>(handle);
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
Java_com_gravity_ide_ai_local_LlamaBridge_lastError(JNIEnv *env, jobject) {
    std::lock_guard<std::mutex> lock(g_error_mutex);
    return env->NewStringUTF(g_last_error.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_gravity_ide_ai_local_LlamaBridge_systemInfo(JNIEnv *env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}
