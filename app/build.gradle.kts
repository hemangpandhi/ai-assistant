import java.util.Properties
import java.io.FileInputStream

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(FileInputStream(localPropertiesFile))
}
val geminiApiKey: String = localProperties.getProperty("GEMINI_API_KEY", "")

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.tcs.vehicleassistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tcs.vehicleassistant"
        minSdk = 34 // Android 14 is required for AICore
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiApiKey\"")
        
        ndk {
            // Physical boards are arm64; AAOS emulator images are typically x86_64.
            abiFilters.addAll(listOf("arm64-v8a", "x86_64"))
        }
    }

    androidResources {
        noCompress += listOf("onnx")
    }

    signingConfigs {
        create("platform") {
            storeFile = file("platform.jks")
            storePassword = "android"
            keyAlias = "platform"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("platform")
        }
        release {
            signingConfig = signingConfigs.getByName("platform")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
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
        compose = true
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
    implementation(project(":agent-core"))
    implementation(project(":assistant-ext"))
    implementation(project(":assistant-ui"))
    implementation(project(":assistant-api"))

    val composeBom = platform("androidx.compose:compose-bom:2025.12.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3:1.5.0-alpha04")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")

    implementation("org.tensorflow:tensorflow-lite:2.13.0")
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    // Disabled tasks-text because it causes JNI collision with tasks-vision on some devices
    // implementation("com.google.mediapipe:tasks-text:0.10.14")
    
    // Dependency Injection
    implementation("io.insert-koin:koin-android:3.5.3")
    

    // Google AI Edge SDK for accessing Gemini Nano via AICore
    // Note: You must be in the AICore early access program to use this library.
    // implementation("com.google.ai.edge.aicore:aicore:1.0.0-beta01")

    // Alternatively, for cloud fallback:
    // implementation("com.google.ai.client.generativeai:generativeai:0.2.0")

    implementation(files("libs/sherpa-onnx.aar"))
    implementation(files("libs/soniqo-speech.aar"))
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.14.0")

    // Vosk Offline Speech Recognition for Wake Word
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    
    // CameraX
    val cameraxVersion = "1.3.1"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.lifecycle:lifecycle-process:2.6.2")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20210307")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.test:rules:1.5.0")
}
