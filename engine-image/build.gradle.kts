// The on-phone picture engine: stable-diffusion.cpp (pinned commit at
// engine-image/src/main/cpp/stable-diffusion.cpp) behind a small JNI layer (libgravity_image.so,
// Kotlin object com.gravity.ide.ai.image.NativeImage). On arm64 it is built for Armv8.2 (dot product,
// fp16): Gravity only loads it on CPUs that report those features.
plugins {
    alias(libs.plugins.android.library)
}

val abis = (project.findProperty("gravity.abis") as String?)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
    ?: listOf("arm64-v8a", "x86_64")

android {
    namespace = "com.gravity.engine.image"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        ndk {
            abiFilters.addAll(abis)
        }
        externalNativeBuild {
            cmake {
                arguments.addAll(
                    listOf(
                        "-DCMAKE_BUILD_TYPE=Release",
                        "-DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16",
                    )
                )
                cppFlags.add("-std=c++17")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}
