// Template APK for apps made with Gravity's App Builder. Plain Java + framework WebView, no
// libraries, so the APK stays tiny and its manifest has nothing tied to the package name.
// Gravity copies this APK, replaces assets/www, the package name and the label, and signs it.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.gravity.webshell"
    compileSdk = 36

    defaultConfig {
        // Placeholder package; Gravity rewrites it per app. Must not be a prefix of the class package.
        applicationId = "com.gravity.webshell.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
    }
}
