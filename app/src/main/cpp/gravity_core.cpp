// Gravity's on-device inference core (see gravity_core.h). Written against the llama.cpp C API at
// the tag pinned in docs/OFFLINE_SETUP.md (b6000); the C API changes between releases.

#include "gravity_core.h"

#include <mutex>

namespace gravity {

namespace {
std::once_flag g_backend_once;
}

Session *load(const std::string &path, int n_ctx, int n_threads, int n_gpu_layers, bool use_mmap, std::string &error) {
    std::call_once(g_backend_once, [] { llama_backend_init(); });
    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = n_gpu_layers;
    mparams.use_mmap = use_mmap;
    llama_model *model = llama_model_load_from_file(path.c_str(), mparams);
    if (!model) {
        error = "llama_model_load_from_file failed for " + path;
        return nullptr;
    }
    auto *s = new Session();
    s->model = model;
    s->n_ctx = n_ctx > 0 ? n_ctx : 4096;
    s->n_threads = n_threads > 0 ? n_threads : 4;
    return s;
}

void unload(Session *s) {
    if (!s) return;
    if (s->ctx) llama_free(s->ctx);
    if (s->model) llama_model_free(s->model);
    delete s;
}

// The context for [n_ctx] tokens, kept between requests; another size gets a new one (a big
// context is not kept around for ordinary chats).
static llama_context *ensure_ctx(Session *s, int n_ctx, std::string &error) {
    if (s->ctx && s->ctx_size == n_ctx) return s->ctx;
    if (s->ctx) llama_free(s->ctx);
    s->ctx = nullptr;
    s->cached.clear();
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = n_ctx;
    cparams.n_batch = n_ctx;
    cparams.n_threads = s->n_threads;
    cparams.n_threads_batch = s->n_threads;
    // Flash attention: faster attention and a smaller cache on the CPU (llama.cpp turns it on
    // by default where supported in later versions).
    cparams.flash_attn = true;
    s->ctx = llama_init_from_model(s->model, cparams);
    if (!s->ctx) {
        s->ctx_size = 0;
        error = "llama_init_from_model failed (out of memory?)";
        return nullptr;
    }
    s->ctx_size = n_ctx;
    return s->ctx;
}

bool save_state(Session *s, const std::string &path, std::string &error) {
    if (!s || !s->ctx || s->cached.empty()) {
        error = "nothing to save";
        return false;
    }
    if (!llama_state_save_file(s->ctx, path.c_str(), s->cached.data(), s->cached.size())) {
        error = "llama_state_save_file failed";
        return false;
    }
    return true;
}

int load_state(Session *s, const std::string &path, int n_ctx_req, std::string &error) {
    if (!s || !s->model) {
        error = "Model not loaded";
        return -1;
    }
    const int n_ctx = n_ctx_req > s->n_ctx ? n_ctx_req : s->n_ctx;
    llama_context *ctx = ensure_ctx(s, n_ctx, error);
    if (!ctx) return -1;
    std::vector<llama_token> tokens(n_ctx);
    size_t count = 0;
    if (!llama_state_load_file(ctx, path.c_str(), tokens.data(), tokens.size(), &count) || count == 0 || count >= (size_t) n_ctx) {
        // A file from another model, size or engine: start clean.
        llama_memory_clear(llama_get_memory(ctx), true);
        s->cached.clear();
        error = "the saved state does not fit this model";
        return -1;
    }
    tokens.resize(count);
    s->cached = tokens;
    return (int) count;
}

int generate(Session *s, const std::vector<std::string> &roles, const std::vector<std::string> &contents, int max_tokens,
             float temperature, float top_p, int n_ctx_req, const std::string &prefix,
             const std::function<bool(const char *, int)> &on_piece, std::string &finish, std::string &error) {
    finish = "error";
    if (!s || !s->model) {
        error = "Model not loaded";
        return -1;
    }
    const llama_vocab *vocab = llama_model_get_vocab(s->model);

    // 1. Build the prompt with the model's own chat template (GGUF metadata).
    const size_t n = roles.size() < contents.size() ? roles.size() : contents.size();
    std::vector<llama_chat_message> chat(n);
    for (size_t i = 0; i < n; ++i) chat[i] = {roles[i].c_str(), contents[i].c_str()};

    const char *tmpl = llama_model_chat_template(s->model, nullptr);
    if (!tmpl) tmpl = "chatml";  // GGUF without an embedded template: fall back to ChatML
    std::vector<char> buf(16 * 1024);
    int32_t len = llama_chat_apply_template(tmpl, chat.data(), chat.size(), true, buf.data(), (int32_t) buf.size());
    if (len > (int32_t) buf.size()) {
        buf.resize(len + 1);
        len = llama_chat_apply_template(tmpl, chat.data(), chat.size(), true, buf.data(), (int32_t) buf.size());
    }
    if (len < 0) {
        error = "llama_chat_apply_template failed (model has no usable chat template)";
        return -1;
    }
    std::string prompt(buf.data(), len);
    // Optional start of the assistant's reply (e.g. "```html"), so the model continues from it.
    prompt += prefix;
    // Context for this request: the size the model was loaded with, or a larger one asked for
    // by the caller (e.g. to write a whole app).
    const int n_ctx = n_ctx_req > s->n_ctx ? n_ctx_req : s->n_ctx;

    // 2. Tokenize with the model's tokenizer.
    int32_t n_tokens = -llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(), nullptr, 0, true, true);
    std::vector<llama_token> tokens(n_tokens);
    if (llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(), tokens.data(), n_tokens, true, true) < 0) {
        error = "Tokenization failed";
        return -1;
    }
    if (n_tokens >= n_ctx) {
        error = "Prompt (" + std::to_string(n_tokens) + " tokens) exceeds context size " + std::to_string(n_ctx);
        return -1;
    }

    // 3. One context per loaded model, kept between requests (see ensure_ctx).
    llama_context *ctx = ensure_ctx(s, n_ctx, error);
    if (!ctx) return -1;
    llama_memory_t mem = llama_get_memory(ctx);

    // Reuse the tokens this context already read (same start of the conversation); at least the
    // last prompt token is read again so there are fresh logits to sample from.
    size_t reuse = 0;
    while (reuse < s->cached.size() && reuse < tokens.size() && s->cached[reuse] == tokens[reuse]) reuse++;
    if (reuse >= tokens.size()) reuse = tokens.size() - 1;
    if (reuse > 0) {
        // Sliding-window models (e.g. Gemma 3) drop old tokens from part of their cache: reuse only
        // if the cache still holds everything the next tokens look back at (as llama-server does).
        const llama_pos pos_min = llama_memory_seq_pos_min(mem, 0);
        const int n_swa = llama_model_n_swa(s->model);
        const int keep_from = (int) reuse - n_swa > 0 ? (int) reuse - n_swa : 0;
        if (pos_min < 0 || (n_swa > 0 && pos_min > keep_from) || (n_swa <= 0 && pos_min > 0)) reuse = 0;
    }
    if (reuse > 0 && !llama_memory_seq_rm(mem, 0, (llama_pos) reuse, -1)) reuse = 0;
    if (reuse == 0) llama_memory_clear(mem, true);
    s->cached.resize(reuse);
    s->last_reused = (int) reuse;
    s->last_prompt = (int) tokens.size();

    // Mild repetition penalty and min-p keep small models from looping on one token.
    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(64, 1.05f, 0.0f, 0.0f));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(top_p, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_min_p(0.05f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    int generated = 0;
    finish = "max";
    llama_batch batch = llama_batch_get_one(tokens.data() + reuse, n_tokens - (int32_t) reuse);
    llama_token next = 0;
    int n_past = n_tokens;
    bool first = true;
    while (generated < max_tokens) {
        if (llama_decode(ctx, batch) != 0) {
            error = "llama_decode failed";
            generated = generated > 0 ? generated : -1;
            finish = "error";
            // The context's state is unknown now: start clean next time.
            llama_memory_clear(mem, true);
            s->cached.clear();
            break;
        }
        // Remember what the context holds now.
        if (first) {
            s->cached.insert(s->cached.end(), tokens.begin() + (long) reuse, tokens.end());
            first = false;
        } else {
            s->cached.push_back(next);
        }
        next = llama_sampler_sample(smpl, ctx, -1);
        if (llama_vocab_is_eog(vocab, next)) {
            finish = "eos";
            break;
        }
        char piece[256];
        int plen = llama_token_to_piece(vocab, next, piece, sizeof(piece), 0, true);
        if (plen > 0 && !on_piece(piece, plen)) {
            generated++;
            finish = "stop";
            break;
        }
        generated++;
        if (++n_past >= n_ctx) {
            finish = "ctx";  // the context is full
            break;
        }
        batch = llama_batch_get_one(&next, 1);
    }

    llama_sampler_free(smpl);
    return generated;
}

}  // namespace gravity
