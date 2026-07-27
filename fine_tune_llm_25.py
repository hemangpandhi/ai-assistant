import os
import torch
from datasets import load_dataset
from transformers import (
    AutoModelForCausalLM,
    AutoTokenizer,
    BitsAndBytesConfig,
    TrainingArguments
)
from peft import LoraConfig, get_peft_model, prepare_model_for_kbit_training
from trl import SFTTrainer

# Configuration
MODEL_NAME = "google/gemma-2b-it"  # Alternatively: "meta-llama/Llama-3.2-1B-Instruct" or "Qwen/Qwen2.5-1.5B-Instruct"
DATASET_PATH = "train_25_usecases.jsonl"
OUTPUT_DIR = "./results_25_usecases"

def train():
    print(f"Loading dataset from {DATASET_PATH}...")
    dataset = load_dataset("json", data_files=DATASET_PATH, split="train")

    print(f"Configuring QLoRA 4-bit Quantization...")
    bnb_config = BitsAndBytesConfig(
        load_in_4bit=True,
        bnb_4bit_quant_type="nf4",
        bnb_4bit_compute_dtype=torch.float16,
        bnb_4bit_use_double_quant=True,
    )

    print(f"Loading tokenizer and model: {MODEL_NAME}...")
    tokenizer = AutoTokenizer.from_pretrained(MODEL_NAME, trust_remote_code=True)
    if tokenizer.pad_token is None:
        tokenizer.pad_token = tokenizer.eos_token

    model = AutoModelForCausalLM.from_pretrained(
        MODEL_NAME,
        quantization_config=bnb_config,
        device_map="auto",
        trust_remote_code=True
    )

    model = prepare_model_for_kbit_training(model)

    peft_config = LoraConfig(
        r=16,
        lora_alpha=32,
        target_modules=["q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj"],
        lora_dropout=0.05,
        bias="none",
        task_type="CAUSAL_LM"
    )

    model = get_peft_model(model, peft_config)

    training_args = TrainingArguments(
        output_dir=OUTPUT_DIR,
        per_device_train_batch_size=4,
        gradient_accumulation_steps=4,
        optim="paged_adamw_8bit",
        save_steps=100,
        logging_steps=10,
        learning_rate=2e-4,
        fp16=True,
        num_train_epochs=3,
        warmup_ratio=0.03,
        lr_scheduler_type="cosine",
        report_to="none"
    )

    def formatting_prompts_func(example):
        messages = example['messages']
        formatted_text = ""
        for msg in messages:
            role = msg['role']
            content = msg['content']
            formatted_text += f"<|im_start|>{role}\n{content}<|im_end|>\n"
        return formatted_text

    trainer = SFTTrainer(
        model=model,
        train_dataset=dataset,
        peft_config=peft_config,
        formatting_func=formatting_prompts_func,
        max_seq_length=1024,
        tokenizer=tokenizer,
        args=training_args,
    )

    print("Starting QLoRA Fine-Tuning for 25 Use Cases...")
    trainer.train()

    print(f"Saving merged PEFT adapter model to {OUTPUT_DIR}...")
    trainer.model.save_pretrained(os.path.join(OUTPUT_DIR, "final_adapter"))
    tokenizer.save_pretrained(os.path.join(OUTPUT_DIR, "final_adapter"))
    print("Fine-tuning pipeline execution complete!")

if __name__ == "__main__":
    train()
