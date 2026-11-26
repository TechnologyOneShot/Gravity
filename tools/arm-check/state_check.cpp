// Checks Yamini's saved prompt (gravity_core.cpp save_state/load_state) with a real model: a state
// saved after the warm-up and loaded into a fresh session must be reused (the question only reads
// what is new) and give the same next-token logits as a session that read everything; a wrong
// file is refused. Usage: state-check model.gguf [system-prompt.txt] [threads]
#include <fstream>
#include <sstream>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <string>
#include <vector>
#include <cstdlib>
#include "gravity_core.h"
static std::string run(gravity::Session *s, const std::vector<std::string> &r, const std::vector<std::string> &c, int max) {
    std::string out, finish, err;
    int n = gravity::generate(s, r, c, max, 0.0f, 0.95f, 0, "", [&](const char *p, int l) { out.append(p, l); return true; }, finish, err);
    if (n < 0) std::printf("ERROR %s\n", err.c_str());
    return out;
}
static std::vector<float> logits(gravity::Session *s) {
    const int nv = llama_vocab_n_tokens(llama_model_get_vocab(s->model));
    const float *l = llama_get_logits_ith(s->ctx, -1);
    return std::vector<float>(l, l + nv);
}
int main(int argc, char **argv) {
    std::string err, sys;
    if (argc > 2) { std::ifstream f(argv[2]); std::stringstream ss; ss << f.rdbuf(); sys = ss.str(); }
    if (sys.empty()) for (int i = 0; i < 40; i++) sys += "You are Yamini, a friendly assistant who answers clearly and briefly. ";
    const int threads = argc > 3 ? std::atoi(argv[3]) : 2;
    auto ms = [](auto a, auto b) { return (long) std::chrono::duration_cast<std::chrono::milliseconds>(b - a).count(); };
    std::vector<std::string> rw{"system", "user"}, cw{sys, ""};
    std::vector<std::string> cq{sys, "What is the capital of France?"};
    auto *a = gravity::load(argv[1], 4096, threads, 0, true, err);
    auto t0 = std::chrono::steady_clock::now();
    run(a, rw, cw, 1);
    auto t1 = std::chrono::steady_clock::now();
    int prompt = a->last_prompt;
    bool saved = gravity::save_state(a, "/tmp/state.bin", err);
    auto t2 = std::chrono::steady_clock::now();
    std::printf("warm-up read %d tokens in %ld ms; saved in %ld ms: %s %s\n", prompt, ms(t0, t1), ms(t1, t2), saved ? "yes" : "no", err.c_str());
    gravity::unload(a);

    auto *b = gravity::load(argv[1], 4096, threads, 0, true, err);
    auto t3 = std::chrono::steady_clock::now();
    int loaded = gravity::load_state(b, "/tmp/state.bin", 0, err);
    auto t4 = std::chrono::steady_clock::now();
    std::printf("loaded %d tokens in %ld ms %s\n", loaded, ms(t3, t4), err.c_str());
    run(b, rw, cq, 1);
    auto t5 = std::chrono::steady_clock::now();
    std::printf("question answered (first token) %ld ms after loading the state\n", ms(t4, t5));
    int reused = b->last_reused;
    auto lb = logits(b);
    gravity::unload(b);

    auto *c = gravity::load(argv[1], 4096, threads, 0, true, err);
    run(c, rw, cq, 1);
    auto lc = logits(c);
    gravity::unload(c);
    float maxd = 0, maxabs = 0;
    for (size_t i = 0; i < lb.size(); ++i) { maxd = std::fmax(maxd, std::fabs(lb[i] - lc[i])); maxabs = std::fmax(maxabs, std::fabs(lc[i])); }
    bool same = maxd <= 1e-2f * std::fmax(1.0f, maxabs);
    std::printf("after loading the state the question reused %d tokens; logits max |diff| %.5f (up to %.3f): %s\n", reused, maxd, maxabs, same ? "same" : "DIFFERENT");
    // A wrong file is refused cleanly.
    std::FILE *f = std::fopen("/tmp/bad.bin", "wb"); std::fputs("not a state", f); std::fclose(f);
    auto *d = gravity::load(argv[1], 4096, threads, 0, true, err);
    int bad = gravity::load_state(d, "/tmp/bad.bin", 0, err);
    std::printf("bad file: %d (%s)\n", bad, err.c_str());
    gravity::unload(d);
    const bool ok = saved && loaded > 0 && reused * 10 >= loaded * 9 && same && bad < 0;
    std::printf("RESULT: %s\n", ok ? "PASS" : "FAIL");
    return ok ? 0 : 1;
}
