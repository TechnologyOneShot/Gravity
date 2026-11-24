cmake_minimum_required(VERSION 3.22.1)
project(gravity_llama LANGUAGES C CXX)

# llama.cpp is expected at app/src/main/cpp/llama.cpp (git clone, pinned tag — see docs/OFFLINE_SETUP.md).
set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp)

set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
# llamafile sgemm does not support Android (garbage output on ARM phones); llama.cpp's Android docs turn it off.
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

add_subdirectory(${LLAMA_DIR} llama-build)

# The library and the Kotlin class it binds to. The app builds the standard engine; the
# :engine-fast module builds this same file again for Armv8.2 phones (GGML_CPU_ARM_ARCH).
set(GRAVITY_LIB_NAME gravity_llama CACHE STRING "Name of the engine library")
set(GRAVITY_JNI_CLASS NativeBase CACHE STRING "Kotlin class in com.gravity.ide.ai.local")
message(STATUS "Gravity engine ${GRAVITY_LIB_NAME} (${GRAVITY_JNI_CLASS}), GGML_CPU_ARM_ARCH='${GGML_CPU_ARM_ARCH}'")

add_library(${GRAVITY_LIB_NAME} SHARED gravity_llama.cpp gravity_core.cpp)
target_include_directories(${GRAVITY_LIB_NAME} PRIVATE ${LLAMA_DIR}/include)
target_compile_definitions(${GRAVITY_LIB_NAME} PRIVATE GRAVITY_JNI_CLASS=${GRAVITY_JNI_CLASS})
target_link_libraries(${GRAVITY_LIB_NAME} llama ggml android log)
# Keep llama.cpp's symbols private, so two engine builds in one app never mix.
target_link_options(${GRAVITY_LIB_NAME} PRIVATE -Wl,--exclude-libs,ALL)
