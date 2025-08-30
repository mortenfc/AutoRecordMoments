# pip install torch torchaudio onnx onnxruntime huggingface_hub pyannote.audio
# pip install torch torchaudio onnx onnxruntime huggingface_hub pyannote.audio
import torch
from pyannote.audio import Model
import numpy as np
import os

# You can get a token from https://hf.co/settings/tokens
HF_TOKEN = os.environ.get("HF_TOKEN")

# Load the model using the token
model = Model.from_pretrained(
    "pyannote/embedding",
    use_auth_token=HF_TOKEN
)
model.eval()

# IMPORTANT: Use exactly 1.5 seconds of audio (24000 samples @ 16kHz)
# This MUST match the REQUIRED_SAMPLES constant in your Android code.
dummy_input = torch.randn(1, 24000)

# Export with fixed size for better mobile performance
onnx_path = "speaker_embedding.onnx"
torch.onnx.export(
    model,
    dummy_input,
    onnx_path,
    input_names=["input_values"],
    output_names=["embeddings"],
    opset_version=11,
    do_constant_folding=True,
    export_params=True,
)

print(f"Model successfully exported to {os.path.abspath(onnx_path)}")

# Verify the model
import onnxruntime as ort
session = ort.InferenceSession(onnx_path)
test_input = np.random.randn(1, 24000).astype(np.float32)
outputs = session.run(None, {"input_values": test_input})
print(f"Verified ONNX model. Output shape: {outputs[0].shape}")