// The fast on-device AI engine: app/src/main/cpp built again with Armv8.2 dot-product and fp16
// instructions (libgravity_llama_fast.so, Kotlin class NativeFast). Gravity uses it only on CPUs
// that report those features, and only after the model answers a check question correctly.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.gravity.engine.fast"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        ndk {
            abiFilters.add("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                arguments.addAll(
                    listOf(
                        "-DLLAMA_CURL=OFF",
                        "-DGGML_OPENMP=OFF",
                        "-DLLAMA_BUILD_TESTS=OFF",
                        "-DLLAMA_BUILD_EXAMPLES=OFF",
                        "-DLLAMA_BUILD_SERVER=OFF",
                        "-DGGML_LLAMAFILE=OFF",
                        "-DCMAKE_BUILD_TYPE=Release",
                        "-DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16",
                        "-DGRAVITY_LIB_NAME=gravity_llama_fast",
                        "-DGRAVITY_JNI_CLASS=NativeFast",
                    )
                )
                cppFlags.add("-std=c++17")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../app/src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}
