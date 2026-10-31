plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
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
        versionName = "1." + (System.getenv("BUILD_NUMBER") ?: "0")
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

    defaultConfig.resValue("string", "app_name", "Zeekr Журнал")

    buildFeatures {
        compose = true
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
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Lifts Android's hidden-API reflection filter for this process (no root). The ECARX
    // AdaptAPI jar sits in /system/framework and its members are invisible otherwise.
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
    // FileProvider, for handing the downloaded update APK to the system installer.
    implementation("androidx.core:core-ktx:1.13.1")

    // Jetpack Compose + Material 3 for the journal UI. Pinned to known stable versions
    // (no local Android SDK here to compile against — CI builds it).
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui:1.7.5")
    implementation("androidx.compose.ui:ui-graphics:1.7.5")
    implementation("androidx.compose.foundation:foundation:1.7.5")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.material:material-icons-extended:1.7.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
