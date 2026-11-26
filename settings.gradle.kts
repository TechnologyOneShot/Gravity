pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Gravity"
include(":app")
// Tiny WebView app that Gravity re-packages on the phone for every web app it builds.
include(":shell")
// Fast on-device AI engine for Armv8.2 phones: the same native code as the app's engine, built
// with dot-product and fp16 instructions. Only with the native runtime (-Pgravity.llama=true).
val llamaBuild = providers.gradleProperty("gravity.llama").orNull == "true"
val llamaAbis = providers.gradleProperty("gravity.abis").orNull ?: "arm64-v8a,x86_64"
if (llamaBuild && "arm64-v8a" in llamaAbis && providers.gradleProperty("gravity.fastEngine").orNull != "false") {
    include(":engine-fast")
}
// Engine for Armv8.6 phones with int8 matrix instructions (i8mm): reads prompts ~30% faster, but
// off by default: in CI (Arm Neoverse-N2, Gemma 3 4B) its next-word scores after a saved prompt
// differed from reading everything (max difference 2.9 where the other engines give 0), so it is
// only built with -Pgravity.i8mmEngine=true until that is understood.
if (llamaBuild && "arm64-v8a" in llamaAbis && providers.gradleProperty("gravity.i8mmEngine").orNull == "true") {
    include(":engine-i8mm")
}
// On-phone picture engine (stable-diffusion.cpp), with the native runtime unless -Pgravity.image=false.
// Needs engine-image/src/main/cpp/stable-diffusion.cpp (pinned commit, cloned with submodules).
if (llamaBuild && providers.gradleProperty("gravity.image").orNull != "false" &&
    file("engine-image/src/main/cpp/stable-diffusion.cpp/CMakeLists.txt").exists()
) {
    include(":engine-image")
}
