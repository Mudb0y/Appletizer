plugins {
    id("com.android.application")
}

android {
    namespace = "com.mudb0y.appletizer"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    // r27c, the NDK the flake provides and macho2elf's Android target expects.
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.mudb0y.appletizer"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // The engine is arm64 code; nothing else can run it.
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                arguments += "-DENGINE_DIR=${rootDir}/../work/build/android-arm64"
                // NDK r27 still links with 4 KB segment alignment unless asked.
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // The voice data is already dense, and stored files copy out faster.
    androidResources {
        noCompress += listOf("dat", "hdr", "txt")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
