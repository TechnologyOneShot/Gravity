// gravity-cli <model.gguf> <threads> <ctx> <question> [system prompt] [temperature] [max tokens]
// One chat turn through Gravity's inference core (the code the phone runs).
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
    gravity::Session *s = gravity::load(argv[1], ctx, threads, 0, error);
    if (!s) {
        std::fprintf(stderr, "load failed: %s\n", error.c_str());
        return 1;
    }
    std::printf("SYSTEM_INFO: %s\n", llama_print_system_info());
    std::string answer, finish;
    const std::vector<std::string> roles{"system", "user"};
    const std::vector<std::string> contents{system, question};
    const int n = gravity::generate(
        s, roles, contents, max_tokens, temperature, 0.95f, 0, "",
        [&](const char *piece, int len) {
            answer.append(piece, len);
            return true;
        },
        finish, error);
    for (char &ch : answer) if (ch == '\n') ch = ' ';
    std::printf("ANSWER: %s\n", answer.c_str());
    std::printf("RESULT: generated=%d finish=%s error=%s\n", n, finish.c_str(), error.c_str());
    gravity::unload(s);
    return n > 0 ? 0 : 1;
}
