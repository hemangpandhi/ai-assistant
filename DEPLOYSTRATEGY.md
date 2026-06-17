# AAOS LLM Deployment Strategy

This document outlines the official OEM integration path for bundling Large Language Models (LLMs) into an Android Automotive OS (AAOS) production build.

## Background: Why Not App Storage?

During development, models are often pushed to the application's internal or external storage (e.g., `/data/user/10/com.example.gemininano/files/`). However, in a production AAOS environment, this is an anti-pattern:

1. **Multi-User Duplication:** AAOS heavily utilizes multiple user profiles (Driver 1, Driver 2, Guest, Valet). If a 2.5GB model is stored in app storage, Android's strict sandboxing requires duplicating that file for *every single user profile*, wasting massive amounts of eMMC/UFS storage.
2. **Permission Constraints:** Cross-user file sharing requires complex content provider setups and breaks Android security sandboxes.
3. **Memory Inefficiency:** Models in user storage cannot be efficiently mmap'ed globally across the system without redundant page cache usage.

## The Official OEM Integration Path: The `/product` Partition

Under Android's Project Treble architecture, there is a strict separation of concerns:
* `/vendor`: Reserved for the SoC vendor (e.g., Qualcomm hardware HALs, DSP blobs, NPU kernel drivers).
* `/product`: Reserved for the OEM (e.g., Ford, Polestar). This is the correct location for OEM-specific apps, custom System UI, and OEM-provided machine learning models.

By baking the model into the read-only `/product` partition, you ensure that the model is shared globally across the entire operating system, regardless of which user profile is active.

### 1. Create an AOSP Prebuilt Module

In your AOSP source tree (typically under `packages/apps/YourAssistantApp/models/` or a dedicated `vendor/your_oem/models/` directory), place the model file and create an `Android.bp` file:

```blueprint
prebuilt_etc {
    name: "vehicle_llm_model_qwen",
    src: "Qwen2.5-1.5B-Instruct.litertlm",
    sub_dir: "models",
    product_specific: true, // Crucial: Places it in /product/etc/models/
}

prebuilt_etc {
    name: "vehicle_llm_model_gemma",
    src: "gemma-4-E2B-it.litertlm",
    sub_dir: "models",
    product_specific: true,
}
```

### 2. Include in the Car Make File

In your device's `device.mk` or OEM `car.mk` file, ensure the packages are built into the system image:

```makefile
# Add OEM LLM Models to the Product partition
PRODUCT_PACKAGES += \
    vehicle_llm_model_qwen \
    vehicle_llm_model_gemma
```

### 3. Update Application Logic

Modify your application's model loading logic to read directly from the system partition instead of scanning the local app sandbox. Because `/product/etc/` is universally readable by all apps (unlike `/data`), no special permissions are required.

```kotlin
// Example updated checkModelExists() logic
val SYSTEM_MODEL_DIR = "/product/etc/models/"
val modelFile = java.io.File(SYSTEM_MODEL_DIR, "Qwen2.5-1.5B-Instruct.litertlm")

if (modelFile.exists()) {
    // Model found globally. Ready for zero-copy memory mapping.
    LLMManager.initialize(context, modelFile.absolutePath)
} else {
    // Fallback to local app storage (for development/OTA updates)
}
```

## Performance Benefits of `/product` Deployment

1. **Zero Duplication:** A 2.5GB model takes exactly 2.5GB of disk space, whether there is 1 driver profile or 10.
2. **Instant Loading (Zero-Copy):** LiteRT and Qualcomm QNN use memory mapping (`mmap`). Since the file is on a read-only system partition, the Linux kernel maps the file directly into RAM with zero-copy. 
3. **Stability:** Pages backed by a read-only file can be cleanly discarded by the kernel during high memory pressure without triggering the Android Out-of-Memory (OOM) killer, as the kernel knows it can just re-read them from the disk when needed.
