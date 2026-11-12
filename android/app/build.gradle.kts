import java.util.Properties

plugins {
    id("com.android.application")
}

// One key signs every build when it is available, so CI builds and local
// builds install over each other. CI writes this file from repository secrets.
val signingFile = file(System.getenv("APPLETIZER_SIGNING")
    ?: "${System.getProperty("user.home")}/.local/share/appletizer/signing.properties")
val signing = if (signingFile.isFile) Properties().apply { signingFile.inputStream().use { load(it) } } else null

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
        versionCode = System.getenv("APPLETIZER_VERSION_CODE")?.toInt() ?: 1
        versionName = System.getenv("APPLETIZER_VERSION_NAME") ?: "0.1.0"
        // The engine is arm64 code; nothing else can run it.
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                // NDK r27 still links with 4 KB segment alignment unless asked.
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
                // The iOS 10 engine is partly C++; this also packages libc++_shared.so.
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    // The converted engines come from scripts/stage-android.sh, not from CMake.
    sourceSets["main"].jniLibs.srcDir("${rootDir}/../work/build/android-arm64/jniLibs")

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

    signingConfigs {
        if (signing != null) create("appletizer") {
            storeFile = signingFile.parentFile.resolve(signing.getProperty("storeFile"))
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
    }

    buildTypes {
        val key = signingConfigs.findByName("appletizer") ?: signingConfigs.getByName("debug")
        debug {
            signingConfig = key
        }
        release {
            isMinifyEnabled = false
            signingConfig = key
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
