plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.example.mrubyime"
    compileSdk = 34
    ndkVersion = "30.0.16248370"
    defaultConfig {
        applicationId = "com.example.mrubyime"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_static" } }
    }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    androidResources {
        // Custom key sounds must stay uncompressed so AssetManager.openFd works.
        noCompress += listOf("ogg", "wav", "mp3", "m4a")
    }

    packaging {
        // Compress the .so into the APK (smaller download). Android extracts it
        // at install time, so the installed footprint grows slightly.
        jniLibs { useLegacyPackaging = true }
    }

    signingConfigs {
        create("release") {
            // Sign the release APK with the debug key: it then installs over
            // the debug build (same signature), so the user's edited dicts/,
            // layouts/ and engine/ files survive an upgrade. Swap this for a
            // real keystore before ever publishing.
            val debugKeystore = File(System.getProperty("user.home"), ".android/debug.keystore")
            if (debugKeystore.exists()) {
                storeFile = debugKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // No lint in this project (see AGENTS.md); lintVitalAnalyzeRelease
            // is release-only and fails on this toolchain, so skip it.
            lint { checkReleaseBuilds = false; abortOnError = false }
            if (signingConfigs["release"].storeFile?.exists() == true) {
                signingConfig = signingConfigs["release"]
            }
        }
    }
}

dependencies {
    // No androidx: the IME and settings use framework widgets only, and
    // LayoutFileProvider replaces androidx.core.content.FileProvider.
}
