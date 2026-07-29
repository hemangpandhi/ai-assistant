import java.util.Properties
import java.io.FileInputStream

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(FileInputStream(localPropertiesFile))
}
val geminiApiKey: String = localProperties.getProperty("GEMINI_API_KEY", "")

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tcs.vehicleassistant"
    compileSdk = 35

    defaultConfig {
        minSdk = 34
        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiApiKey\"")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    androidResources {
        noCompress += listOf("onnx")
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts.add("lib/**/libLiteRt.so")
            pickFirsts.add("**/libLiteRt.so")
            pickFirsts.add("**/libonnxruntime.so")
        }
    }
    useLibrary("android.car")
}

dependencies {
    implementation("org.tensorflow:tensorflow-lite:2.13.0")
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("io.insert-koin:koin-android:3.5.3")
    compileOnly(files("../app/libs/sherpa-onnx.aar"))
    compileOnly(files("../app/libs/soniqo-speech.aar"))
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.14.0")
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    val cameraxVersion = "1.3.1"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.lifecycle:lifecycle-process:2.6.2")
    implementation("androidx.camera:camera-view:$cameraxVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20210307")
}
