plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tcs.vehicleassistant.data.hardware"
    compileSdk = 35

    defaultConfig {
        minSdk = 34
    }
    
    useLibrary("android.car")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("io.insert-koin:koin-android:3.5.3")
    implementation(project(":core"))
    
    val cameraxVersion = "1.4.2"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
    
    implementation("com.google.mediapipe:tasks-vision:0.10.26")
    compileOnly(files("../../app/libs/sherpa-onnx.aar"))
    
    implementation("com.google.ai.edge.litert:litert:1.4.0")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
}
