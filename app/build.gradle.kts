plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.media.companion"
    compileSdk = 34

    defaultConfig {
        // Package id changed from "com.media.companion" in v1.2 so the app
        // installs cleanly even where an older, differently-signed build exists.
        applicationId = "com.media.companion.sms"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "1.3"
    }

    // ── Signing ──────────────────────────────────────────────────────────
    // EVERY build must be signed with the SAME key, or Android refuses to
    // update an installed copy ("App not installed"). The key is looked up in
    // this order:
    //   1. KEYSTORE_FILE (+ KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD) env
    //      vars — used by GitHub Actions, which decodes it from a repo secret.
    //   2. debug.keystore at the project root — present in the Base44 copy.
    // If neither exists the release build is left UNSIGNED (it will not
    // install) instead of silently using a random key.
    val envKeystore = System.getenv("KEYSTORE_FILE")?.let { file(it) }
    val projectKeystore = rootProject.file("debug.keystore").takeIf { it.exists() }
    val keystore = envKeystore ?: projectKeystore

    signingConfigs {
        create("release") {
            if (keystore != null) {
                storeFile = keystore
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "android"
                keyAlias = System.getenv("KEY_ALIAS") ?: "androiddebugkey"
                keyPassword = System.getenv("KEY_PASSWORD") ?: "android"
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
        if (keystore != null) {
            getByName("debug") {
                storeFile = keystore
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "android"
                keyAlias = System.getenv("KEY_ALIAS") ?: "androiddebugkey"
                keyPassword = System.getenv("KEY_PASSWORD") ?: "android"
            }
        }
    }

    buildTypes {
        release {
            // Distribute RELEASE builds only: debug builds are flagged more
            // often by Play Protect and phone-maker security scanners.
            isMinifyEnabled = false
            isDebuggable = false
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
}
