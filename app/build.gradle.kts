plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.media.companion"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.media.companion"
        minSdk = 26
        targetSdk = 34
        versionCode = 24
        versionName = "2.13"
    }

    // Two editions of the same app (same package, so they update each other):
    //  standard - SMS only. No overlay permission, no Accessibility service, so
    //             Google Pay and other payment apps do not flag it for those.
    //  relay    - for the admin's dedicated WhatsApp relay phone only.
    flavorDimensions += "edition"
    productFlavors {
        create("standard") { dimension = "edition" }
        create("relay") { dimension = "edition" }
    }

    // Release signing key comes from CI secrets (never committed — this repo is
    // public). Without it the release build is simply left unsigned and CI
    // falls back to the debug APK exactly as before.
    val ksPath = System.getenv("RELEASE_KEYSTORE_PATH")
    signingConfigs {
        if (!ksPath.isNullOrEmpty()) {
            create("release") {
                storeFile = file(ksPath)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
            if (!ksPath.isNullOrEmpty()) signingConfig = signingConfigs.getByName("release")
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
    // Schedules the optional 12-hour follow-up SMS so it survives Doze and
    // reboots without needing an always-exact alarm.
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}