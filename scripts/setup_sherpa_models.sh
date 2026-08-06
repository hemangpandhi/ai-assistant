#!/bin/bash

echo "===================================================="
echo "   Sherpa-ONNX Model Downloader & Patcher"
echo "===================================================="
echo ""
echo "Select which Sherpa models to download and push to the device:"
echo "1) Wakeword (KWS) - Streaming Zipformer CTC (~20MB)"
echo "2) Speech-To-Text (STT) - Whisper Tiny INT8 (~135MB)"
echo "3) Both KWS and STT"
echo "4) Exit"
echo ""

read -p "Enter choice [1-4]: " choice

KWS_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2"
KWS_DIR="sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01"

STT_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.en.tar.bz2"
STT_DIR="sherpa-onnx-whisper-tiny.en"

download_kws() {
    echo "Downloading KWS Zipformer Transducer model..."
    wget -q --show-progress $KWS_URL -O kws.tar.bz2
    tar -xjf kws.tar.bz2
    
    echo "Pushing KWS models to /data/local/tmp/kws/ ..."
    adb shell mkdir -p /data/local/tmp/kws
    
    # SherpaKwsManager.kt expects 'encoder.onnx', 'decoder.onnx', 'joiner.onnx', 'tokens.txt', and 'bpe.model'
    adb push $KWS_DIR/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx /data/local/tmp/kws/encoder.onnx
    adb push $KWS_DIR/decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx /data/local/tmp/kws/decoder.onnx
    adb push $KWS_DIR/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx /data/local/tmp/kws/joiner.onnx
    adb push $KWS_DIR/tokens.txt /data/local/tmp/kws/tokens.txt
    adb push $KWS_DIR/bpe.model /data/local/tmp/kws/bpe.model
    
    adb shell chmod 755 /data/local/tmp/kws
    adb shell chmod 644 /data/local/tmp/kws/*
    
    rm -rf kws.tar.bz2 $KWS_DIR
    echo "KWS model installed successfully!"
}

download_stt() {
    echo "Downloading STT Whisper Tiny INT8 model..."
    wget -q --show-progress $STT_URL -O stt.tar.bz2
    tar -xjf stt.tar.bz2
    
    echo "Pushing STT models to /data/local/tmp/stt/ ..."
    adb shell mkdir -p /data/local/tmp/stt
    
    adb push $STT_DIR/tiny.en-encoder.int8.onnx /data/local/tmp/stt/
    adb push $STT_DIR/tiny.en-decoder.int8.onnx /data/local/tmp/stt/
    adb push $STT_DIR/tiny.en-tokens.txt /data/local/tmp/stt/
    
    adb shell chmod 755 /data/local/tmp/stt
    adb shell chmod 644 /data/local/tmp/stt/*
    
    rm -rf stt.tar.bz2 $STT_DIR
    echo "STT model installed successfully!"
}

case $choice in
    1)
        download_kws
        ;;
    2)
        download_stt
        ;;
    3)
        download_kws
        download_stt
        ;;
    4)
        echo "Exiting."
        exit 0
        ;;
    *)
        echo "Invalid option."
        exit 1
        ;;
esac

echo ""
echo "Done! The models are now on the device."
echo "If the app is running, force stop it and reopen to load the new models."
