plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.gemininano"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.gemininano"
        minSdk = 34 // Android 14 is required for AICore
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
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
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    
    // Google AI Edge SDK for accessing Gemini Nano via AICore
    // Note: You must be in the AICore early access program to use this library.
    // implementation("com.google.ai.edge.aicore:aicore:1.0.0-beta01")

    // Alternatively, for cloud fallback:
    // implementation("com.google.ai.client.generativeai:generativeai:0.2.0")

    // MediaPipe library for running custom Local LLMs (Gemma, Llama, Falcon, etc.)
    implementation("com.google.mediapipe:tasks-genai:0.10.35")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
