# pip install torch torchaudio onnx onnxruntime huggingface_hub pyannote.audio
import torch
from pyannote.audio import Model

model = Model.from_pretrained("pyannote/embedding")

model.eval() # Set the model to evaluation mode

# Create a dummy input tensor that matches the model's expected input
# The model expects audio at 16kHz. Let's create a 2-second dummy clip.
# Input shape: [batch_size, num_samples]
dummy_input = torch.randn(1, 16000 * 2)

# Define input and output names for the ONNX graph
input_names = ["input_values"]
output_names = ["output_embedding"]

# Export the model
onnx_path = "speaker_embedding.onnx"
torch.onnx.export(model,
                  dummy_input,
                  onnx_path,
                  input_names=input_names,
                  output_names=output_names,
                  opset_version=12, # A common version for mobile compatibility
                  dynamic_axes={'input_values': {1: 'num_samples'}}) # Allow variable audio length

print(f"Model converted and saved to {onnx_path}")
