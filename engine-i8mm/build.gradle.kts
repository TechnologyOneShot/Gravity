// The fastest on-device AI engine: app/src/main/cpp built again with Armv8.6 int8 matrix (i8mm),
// dot-product and fp16 instructions (libgravity_llama_i8mm.so, Kotlin class NativeI8mm): prompts
// are read about 30% faster (measured on an Arm Neoverse-N2). Yamini uses it only on CPUs that
// report those features, and only after the model answers a check question correctly.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.gravity.engine.i8mm"
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
                        "-DGGML_CPU_ARM_ARCH=armv8.6-a+dotprod+fp16+i8mm",
                        "-DGRAVITY_LIB_NAME=gravity_llama_i8mm",
                        "-DGRAVITY_JNI_CLASS=NativeI8mm",
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
