buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // AGP 8.3.2 bundles an R8 whose kotlinx-metadata-jvm tops out at Kotlin 1.9/2.0 metadata,
        // so minifying code compiled by Kotlin 2.2 fails with
        // "Provided Metadata instance has version 2.2.0". Overriding R8 on the buildscript
        // classpath is the supported way to pick up a build that understands the newer metadata.
        classpath("com.android.tools:r8:8.9.35")
    }
}

plugins {
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.2.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.0" apply false
}
