buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // Kotlin 2.2 metadata needs a newer R8 than some AGP bundles ship.
        classpath("com.android.tools:r8:8.9.35")
    }
}

plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.2.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.0" apply false
}
