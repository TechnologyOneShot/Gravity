// Runs Gravity's on-phone picture engine (engine-image/src/main/cpp/gravity_image_core.cpp) outside
// Android, for CI on an Arm machine: same code, real models. Writes a PPM and prints timings.
// Usage: gravity-image-cli model lora taesd threads wtype width height steps cfg seed method out.ppm prompt [negative]
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <sys/resource.h>
#include <vector>

#include "gravity_image_core.h"

static double now() {
    return std::chrono::duration<double>(std::chrono::steady_clock::now().time_since_epoch()).count();
}

static long peak_mb() {
    struct rusage u {};
    getrusage(RUSAGE_SELF, &u);
    return u.ru_maxrss / 1024;
}

int main(int argc, char **argv) {
    if (argc < 14) {
        std::fprintf(stderr, "usage: %s model lora taesd threads wtype width height steps cfg seed method out.ppm prompt [negative]\n", argv[0]);
        return 2;
    }
    std::string error;
    const double t0 = now();
    gimage::Session *s = gimage::load(argv[1], argv[2], argv[3], std::atoi(argv[4]), argv[5], error);
    if (!s) {
        std::printf("LOAD FAILED: %s\n", error.c_str());
        return 1;
    }
    const double t1 = now();
    double first_step = -1;
    std::vector<uint8_t> rgb;
    int w = 0, h = 0;
    const bool ok = gimage::generate(
        s, argv[13], argc > 14 ? argv[14] : "", std::atoi(argv[6]), std::atoi(argv[7]), std::atoi(argv[8]),
        (float) std::atof(argv[9]), std::atoll(argv[10]), argv[11], 1.0f,
        [&](int step, int steps) {
            if (first_step < 0) first_step = now() - t1;
            std::printf("STEP %d/%d at %.1f s\n", step, steps, now() - t1);
            std::fflush(stdout);
        },
        rgb, w, h, error);
    const double t2 = now();
    if (!ok) {
        std::printf("GENERATE FAILED: %s\n", error.c_str());
        gimage::unload(s);
        return 1;
    }
    if (FILE *f = std::fopen(argv[12], "wb")) {
        std::fprintf(f, "P6\n%d %d\n255\n", w, h);
        std::fwrite(rgb.data(), 1, rgb.size(), f);
        std::fclose(f);
    }
    std::printf("PICTURE %dx%d: prepare %.1f s, make %.1f s, peak memory %ld MB\n", w, h, t1 - t0, t2 - t1, peak_mb());
    gimage::unload(s);
    return 0;
}
