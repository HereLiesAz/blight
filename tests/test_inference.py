import numpy as np
from PIL import Image
from io import BytesIO
import pytest
from scripts.lib.inference import GraffitiClassifier

def _make_jpeg(value: int) -> bytes:
    img = Image.new('RGB', (300, 300), (value, value, value))
    buf = BytesIO(); img.save(buf, 'JPEG'); return buf.getvalue()

def test_classifier_returns_score_in_unit_interval(stub_onnx_path):
    clf = GraffitiClassifier(stub_onnx_path)
    score = clf.score(_make_jpeg(128))
    assert 0.0 <= score <= 1.0

def test_classifier_orders_dark_below_bright(stub_onnx_path):
    """Stub model: score = sigmoid(mean - 0.5). Brighter image -> higher score."""
    clf = GraffitiClassifier(stub_onnx_path)
    s_dark = clf.score(_make_jpeg(40))
    s_bright = clf.score(_make_jpeg(220))
    assert s_dark < s_bright

def test_classifier_handles_corrupt_bytes(stub_onnx_path):
    clf = GraffitiClassifier(stub_onnx_path)
    with pytest.raises(ValueError):
        clf.score(b'not-an-image')
