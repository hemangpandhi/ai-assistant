import urllib.request
import json
import os

url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.en.tar.bz2"
print(f"Downloading from {url}...")
try:
    urllib.request.urlretrieve(url, "model.tar.bz2")
    print("Download complete.")
    os.system("tar -xvf model.tar.bz2 --strip-components=1")
except Exception as e:
    print(f"Failed to download: {e}")
