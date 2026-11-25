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
    gravity::Session *s = gravity::load(argv[1], ctx, threads, 0, std::getenv("NO_MMAP") == nullptr, error);
    if (!s) {
        std::fprintf(stderr, "load failed: %s\n", error.c_str());
        return 1;
    }
    std::printf("SYSTEM_INFO: %s\n", llama_print_system_info());
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
