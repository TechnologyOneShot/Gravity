import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Optional on-device llama.cpp runtime. Off by default so the standard debug build
// never depends on a native checkout. See docs/OFFLINE_SETUP.md.
val llamaEnabled = (project.findProperty("gravity.llama") as String?)?.toBoolean() == true
val llamaDir = file("src/main/cpp/llama.cpp")
// ABIs for the native runtime; override with -Pgravity.abis=x86_64 (e.g. emulator-only builds).
val llamaAbis = (project.findProperty("gravity.abis") as String?)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
    ?: listOf("arm64-v8a", "x86_64")
if (llamaEnabled && !llamaDir.resolve("CMakeLists.txt").exists()) {
    throw GradleException(
        "gravity.llama=true but llama.cpp was not found at ${llamaDir.path}. " +
            "Clone it there (see docs/OFFLINE_SETUP.md) or build with -Pgravity.llama=false."
    )
}

android {
    namespace = "com.gravity.ide"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.gravity.ide"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "LLAMA_BUNDLED", llamaEnabled.toString())

        if (llamaEnabled) {
            ndk {
                abiFilters.addAll(llamaAbis)
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
                            "-DCMAKE_BUILD_TYPE=Release"
                        )
                    )
                    cppFlags.add("-std=c++17")
                }
            }
        }
    }

    if (llamaEnabled) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)

    // On-device (emulator) verification: see app/src/androidTest and the emulator CI job.
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
