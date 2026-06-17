# Qualcomm NPU Model Compilation Guide (SA8255P / QCS8275)

To run a model directly on the Hexagon NPU of an SA8255P or QCS8275 board, you cannot use raw Hugging Face checkpoints. The model must be converted, quantized, and compiled into a QNN context binary using the Qualcomm AI Hub.

Based on the hardware constraints of the automotive platform, **Llama 3.2 1B Instruct** or **Qwen 2.5 1.5B Instruct** are highly recommended to prevent Unified Memory OOM crashes.

## Option A: Direct Download via Qualcomm Hugging Face (Easiest)

Qualcomm actively maintains pre-compiled `.tflite` / `.bin` files for their supported chips on Hugging Face.

1. Go to the Qualcomm Hugging Face repository: [huggingface.co/qualcomm](https://huggingface.co/qualcomm)
2. Search for the model, for example: `Llama-3.2-1B-Instruct-QNN`
3. Check the "Files and versions" tab. Look for an asset ending in `-htp.tflite`, `-htp.bin`, or explicitly compiled for the **SA8255P** / **v73 HTP**.
4. Download this file directly to your development machine.

## Option B: Compiling using the AI Hub Python SDK (Custom Setup)

If you have custom fine-tuned weights, or if the exact pre-compiled binary isn't available, you must compile it yourself using the Qualcomm AI Hub SDK.

### Step 1: Install the AI Hub SDK
Run this on your Linux/Mac development machine:
```bash
pip install qai-hub qai-hub-models
```

### Step 2: Authenticate
Go to [aihub.qualcomm.com](https://aihub.qualcomm.com), create an account, generate an API token in your settings, and run:
```bash
qai-hub configure --api_token <YOUR_TOKEN>
```

### Step 3: Run the Compilation Job
The SDK provides built-in recipes to automatically download a model from Hugging Face, quantize it to `w4a16`, and compile it for a specific Qualcomm SoC.

Create a python script (`compile_model.py`):
```python
import qai_hub as hub
import qai_hub_models

# 1. Select the model from the AI Hub zoo
model = qai_hub_models.models.llama_v3_2_1b_chat_quantized.Model()

# 2. Select your target device
# You can use a generic Snapdragon 8 Gen 3 (which shares the v73 HTP with SA8255P)
# or specify the QCS8275 if available in the hub target list.
device = hub.Device("Snapdragon 8 Gen 3")

# 3. Submit the compilation job to the Qualcomm cloud
compile_job = hub.submit_compile_job(
    model=model,
    device=device,
    options="--target_runtime tflite" # Output as LiteRT / TFLite compatible flatbuffer
)

# 4. Download the compiled asset
compiled_model = compile_job.get_target_model()
```
Run the script. The Qualcomm cloud will process the model (this can take 15-30 minutes for an LLM) and download a `.tflite` file to your machine. This file will contain the vital `TF_LITE_AUX` payload!

## Step 4: Pushing to the Target Hardware
Once you have the compiled model from Option A or B, push it to your automotive board:

1. Connect your SA8255P board via ADB.
2. Push the file to the app's data directory:
```bash
adb push Llama-3.2-1B-Instruct-htp.tflite /storage/emulated/10/Android/data/com.example.gemininano/files/
```
3. Open your Android App, type the exact filename into the "Model Path" setting, ensure "NPU" is selected, and hit Initialize.

> [!NOTE]
> If your SA8255P board requires raw `.so` binaries instead of a `.tflite` wrapper for the Genie runtime, change the compile option in Step 3 to `--target_runtime qnn`.
