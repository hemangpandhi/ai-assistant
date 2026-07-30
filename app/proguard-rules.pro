# R8 configuration for the VehicleEdge Assistant release build.
#
# Everything kept below is reached through JNI, reflection or the Android manifest, i.e. via a path
# R8 cannot see. Renaming or removing any of it fails at runtime rather than at build time, so keep
# rules are deliberately broad for the native inference stack.

# ---------------------------------------------------------------------------------------------
# Native inference and speech stacks: all reached from C++ via JNI signatures.
# ---------------------------------------------------------------------------------------------
-keep class com.google.ai.edge.litertlm.** { *; }
-keep class org.vosk.** { *; }
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class org.tensorflow.lite.** { *; }
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }

# JNA (a Vosk dependency) maps Java types onto native structs by field name.
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }

# Native callbacks in our own code.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# ---------------------------------------------------------------------------------------------
# Android Automotive: android.car is a provided platform library, not packaged with the APK.
# ---------------------------------------------------------------------------------------------
-dontwarn android.car.**
-keep class android.car.** { *; }

# ---------------------------------------------------------------------------------------------
# Koin resolves singletons by type at runtime, so the injected classes must keep their names.
# ---------------------------------------------------------------------------------------------
-keep class com.tcs.vehicleassistant.di.** { *; }
-keep class com.tcs.vehicleassistant.ToolManager { *; }
-keep class com.tcs.vehicleassistant.repository.AgentOrchestrator { *; }
-keep interface com.tcs.vehicleassistant.llm.ILLMProvider { *; }
-keep class * implements com.tcs.vehicleassistant.llm.ILLMProvider { *; }
-keep interface com.tcs.vehicleassistant.hardware.IAudioManager { *; }
-keep class * implements com.tcs.vehicleassistant.hardware.IAudioManager { *; }

# The tool registry maps handler_key strings from vehicle_skills_registry.json onto handlers.
-keep class com.tcs.vehicleassistant.handlers.** { *; }

# ---------------------------------------------------------------------------------------------
# Manifest-declared components are instantiated reflectively by the framework.
# ---------------------------------------------------------------------------------------------
-keep class * extends android.app.Application { *; }
-keep class * extends android.app.Activity { *; }
-keep class * extends android.app.Service { *; }
-keep class * extends android.content.BroadcastReceiver { *; }
-keep class * extends android.service.voice.VoiceInteractionService { *; }
-keep class * extends android.service.voice.VoiceInteractionSessionService { *; }
-keep class * extends android.service.voice.VoiceInteractionSession { *; }

# ---------------------------------------------------------------------------------------------
# Kotlin runtime metadata and coroutine internals.
# ---------------------------------------------------------------------------------------------
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# ---------------------------------------------------------------------------------------------
# OkHttp / Okio ship optional platform integrations that are absent on Android.
# ---------------------------------------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---------------------------------------------------------------------------------------------
# Desktop-JVM and annotation-processor classes that leak in through the bundled AARs. None of these
# are reachable on Android; JNA carries AWT fallbacks and MediaPipe/AutoValue ship compile-time
# classes in their runtime artifacts.
# ---------------------------------------------------------------------------------------------
-dontwarn java.awt.**
-dontwarn javax.lang.model.**
-dontwarn com.google.auto.value.**
-dontwarn autovalue.shaded.**
-dontwarn com.google.mediapipe.proto.CalculatorProfileProto$CalculatorProfile
-dontwarn com.google.mediapipe.proto.GraphTemplateProto$CalculatorGraphTemplate

# ---------------------------------------------------------------------------------------------
# Keep line numbers so release crash reports stay actionable; hide the original file names.
# ---------------------------------------------------------------------------------------------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
