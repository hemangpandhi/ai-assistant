plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.test.design.assistant.api"
    compileSdk {
        version = release(37)
    }
    defaultConfig {
        minSdk = 34
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
