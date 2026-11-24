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
