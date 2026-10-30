plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.zeekrprobe"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zeekrprobe"
        minSdk = 28
        targetSdk = 34
        // versionCode is the CI run number (= the probe-N release tag), so self-update
        // can tell "is the latest release newer than what's installed". 0 in local builds.
        versionCode = (System.getenv("BUILD_NUMBER") ?: "0").toInt()
        versionName = "0." + (System.getenv("BUILD_NUMBER") ?: "0")
    }

    // One fixed signing key, restored by CI from a repo secret into signing/zeekrprobe.jks.
    // Every build uses it, so self-update installs cleanly over the previous version — the
    // default debug keystore is regenerated on each runner, which would break updates with
    // a signature mismatch. Override the built-in "debug" config so `assembleDebug` uses it.
    val appKey = rootProject.file("signing/zeekrprobe.jks")
    val ksPass = System.getenv("KEYSTORE_PASSWORD")
    signingConfigs.getByName("debug") {
        if (appKey.exists() && !ksPass.isNullOrBlank()) {
            storeFile = appKey
            storePassword = ksPass
            keyAlias = System.getenv("KEY_ALIAS") ?: "zeekrprobe"
            keyPassword = System.getenv("KEY_PASSWORD") ?: ksPass
        }
    }

    defaultConfig.manifestPlaceholders["appIcon"] = "@android:drawable/ic_menu_compass"
    defaultConfig.resValue("string", "app_name", "Zeekr Probe")

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
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Lifts Android's hidden-API reflection filter for this process (no root). The ECARX
    // AdaptAPI jar sits in /system/framework and its members are invisible otherwise.
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
    // FileProvider, for handing the downloaded update APK to the system installer.
    implementation("androidx.core:core:1.13.1")
}
