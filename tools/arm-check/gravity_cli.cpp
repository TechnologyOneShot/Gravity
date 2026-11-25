// gravity-cli <model.gguf> <threads> <ctx> <question> [system prompt] [temperature] [max tokens]
// One chat turn through Gravity's inference core (the code the phone runs).
#include <sys/resource.h>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

#include "gravity_core.h"

int main(int argc, char **argv) {
    if (argc < 5) {
        std::fprintf(stderr, "usage: gravity-cli model.gguf threads ctx \"question\" [system] [temperature] [max_tokens]\n");
        return 2;
    }
    const int threads = std::atoi(argv[2]);
    const int ctx = std::atoi(argv[3]);
    const std::string question = argv[4];
    const std::string system = argc > 5 ? argv[5] : "You are a helpful assistant. Answer briefly.";
    const float temperature = argc > 6 ? (float) std::atof(argv[6]) : 0.2f;
    const int max_tokens = argc > 7 ? std::atoi(argv[7]) : 24;
    std::string error;
    gravity::Session *s = gravity::load(argv[1], ctx, threads, 0, std::getenv("NO_MMAP") == nullptr, error);
    if (!s) {
        std::fprintf(stderr, "load failed: %s\n", error.c_str());
        return 1;
    }
    std::printf("SYSTEM_INFO: %s\n", llama_print_system_info());
    // DRAW_SYSTEM=file: the app's picture request on a phone model, step by step as the app does
    // it: the warm-up (WARM_SYSTEM, one token), then the drawing (DRAW_SYSTEM + the question, up to
    // DRAW_MAX tokens, context DRAW_CTX, 0 = the loaded one). Writes the drawing to DRAW_OUT.
    if (const char *draw_system = std::getenv("DRAW_SYSTEM"); draw_system != nullptr) {
        auto read = [](const char *path) {
            std::string out;
            if (FILE *f = std::fopen(path, "rb")) {
                char buf[4096];
                size_t n;
                while ((n = std::fread(buf, 1, sizeof buf, f)) > 0) out.append(buf, n);
                std::fclose(f);
            }
            return out;
        };
        auto peak_mb = [] {
            struct rusage u {};
            getrusage(RUSAGE_SELF, &u);
            return (long) u.ru_maxrss / 1024;
        };
        auto now = [] { return std::chrono::duration<double>(std::chrono::steady_clock::now().time_since_epoch()).count(); };
        std::printf("STEP loaded: context %d, peak memory %ld MB\n", ctx, peak_mb());
        if (const char *warm = std::getenv("WARM_SYSTEM"); warm != nullptr) {
            std::string ignored, fin;
            const double t = now();
            const int w = gravity::generate(s, {"system"}, {read(warm)}, 1, temperature, 0.95f, 0, "", [](const char *, int) { return true; }, fin, error);
            std::printf("STEP warm-up: %d token(s) in %.1f s, peak memory %ld MB, error=%s\n", w, now() - t, peak_mb(), error.c_str());
        }
        const int draw_ctx = std::getenv("DRAW_CTX") ? std::atoi(std::getenv("DRAW_CTX")) : 0;
        const int draw_max = std::getenv("DRAW_MAX") ? std::atoi(std::getenv("DRAW_MAX")) : 2500;
        std::string drawing, fin;
        const double t = now();
        double first = -1;
        const int n = gravity::generate(
            s, {"system", "user"}, {read(draw_system), question}, draw_max, temperature, 0.95f, draw_ctx, "",
            [&](const char *piece, int len) {
                if (first < 0) first = now() - t;
                drawing.append(piece, len);
                return true;
            },
            fin, error);
        const double took = now() - t;
        std::printf("STEP drawing: context %d, %d tokens in %.1f s (first word after %.1f s, %.1f tokens/s), finish=%s, reused %d, peak memory %ld MB, error=%s\n",
            s->ctx_size, n, took, first, n > 0 ? n / took : 0.0, fin.c_str(), s->last_reused, peak_mb(), error.c_str());
        if (const char *out = std::getenv("DRAW_OUT"); out != nullptr) {
            if (FILE *f = std::fopen(out, "wb")) {
                std::fwrite(drawing.data(), 1, drawing.size(), f);
                std::fclose(f);
            }
        }
        gravity::unload(s);
        return n > 0 ? 0 : 1;
    }
    std::string answer, finish;
    const std::vector<std::string> roles{"system", "user"};
    const std::vector<std::string> contents{system, question};
    int n = gravity::generate(
        s, roles, contents, max_tokens, temperature, 0.95f, 0, "",
        [&](const char *piece, int len) {
            answer.append(piece, len);
            return true;
        },
        finish, error);
    const std::string first_answer = answer;
    for (char &ch : answer) if (ch == '\n') ch = ' ';
    std::printf("ANSWER: %s\n", answer.c_str());
    std::printf("RESULT: generated=%d finish=%s error=%s\n", n, finish.c_str(), error.c_str());
    // FOLLOWUP="second question": a second turn in the same conversation, as the app sends it;
    // the context kept from the first turn should be reused (REUSED > 0).
    if (const char *follow = std::getenv("FOLLOWUP"); follow != nullptr && n > 0) {
        std::string answer2, finish2;
        const std::vector<std::string> roles2{"system", "user", "assistant", "user"};
        const std::vector<std::string> contents2{system, question, first_answer, follow};
        const int n2 = gravity::generate(
            s, roles2, contents2, max_tokens, temperature, 0.95f, 0, "",
            [&](const char *piece, int len) {
                answer2.append(piece, len);
                return true;
            },
            finish2, error);
        for (char &ch : answer2) if (ch == '\n') ch = ' ';
        std::printf("REUSED: %d\n", s->last_reused);
        std::printf("ANSWER2: %s\n", answer2.c_str());
        std::printf("RESULT2: generated=%d finish=%s error=%s\n", n2, finish2.c_str(), error.c_str());
        if (n2 <= 0) n = -1;
    }
    gravity::unload(s);
    return n > 0 ? 0 : 1;
}
