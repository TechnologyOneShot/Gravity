# Builds Gravity's inference core (app/src/main/cpp/gravity_core.cpp) as a small command-line
# program for arm64 Android, with the app's llama.cpp settings, so CI can run the exact phone
# code under ARM emulation (qemu) with a real model. See the arm64-native-check job.
cmake_minimum_required(VERSION 3.22.1)
project(gravity_arm_check C CXX)

set(LLAMA_DIR "" CACHE PATH "llama.cpp checkout (same tag as the app)")
set(APP_CPP ${CMAKE_CURRENT_SOURCE_DIR}/../../app/src/main/cpp)

# Same options as app/build.gradle.kts + app/src/main/cpp/CMakeLists.txt.
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
# The app turns llamafile off; the check can turn it on to show what it does on ARM.
option(CHECK_LLAMAFILE "Build with llamafile sgemm" OFF)
set(GGML_LLAMAFILE ${CHECK_LLAMAFILE} CACHE BOOL "" FORCE)

add_subdirectory(${LLAMA_DIR} llama-build)

add_executable(gravity-cli gravity_cli.cpp ${APP_CPP}/gravity_core.cpp)
target_include_directories(gravity-cli PRIVATE ${LLAMA_DIR}/include ${APP_CPP})
target_compile_features(gravity-cli PRIVATE cxx_std_17)
target_link_libraries(gravity-cli llama ggml)
# Static, so it runs under qemu-aarch64 without Android's dynamic linker.
target_link_options(gravity-cli PRIVATE -static)

# The saved prompt (save_state / load_state) with a real model; runs natively on the Arm runner.
add_executable(state-check state_check.cpp ${APP_CPP}/gravity_core.cpp)
target_include_directories(state-check PRIVATE ${LLAMA_DIR}/include ${APP_CPP})
target_compile_features(state-check PRIVATE cxx_std_17)
target_link_libraries(state-check llama ggml)

# The waits a user feels in chat and live voice (first word, speed, what a follow-up reads again);
# runs natively on the Arm runner (see .github/workflows/speed.yml).
add_executable(speed-check speed_check.cpp ${APP_CPP}/gravity_core.cpp)
target_include_directories(speed-check PRIVATE ${LLAMA_DIR}/include ${APP_CPP})
target_compile_features(speed-check PRIVATE cxx_std_17)
target_link_libraries(speed-check llama ggml)
