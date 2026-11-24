// Gravity's on-device inference core on top of llama.cpp: no JNI here, so the exact same code
// runs in the app (through gravity_llama.cpp) and in the CI command-line check (tools/arm-check).
#pragma once

#include <functional>
#include <string>
#include <vector>

#include "llama.h"

namespace gravity {

struct Session {
    llama_model *model = nullptr;
    int n_ctx = 4096;
    int n_threads = 4;
};

/** Loads a GGUF model. Returns nullptr on failure and sets [error]. */
Session *load(const std::string &path, int n_ctx, int n_threads, int n_gpu_layers, std::string &error);

void unload(Session *s);

/**
 * Applies the model's chat template, appends [prefix] (the start of the reply), tokenizes and
 * samples up to [max_tokens] tokens. [on_piece] gets the UTF-8 bytes of each token piece and
 * returns false to stop. Returns the number of generated tokens, or -1 on error ([error] set).
 * [finish] is "eos", "stop", "max", "ctx" (context full) or "error".
 */
int generate(Session *s, const std::vector<std::string> &roles, const std::vector<std::string> &contents, int max_tokens,
             float temperature, float top_p, int n_ctx_req, const std::string &prefix,
             const std::function<bool(const char *, int)> &on_piece, std::string &finish, std::string &error);

}  // namespace gravity
