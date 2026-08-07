# Pixel Tablet Hardware & Inference Report

Below is the hardware configuration and the Time-to-First-Token (TTFF/TTFT) measurement for the current application running on the connected device.

## 1. Hardware Specifications

*   **Device / Model:** Pixel Tablet (AOSP on Tangorpro / `aosp_tangorpro_car`)
*   **System on Chip (SoC):** Google Tensor G2 (`gs201`)
*   **CPU:** Octa-core CPU (2x Cortex-X1, 2x Cortex-A78, 4x Cortex-A55)
*   **GPU:** ARM Mali-G710 MP7 (OpenGL ES 3.2 v1.r46p0)
*   **NPU:** Tensor Processing Unit (TPU) - *Note: Local LiteRT inference is currently executing via CPU Fallback mode on this build.*

## 2. Active Model Configuration

Based on the most recent session logs, the application successfully initialized the Google Gemma model locally:

*   **Active Model:** `gemma-4-E2B-it.litertlm` (Gemma 4 Edge 2B Instruction-Tuned)
*   **Inference Engine:** LiteRT (MediaPipe)
*   **Execution Backend:** CPU Fallback
*   **Max Tokens:** 2048

## 3. TTFF (Time-to-First-Token) Latency

During live command testing, we successfully captured the raw latency for the first token generation when running `gemma-4-E2B-it` locally on the device's CPU. 

*   **Measured TTFT:** **`622ms`**

This is an incredibly impressive metric for a 2B parameter model running via CPU fallback on the Tensor G2 processor, demonstrating the highly optimized architecture of the Gemma-E2B LiteRT binaries. 

### Recommendations for Future Optimization
1. **NPU Delegation:** While 622ms is excellent, ensuring the `.litertlm` model is explicitly built with `TfLite` operators supported by the Tensor G2 TPU would offload compute from the CPU, further reducing power consumption and potentially dropping TTFT into the `200ms` range.

<!-- connectivity check: push test from local environment -->
