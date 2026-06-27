#!/bin/bash

echo "===================================================="
echo "   Android Automotive LLM - Model Downloader   "
echo "===================================================="
echo ""
echo "Select a model to download to the current directory:"
echo "1) SmolLM-135M-Instruct (150MB, Very Fast, 1K Context)"
echo "2) Qwen2.5-1.5B-Instruct (1.6GB, Balanced, 4K Context)"
echo "3) Gemma-2B-IT GPU INT4 (2.5GB, Premium, 1K Context)"
echo "4) Exit"
echo ""

read -p "Enter choice [1-4]: " choice

case $choice in
    1)
        echo "Downloading SmolLM-135M-Instruct.task..."
        wget https://huggingface.co/litert-community/SmolLM-135M-Instruct/resolve/main/SmolLM-135M-Instruct_multi-prefill-seq_q8_ekv1280.task -O SmolLM-135M-Instruct.task
        echo "Done!"
        ;;
    2)
        echo "Downloading Qwen2.5-1.5B-Instruct.litertlm..."
        wget https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm -O Qwen2.5-1.5B-Instruct.litertlm
        echo "Done!"
        ;;
    3)
        echo "Downloading gemma-2b-it-gpu-int4.bin..."
        wget https://huggingface.co/mikkir/gemma-2b-it-gpu-int4.bin/resolve/main/gemma-2b-it-gpu-int4.bin -O gemma-2b-it-gpu-int4.bin
        echo "Done!"
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
echo "To push this model to the Android Automotive Emulator or Device, run:"
echo "./setup_model.sh"
