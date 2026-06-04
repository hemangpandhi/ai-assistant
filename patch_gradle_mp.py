import re

with open("app/build.gradle.kts", "r") as f:
    code = f.read()

code = code.replace("implementation(\"androidx.constraintlayout:constraintlayout:2.1.4\")", "implementation(\"androidx.constraintlayout:constraintlayout:2.1.4\")\n    implementation(\"com.google.mediapipe:tasks-text:0.10.14\")")

with open("app/build.gradle.kts", "w") as f:
    f.write(code)

print("build.gradle.kts patched with MediaPipe.")
