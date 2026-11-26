// Gravity's on-phone picture engine: a thin layer over stable-diffusion.cpp, shared by the app's
// JNI library (gravity_image.cpp) and the CI check tool (tools/image-check), so CI runs the same code.
#pragma once

#include <cstdint>
#include <functional>
#include <string>
#include <vector>

struct sd_ctx_t;

namespace gimage {

struct Session {
    sd_ctx_t *ctx = nullptr;
    std::string lora;  // speed-up LoRA (LCM) applied to every picture, "" for none
};

// Prepares a Stable Diffusion checkpoint with an optional LoRA and the small TAESD decoder.
// The weights are read on the first picture (as [wtype], e.g. "q8_0"). nullptr + [error] on failure.
Session *load(const std::string &model, const std::string &lora, const std::string &taesd, int threads,
              const std::string &wtype, std::string &error);

// Makes one picture: [on_step](step, steps) after each sampling step. Fills [rgb] with
// out_w*out_h*3 bytes, row by row. False + [error] on failure or when cancelled.
bool generate(Session *s, const std::string &prompt, const std::string &negative, int width, int height,
              int steps, float cfg, int64_t seed, const std::string &method, float lora_strength,
              const std::function<void(int, int)> &on_step, std::vector<uint8_t> &rgb, int &out_w, int &out_h,
              std::string &error);

// Stops a picture being made (from another thread); generate() then returns false.
void cancel(Session *s);

void unload(Session *s);

}  // namespace gimage
