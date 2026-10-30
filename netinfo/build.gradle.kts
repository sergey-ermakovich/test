plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Tiny standalone phone app: shows this device's IP, the gateway, and the peers it
// can see on the shared network, so finding the car's IP for `adb connect` is trivial.
android {
    namespace = "com.zeekrprobe.netinfo"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zeekrprobe.netinfo"
        minSdk = 24
        targetSdk = 34
        versionCode = (System.getenv("BUILD_NUMBER") ?: "0").toInt()
        versionName = "0." + (System.getenv("BUILD_NUMBER") ?: "0")
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
