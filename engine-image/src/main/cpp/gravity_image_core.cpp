#include "gravity_image_core.h"

#include <mutex>

#include "stable-diffusion.h"

namespace gimage {

namespace {
// sd.cpp's step callback is global: one picture at a time (the app never makes two at once).
std::mutex g_step_mutex;
const std::function<void(int, int)> *g_on_step = nullptr;

void step_cb(int step, int steps, float, void *) {
    if (g_on_step && *g_on_step) (*g_on_step)(step, steps);
}

std::string last_error(const char *what) {
    const char *e = sd_get_last_error();
    return std::string(what) + (e && *e ? std::string(": ") + e : std::string());
}
}  // namespace

Session *load(const std::string &model, const std::string &lora, const std::string &taesd, int threads,
              const std::string &wtype, std::string &error) {
    sd_ctx_opt_t *opt = sd_ctx_opt_create();
    if (!opt) {
        error = "out of memory";
        return nullptr;
    }
    sd_ctx_t *ctx = nullptr;
    bool ok = sd_opt_set_string(opt, "model_path", model.c_str()) == SD_STATUS_OK &&
              sd_opt_set_int(opt, "n_threads", threads > 0 ? threads : 4) == SD_STATUS_OK &&
              (taesd.empty() || sd_opt_set_string(opt, "taesd_path", taesd.c_str()) == SD_STATUS_OK) &&
              (wtype.empty() || sd_opt_set_string(opt, "wtype", wtype.c_str()) == SD_STATUS_OK);
    if (!ok) {
        error = last_error("bad model settings");
    } else if (sd_ctx_create(opt, &ctx) != SD_STATUS_OK || !ctx) {
        error = last_error("could not prepare the picture model");
        ctx = nullptr;
    }
    sd_opt_free(opt);
    if (!ctx) return nullptr;
    auto *s = new Session();
    s->ctx = ctx;
    s->lora = lora;
    return s;
}

bool generate(Session *s, const std::string &prompt, const std::string &negative, int width, int height,
              int steps, float cfg, int64_t seed, const std::string &method, float lora_strength,
              const std::function<void(int, int)> &on_step, std::vector<uint8_t> &rgb, int &out_w, int &out_h,
              std::string &error) {
    if (!s || !s->ctx) {
        error = "no picture model loaded";
        return false;
    }
    sd_cancel_generation(s->ctx, SD_CANCEL_RESET);
    sd_img_gen_opt_t *opt = sd_img_gen_opt_create();
    if (!opt) {
        error = "out of memory";
        return false;
    }
    sd_lora_t lora{false, lora_strength, s->lora.c_str()};
    bool ok = sd_opt_set_string(opt, "prompt", prompt.c_str()) == SD_STATUS_OK &&
              sd_opt_set_string(opt, "negative_prompt", negative.c_str()) == SD_STATUS_OK &&
              sd_opt_set_int(opt, "width", width) == SD_STATUS_OK &&
              sd_opt_set_int(opt, "height", height) == SD_STATUS_OK &&
              sd_opt_set_int(opt, "seed", seed) == SD_STATUS_OK &&
              sd_opt_set_int(opt, "sample.steps", steps) == SD_STATUS_OK &&
              sd_opt_set_float(opt, "sample.guidance.txt_cfg", cfg) == SD_STATUS_OK &&
              sd_opt_set_string(opt, "sample.method", method.c_str()) == SD_STATUS_OK &&
              (s->lora.empty() || sd_opt_set_loras(opt, "loras", &lora, 1) == SD_STATUS_OK);
    if (!ok) {
        error = last_error("bad picture settings");
        sd_opt_free(opt);
        return false;
    }
    sd_result_t *result = nullptr;
    sd_status_t status;
    {
        std::lock_guard<std::mutex> lock(g_step_mutex);
        g_on_step = &on_step;
        sd_set_progress_callback(step_cb, nullptr);
        status = sd_generate_image(s->ctx, opt, &result);
        sd_set_progress_callback(nullptr, nullptr);
        g_on_step = nullptr;
    }
    sd_opt_free(opt);
    if (status != SD_STATUS_OK || !result || sd_result_get_image_count(result) == 0) {
        error = last_error(status == SD_STATUS_OK ? "no picture came out" : "making the picture failed");
        sd_result_free(result);
        return false;
    }
    // U8 [channels, width, height], channels interleaved: row-major pixels.
    const sd_tensor_t *image = sd_result_get_image(result, 0);
    const int64_t channels = sd_tensor_dim(image, 0);
    out_w = (int) sd_tensor_dim(image, 1);
    out_h = (int) sd_tensor_dim(image, 2);
    const auto *data = static_cast<const uint8_t *>(sd_tensor_data(image));
    if (sd_tensor_type(image) != SD_TENSOR_U8 || channels < 3 || out_w <= 0 || out_h <= 0 || !data) {
        error = "the picture came out in an unexpected format";
        sd_result_free(result);
        return false;
    }
    rgb.resize((size_t) out_w * out_h * 3);
    for (size_t p = 0, n = (size_t) out_w * out_h; p < n; ++p) {
        rgb[p * 3] = data[p * channels];
        rgb[p * 3 + 1] = data[p * channels + 1];
        rgb[p * 3 + 2] = data[p * channels + 2];
    }
    sd_result_free(result);
    return true;
}

void cancel(Session *s) {
    if (s && s->ctx) sd_cancel_generation(s->ctx, SD_CANCEL_ALL);
}

void unload(Session *s) {
    if (!s) return;
    if (s->ctx) sd_ctx_free(s->ctx);
    delete s;
}

}  // namespace gimage
