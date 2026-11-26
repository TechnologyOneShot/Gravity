cmake_minimum_required(VERSION 3.22.1)
project(gravity_image LANGUAGES C CXX)

# The on-phone picture engine: stable-diffusion.cpp (pinned commit, cloned by CI or by hand to
# engine-image/src/main/cpp/stable-diffusion.cpp, with --recursive for its ggml) behind a small JNI layer.
set(SD_DIR ${CMAKE_CURRENT_SOURCE_DIR}/stable-diffusion.cpp)

set(SD_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(SD_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(SD_WEBP OFF CACHE BOOL "" FORCE)
set(SD_WEBM OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

add_subdirectory(${SD_DIR} sd-build)

add_library(gravity_image SHARED gravity_image.cpp gravity_image_core.cpp)
target_include_directories(gravity_image PRIVATE ${SD_DIR}/include)
target_link_libraries(gravity_image stable-diffusion android log)
# Keep stable-diffusion.cpp's own ggml private: the chat engines in the same app have theirs.
target_link_options(gravity_image PRIVATE -Wl,--exclude-libs,ALL)
