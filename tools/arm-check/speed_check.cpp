// speed-check <model.gguf> <threads> <chat-system.txt> <live-system.txt>
//
// The waits a user feels in chat and live voice, measured with Gravity's inference core (the code
// the phone runs, gravity_core.cpp) on a real Arm CPU: loading the model, the warm-up (reading the
// system prompt before the user sends), the first word of a reply and how fast the rest comes, how
// much of the chat a follow-up reads again, and in a live voice turn the first finished sentence
// (when the voice can start). Prints SPEED lines that the workflow collects into a table.
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

#include "gravity_core.h"

static double now() { return std::chrono::duration<double>(std::chrono::steady_clock::now().time_since_epoch()).count(); }

static std::string read_file(const char *path) {
    std::ifstream f(path);
    std::stringstream ss;
    ss << f.rdbuf();
    return ss.str();
}

struct Turn {
    double first = -1;     // seconds to the first piece
    double sentence = -1;  // seconds to the end of the first sentence (12+ characters)
    double total = 0;
    int tokens = 0;
    int reused = 0;
    int prompt = 0;
    std::string text, finish, error;
};

static Turn ask(gravity::Session *s, const std::vector<std::string> &roles, const std::vector<std::string> &contents, int max_tokens) {
    Turn t;
    const double start = now();
    t.tokens = gravity::generate(
        s, roles, contents, max_tokens, 0.4f, 0.95f, 0, "",
        [&](const char *piece, int len) {
            if (t.first < 0) t.first = now() - start;
            t.text.append(piece, len);
            if (t.sentence < 0 && t.text.size() >= 12) {
                const std::string &x = t.text;
                const char last = x.back();
                const bool danda = x.size() >= 3 && x.compare(x.size() - 3, 3, "\xE0\xA5\xA4") == 0;  // "।"
                if (last == '.' || last == '!' || last == '?' || last == '\n' || danda) t.sentence = now() - start;
            }
            return true;
        },
        t.finish, t.error);
    t.total = now() - start;
    t.reused = s->last_reused;
    t.prompt = s->last_prompt;
    return t;
}

static std::string trim(const std::string &x) {
    const auto a = x.find_first_not_of(" \n\r\t");
    if (a == std::string::npos) return "";
    const auto b = x.find_last_not_of(" \n\r\t");
    return x.substr(a, b - a + 1);
}

static double write_speed(const Turn &t) { return t.tokens > 1 && t.total > t.first ? (t.tokens - 1) / (t.total - t.first) : 0; }

static std::string one_line(const std::string &x) {
    std::string out;
    for (char c : x) out += (c == '\n' ? ' ' : c);
    return out.size() > 160 ? out.substr(0, 160) + "…" : out;
}

int main(int argc, char **argv) {
    if (argc < 5) {
        std::fprintf(stderr, "usage: speed-check model.gguf threads chat-system.txt live-system.txt\n");
        return 2;
    }
    const int threads = std::atoi(argv[2]);
    const std::string chat_system = read_file(argv[3]);
    const std::string live_system = read_file(argv[4]);
    std::string error, fin;

    double t0 = now();
    gravity::Session *s = gravity::load(argv[1], 4096, threads, 0, true, error);
    if (!s) {
        std::printf("LOAD FAILED: %s\n", error.c_str());
        return 1;
    }
    std::printf("SYSTEM_INFO: %s\n", llama_print_system_info());
    // The first request builds the context (and repacks weights on first use): part of what a user waits for once.
    const double load_s = now() - t0;

    // Warm-up as the app does when a chat opens: the system prompt and an empty user turn, one token.
    Turn warm = ask(s, {"system", "user"}, {chat_system, ""}, 1);
    const int warm_read = warm.prompt - warm.reused;
    std::printf("SPEED warm-up: read %d tokens in %.2f s (%.0f tokens/s)\n", warm_read, warm.total, warm_read / (warm.total > 0 ? warm.total : 1));

    // A chat question after the warm-up.
    const std::string q1 = "Subah jaldi uthne ke 5 aasan tips do.";
    Turn a = ask(s, {"system", "user"}, {chat_system, q1}, 220);
    std::printf("SPEED chat reply: first word %.0f ms (read %d new tokens), %d tokens at %.1f tokens/s, finish=%s %s\n",
                a.first * 1000, a.prompt - a.reused, a.tokens, write_speed(a), a.finish.c_str(), a.error.c_str());
    std::printf("REPLY 1: %s\n", one_line(a.text).c_str());

    // A follow-up, with the reply stored as the app stores it (trimmed): how much is read again?
    // The context holds the first prompt and the reply's tokens; ideally only the new question is read.
    const int ideal_reuse = a.prompt + a.tokens;
    const std::string q2 = "Inme se sabse aasan kaun sa hai?";
    Turn b = ask(s, {"system", "user", "assistant", "user"}, {chat_system, q1, trim(a.text), q2}, 120);
    std::printf("SPEED follow-up: first word %.0f ms, reused %d of %d tokens (read %d again; the reply had %d tokens, "
                "ideal reuse about %d), %.1f tokens/s\n",
                b.first * 1000, b.reused, b.prompt, b.prompt - b.reused, a.tokens, ideal_reuse, write_speed(b));
    std::printf("REPLY 2: %s\n", one_line(b.text).c_str());

    // Live voice: the app warms up the live prompt when the talk opens; then a spoken question.
    Turn lw = ask(s, {"system", "user"}, {live_system, ""}, 1);
    std::printf("SPEED live warm-up: read %d tokens in %.2f s\n", lw.prompt - lw.reused, lw.total);
    const std::string q3 = "Yaar aaj office mein bahut thak gaya, kya karun?";
    Turn c = ask(s, {"system", "user"}, {live_system, q3}, 80);
    std::printf("SPEED live turn: first word %.0f ms, first sentence %.0f ms (read %d new tokens), %d tokens at %.1f tokens/s\n",
                c.first * 1000, c.sentence * 1000, c.prompt - c.reused, c.tokens, write_speed(c));
    std::printf("REPLY 3: %s\n", one_line(c.text).c_str());

    std::printf("SPEED load: %.1f s\n", load_s);
    std::printf("SUMMARY|load %.1fs|warm %.0f tok/s|chat first %.0f ms|write %.1f tok/s|follow-up read %d again (reply %d)|follow-up first %.0f ms|live first sentence %.0f ms\n",
                load_s, warm_read / (warm.total > 0 ? warm.total : 1), a.first * 1000, write_speed(a), b.prompt - b.reused, a.tokens,
                b.first * 1000, c.sentence * 1000);
    gravity::unload(s);
    return 0;
}
