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
        versionCode = 1
        versionName = "0.1"
    }

    // Optional: the public AOSP platform test key (platform.pk8 from AOSP, alias
    // "platformkey", password "android"). Zeekr OS userdebug builds are signed with it,
    // so an APK signed the same way gets signature-level permissions such as
    // android.car.permission.*. Drop it at tools/platform-key/androiddebugkey.jks to
    // enable the extra "platform" build; nothing happens when the file is absent.
    val platformKey = rootProject.file("tools/platform-key/androiddebugkey.jks")
    if (platformKey.exists()) {
        signingConfigs.create("platform") {
            storeFile = platformKey
            storePassword = "android"
            keyAlias = "platformkey"
            keyPassword = "android"
        }
    }

    // Name and icon differ per build type so the two installs are telling apart on the DHU.
    defaultConfig.manifestPlaceholders["appIcon"] = "@android:drawable/ic_menu_compass"
    defaultConfig.resValue("string", "app_name", "Zeekr Probe")

    buildTypes {
        release {
            isMinifyEnabled = false
            // Debug key on purpose: this is a throwaway probe, easier to sideload.
            signingConfig = signingConfigs.getByName("debug")
        }
        // Same app, platform-signed, separate package so both can be installed side by side
        // and their reports compared. Only exists when the key is present.
        if (platformKey.exists()) {
            create("platform") {
                initWith(getByName("debug"))
                applicationIdSuffix = ".sys"
                signingConfig = signingConfigs.getByName("platform")
                matchingFallbacks += listOf("debug")
                manifestPlaceholders["appIcon"] = "@android:drawable/ic_lock_lock"
                resValue("string", "app_name", "Zeekr Probe SYS")
            }
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
    // Pure-JVM ADB client: lets the app install updates over the head unit's local
    // adbd (127.0.0.1:5555) without the system installer dialog.
    implementation("dev.mobile:dadb:1.2.10")
}
