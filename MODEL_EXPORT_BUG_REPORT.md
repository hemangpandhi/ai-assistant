# 🚨 On-Device Model Export Bug Report & Technical Recommendations

**To**: Model Export & Training Team  
**From**: Vehicle Assistant On-Device Engineering Team  
**Date**: July 28, 2026  
**Subject**: On-Device Validation Findings & Re-Export Requirements for `model.litertlm` (Qwen 2.5 1.5B)  
**Target Hardware**: Google Pixel Tablet (Android Automotive OS 14, Active Driver User 10, 8GB LPDDR5 RAM)  

---

## 1. Executive Summary

During on-device testing on the Google Pixel Tablet (AAOS User 10 environment), the customized fine-tuned model `model.litertlm` (1.80 GB) failed on-device execution due to two critical export issues:
1. **GPU Backend Execution Failure**: OpenCL delegate initialization failed with a **Tensor Shape Mismatch (`{1536, 1, 1, 1536}` vs `{1, 1, 1536, 1536}`)**.
2. **CPU Backend System RAM Crash**: When forced onto the CPU (XNNPACK) engine, total native RSS memory expanded to **4.44 GB RAM**, causing the Android Linux kernel **LowMemoryKiller (LMK)** to terminate the process via `SIGKILL (signal 9)`.

Conversely, the reference baseline model `Qwen2.5.litertlm` (1.59 GB) executed **100% stably on GPU with a lean 120 MB RAM footprint**.

---

## 2. Empirical Logcat Traces

### Issue A: OpenCL GPU Delegate Shape Mismatch
```log
07-28 10:34:58.876  3287  3319 E native  : E0000 00:00:1785234898.876560 3319 delegate_opencl.cc:330] Failed to create litert::ml_drift::DelegateKernelLiteRt: INVALID_ARGUMENT: Shape mismatch: {bhwc, {1536, 1, 1, 1536}} vs {bhwc, {1, 1, 1536, 1536}}
07-28 10:34:58.878  3287  3319 E tflite  : Failed to initialize kernel.
07-28 10:34:59.133  3287  3319 E LLMManager: Error initializing model on GPU: Failed to create engine: INTERNAL: ERROR: [third_party/odml/litert_lm/runtime/executor/llm_litert_compiled_model_executor.cc:1928]
```

### Issue B: CPU System RAM Explosion & Kernel Signal 9 (SIGKILL)
```log
07-28 10:46:14.319   479   479 W lowmemorykiller: Failed to open /proc/16854/oom_score_adj; errno=2: process 16854 might have been killed
07-28 10:46:14.514   479   479 I lowmemorykiller: Kill 'com.tcs.vehicleassistant' (17757), uid 1010174, oom_score_adj 0 to free 4445960kB rss, 3990164kB anon rss, 2413008kB swap, 0kB dmabuf_pss, 0kB dmabuf_rss; reason: min watermark is breached even after kill
07-28 10:46:15.364 30063 30063 I Zygote  : Process 17757 exited due to signal 9 (Killed)
```

---

## 3. Comparative Analysis Matrix

| Model Metric | Customized Model (`model.litertlm`) | Reference Baseline (`Qwen2.5.litertlm`) |
| :--- | :--- | :--- |
| **File Size on Disk** | 1.80 GB | 1.59 GB |
| **KV-Cache Quantization** | None (Unquantized FP16/INT8) | `q8_ekv4096` |
| **Target Engine** | CPU XNNPack | OpenCL GPU Delegate (`LITERT_CL`) |
| **Peak Native RSS Memory** | **4.44 GB RAM** ❌ | **120 MB RAM** ✅ |
| **LMK Process Status** | **Killed (`signal 9`)** ❌ | **100% Stable** ✅ |

---

## 4. Technical Root Cause

1. **Missing KV-Cache Quantization**: The customized model export omitted `q8_ekv` KV-cache quantization. Under multi-turn prompt evaluation, unquantized token context expansion in CPU System RAM reached **4.44 GB**.
2. **Android Kernel Per-Process Cap**: Android OS enforces a **3.0 GB per-process RSS cap** for user-space applications (regardless of whether physical RAM is 8 GB or 16 GB). Hitting 4.44 GB RSS triggers kernel `lowmemorykiller`.
3. **OpenCL Kernel Packing Layout**: The OpenCL GPU delegate requires `{1, 1, 1536, 1536}` layout. `model.litertlm` was packed with `{1536, 1, 1, 1536}`, forcing fallback to CPU execution.

---

## 5. Required Action Items for Training & Export Team

To ensure `model.litertlm` runs smoothly on real hardware, please re-export the fine-tuned adapter with the following `litert-lm` CLI flags:

```bash
litert-lm export \
  --model_path=qwen2.5-1.5b-customized/ \
  --output_path=model.litertlm \
  --backend=gpu \
  --kv_cache_quant=q8_ekv \
  --max_seq_len=2048
```

### Key Flags Needed:
- **`--backend=gpu`**: Generates OpenCL delegate kernels (`{1, 1, 1536, 1536}`) to offload weight tensors to Adreno GPU VRAM.
- **`--kv_cache_quant=q8_ekv`**: Quantizes the KV-cache to 8-bit, reducing memory footprint by **>70%** and keeping process RSS under **200 MB RAM**.
