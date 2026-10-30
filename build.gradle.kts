plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // Compose compiler is a Kotlin plugin since Kotlin 2.0; its version tracks Kotlin's.
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
