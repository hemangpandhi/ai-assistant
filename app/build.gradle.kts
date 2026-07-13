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
}

android {
    namespace = "com.tcs.vehicleassistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tcs.vehicleassistant"
        minSdk = 34 // Android 14 is required for AICore
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiApiKey\"")
        
        ndk {
            abiFilters.add("arm64-v8a")
        }
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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
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
    implementation("com.google.mediapipe:tasks-text:0.10.14")
    
    // Dependency Injection
    implementation("io.insert-koin:koin-android:3.5.3")
    

    // Google AI Edge SDK for accessing Gemini Nano via AICore
    // Note: You must be in the AICore early access program to use this library.
    // implementation("com.google.ai.edge.aicore:aicore:1.0.0-beta01")

    // Alternatively, for cloud fallback:
    // implementation("com.google.ai.client.generativeai:generativeai:0.2.0")

    implementation("com.google.ai.edge.litertlm:litertlm-android:latest.release")

    // Vosk Offline Speech Recognition for Wake Word
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20210307")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.test:rules:1.5.0")
}
