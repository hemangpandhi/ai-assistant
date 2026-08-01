import java.util.Properties
import java.io.FileInputStream

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(FileInputStream(localPropertiesFile))
}
val geminiApiKey: String = localProperties.getProperty("GEMINI_API_KEY", "")

val coroutinesVersion = "1.8.1"
val jacocoToolVersion = "0.8.12"

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("jacoco")
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
            enableUnitTestCoverage = true
            enableAndroidTestCoverage = true
        }
        release {
            signingConfig = signingConfigs.getByName("platform")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    lint {
        // A regression that Lint can see should fail the build rather than scroll past in a log.
        abortOnError = true
        checkDependencies = true
        // Pre-existing findings are recorded in the baseline; new ones break the build.
        baseline = file("lint-baseline.xml")
        sarifReport = true
    }

    testOptions {
        unitTests {
            // Android framework stubs throw by default, which makes plain-logic tests unusable.
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
        }
    }
    packaging {
        resources {
            // The test dependencies each ship their own copy of these, which collides when the
            // androidTest APK is packaged.
            excludes += setOf(
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/DEPENDENCIES",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1"
            )
        }
        jniLibs {
            // AGP 8.5.1+: store .so uncompressed + 16 KB zip-aligned (required on 16 KB page devices).
            // Legacy compressed packaging was hiding zip-align issues but still extracted 4 KB ELF libs.
            useLegacyPackaging = false
            pickFirsts.add("lib/**/libLiteRt.so")
            pickFirsts.add("**/libLiteRt.so")
            pickFirsts.add("**/libonnxruntime.so")
            pickFirsts.add("**/libc++_shared.so")
        }
    }
    // AGP 8.3 defaults to JaCoCo 0.8.8, whose ASM cannot read the Java 21 class files the
    // toolchain produces ("Unsupported class file major version 65").
    testCoverage {
        jacocoVersion = jacocoToolVersion
    }

    useLibrary("android.car")
}

jacoco {
    toolVersion = jacocoToolVersion
}

dependencies {
    // LiteRT ships 16 KB–aligned libtensorflowlite_jni (classic TFLite 2.13–2.16 are still 4 KB).
    implementation("com.google.ai.edge.litert:litert:1.4.0")
    implementation("com.google.mediapipe:tasks-vision:0.10.26")
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
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.14.0")

    // Vosk Offline Speech Recognition for Wake Word (0.3.75+ is 16 KB ELF-aligned)
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    
    // CameraX (1.4.2+ ships 16 KB–aligned image_processing_util_jni)
    val cameraxVersion = "1.4.2"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.lifecycle:lifecycle-process:2.6.2")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:$coroutinesVersion")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20210307")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
    testImplementation("io.mockk:mockk:1.13.11")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("androidx.test:core-ktx:1.5.0")
    testImplementation("androidx.test.ext:junit:1.1.5")

    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:$coroutinesVersion")
    androidTestImplementation("io.mockk:mockk-android:1.13.11")
}

/**
 * Coverage report for the JVM unit tests. Generated classes, DI wiring and the Android-only
 * surfaces we cannot exercise off-device are excluded so the number reflects testable logic.
 */
tasks.register<JacocoReport>("jacocoUnifiedReport") {
    group = "verification"
    description = "Generates a unified JaCoCo coverage report combining debug unit tests and android tests."
    dependsOn("testDebugUnitTest", "connectedDebugAndroidTest")

    reports {
        html.required.set(true)
        xml.required.set(true)
    }

    val excludes = listOf(
        "**/R.class", "**/R$*.class", "**/BuildConfig.*", "**/Manifest*.*",
        "**/*_Impl*.*", "**/databinding/**", "**/*ViewBinding*.*"
    )
    classDirectories.setFrom(
        files(
            fileTree("${layout.buildDirectory.get()}/tmp/kotlin-classes/debug") { exclude(excludes) },
            fileTree("${layout.buildDirectory.get()}/intermediates/javac/debug/classes") { exclude(excludes) }
        )
    )
    sourceDirectories.setFrom(files("src/main/java"))
    
    executionData.setFrom(
        fileTree(layout.buildDirectory.get()) {
            include(
                "outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec",
                "outputs/code_coverage/debugAndroidTest/connected/*coverage.ec"
            )
        }
    )
}
