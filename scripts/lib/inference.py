"""ONNX-runtime wrapper for the graffiti classifier."""
from __future__ import annotations
import numpy as np
import onnxruntime as ort
from PIL import Image, UnidentifiedImageError
from io import BytesIO

IMAGENET_MEAN = np.array([0.485, 0.456, 0.406], dtype=np.float32)
IMAGENET_STD = np.array([0.229, 0.224, 0.225], dtype=np.float32)

def _preprocess(jpeg_bytes: bytes) -> np.ndarray:
    try:
        img = Image.open(BytesIO(jpeg_bytes)).convert('RGB')
    except UnidentifiedImageError as e:
        raise ValueError(f"not a decodable image: {e}") from e
    # Match training Cell 6 eval_tf: resize shorter side to 256 (preserve aspect), then center-crop 224.
    w, h = img.size
    if w < h:
        new_w, new_h = 256, round(h * 256 / w)
    else:
        new_w, new_h = round(w * 256 / h), 256
    img = img.resize((new_w, new_h))
    left = (new_w - 224) // 2
    top = (new_h - 224) // 2
    img = img.crop((left, top, left + 224, top + 224))
    arr = np.asarray(img, dtype=np.float32) / 255.0
    arr = (arr - IMAGENET_MEAN) / IMAGENET_STD
    arr = arr.transpose(2, 0, 1)[None, ...]  # NCHW
    return arr.astype(np.float32)

def _sigmoid(x: np.ndarray) -> np.ndarray:
    return 1.0 / (1.0 + np.exp(-x))

class GraffitiClassifier:
    def __init__(self, model_path: str):
        self.session = ort.InferenceSession(model_path, providers=['CPUExecutionProvider'])
        self.input_name = self.session.get_inputs()[0].name

    def score(self, jpeg_bytes: bytes) -> float:
        x = _preprocess(jpeg_bytes)
        logits = self.session.run(None, {self.input_name: x})[0]
        return float(_sigmoid(logits).reshape(-1)[0])
