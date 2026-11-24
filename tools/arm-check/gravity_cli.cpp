// gravity-cli <model.gguf> <question> [threads]: one chat turn through Gravity's inference core.
#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

#include "gravity_core.h"

int main(int argc, char **argv) {
    if (argc < 3) {
        std::fprintf(stderr, "usage: gravity-cli model.gguf \"question\" [threads]\n");
        return 2;
    }
    const int threads = argc > 3 ? std::atoi(argv[3]) : 4;
    std::string error;
    gravity::Session *s = gravity::load(argv[1], 1024, threads, 0, error);
    if (!s) {
        std::fprintf(stderr, "load failed: %s\n", error.c_str());
        return 1;
    }
    std::printf("SYSTEM_INFO: %s\n", llama_print_system_info());
    std::string answer, finish;
    const std::vector<std::string> roles{"system", "user"};
    const std::vector<std::string> contents{"You are a helpful assistant. Answer briefly.", argv[2]};
    const int n = gravity::generate(
        s, roles, contents, 24, 0.2f, 0.95f, 0, "",
        [&](const char *piece, int len) {
            answer.append(piece, len);
            return true;
        },
        finish, error);
    std::printf("ANSWER: %s\n", answer.c_str());
    std::printf("RESULT: generated=%d finish=%s error=%s\n", n, finish.c_str(), error.c_str());
    gravity::unload(s);
    return n > 0 ? 0 : 1;
}
