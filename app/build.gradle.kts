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
// Second engine build for Armv8.2 phones (module :engine-fast, included by settings.gradle.kts).
val fastEngine = rootProject.findProject(":engine-fast") != null
// Third engine build for Armv8.6 phones with i8mm (module :engine-i8mm).
val i8mmEngine = rootProject.findProject(":engine-i8mm") != null
// On-phone picture engine, stable-diffusion.cpp (module :engine-image, included by settings.gradle.kts).
val imageEngine = rootProject.findProject(":engine-image") != null
// Offline natural voice (sherpa-onnx), fetched by .github/scripts/fetch-sherpa.sh into app/voice-libs.
val voiceLibs = file("voice-libs")
val voiceEngine = llamaEnabled && voiceLibs.resolve("arm64-v8a/libsherpa-onnx-jni.so").isFile
if (llamaEnabled && !llamaDir.resolve("CMakeLists.txt").exists()) {
    throw GradleException(
        "gravity.llama=true but llama.cpp was not found at ${llamaDir.path}. " +
            "Clone it there (see docs/OFFLINE_SETUP.md) or build with -Pgravity.llama=false."
    )
}

// App Builder: the shell APK (module :shell) is bundled as an asset; Gravity repackages it
// on the phone for each web app. Built and copied before every app build.
val webShellOut = layout.buildDirectory.dir("generated/webshell")
val copyWebShell = tasks.register<Copy>("copyWebShell") {
    dependsOn(":shell:assembleRelease")
    from(rootProject.file("shell/build/outputs/apk/release/shell-release-unsigned.apk"))
    into(webShellOut.map { it.dir("shell") })
    rename { "webshell.apk" }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(copyWebShell) }

android {
    namespace = "com.gravity.ide"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.gravity.ide"
        minSdk = 26
        targetSdk = 35
        // CI builds count up (GitHub run number) so every new APK installs as an update.
        val ciBuild = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
        versionCode = ciBuild ?: 1
        versionName = if (ciBuild != null) "0.1.$ciBuild" else "0.1.0-local"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "LLAMA_BUNDLED", llamaEnabled.toString())
        buildConfigField("boolean", "ENGINE_FAST_BUNDLED", fastEngine.toString())
        buildConfigField("boolean", "ENGINE_I8MM_BUNDLED", i8mmEngine.toString())
        buildConfigField("boolean", "IMAGE_BUNDLED", imageEngine.toString())
        buildConfigField("boolean", "VOICE_BUNDLED", voiceEngine.toString())

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
                            // llamafile's sgemm does not support Android: with it on, phones (ARM) produce
                            // garbage text. llama.cpp's own Android build and example turn it off.
                            "-DGGML_LLAMAFILE=OFF",
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

    signingConfigs {
        // Fixed DEBUG key, committed on purpose so every CI-built debug APK installs as an update
        // over the previous one (keeps the user's data). Debug builds only; a release build must
        // be signed with a private key that is never committed (see docs/SETUP.md).
        getByName("debug") {
            storeFile = file("gravity-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            storeType = "pkcs12"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
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

android.sourceSets["main"].assets.srcDir(webShellOut)
if (voiceEngine) android.sourceSets["main"].jniLibs.srcDir(voiceLibs)

dependencies {
    if (fastEngine) implementation(project(":engine-fast"))
    if (i8mmEngine) implementation(project(":engine-i8mm"))
    if (imageEngine) implementation(project(":engine-image"))
    implementation(libs.apksig)
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
    // Unpacks the offline voice packs (tar.bz2).
    implementation(libs.commons.compress)
    implementation(libs.pdfbox.android)
    implementation(libs.mlkit.text.devanagari)
    implementation(libs.androidsvg)

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
    androidTestImplementation(libs.okhttp.mockwebserver)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
