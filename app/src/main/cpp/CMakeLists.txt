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
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

add_subdirectory(${LLAMA_DIR} llama-build)

add_library(gravity_llama SHARED gravity_llama.cpp)
target_include_directories(gravity_llama PRIVATE ${LLAMA_DIR}/include)
target_link_libraries(gravity_llama llama ggml android log)
