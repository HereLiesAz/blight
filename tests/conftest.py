"""Test fixtures shared across the suite."""
import pathlib
import pytest

@pytest.fixture(scope="session")
def stub_onnx_path(tmp_path_factory):
    """Tiny ONNX model: takes 1x3x224x224, returns deterministic logit per image (mean of pixels - 0.5)."""
    import numpy as np
    import onnx
    from onnx import helper, TensorProto

    inp = helper.make_tensor_value_info('input', TensorProto.FLOAT, [None, 3, 224, 224])
    out = helper.make_tensor_value_info('logits', TensorProto.FLOAT, [None, 1])

    # ReduceMean over (1,2,3) -> shape [N,1,1,1]; subtract 0.5; flatten to [N,1]
    rm = helper.make_node('ReduceMean', ['input'], ['m'], axes=[1, 2, 3], keepdims=1)
    half_const = helper.make_node('Constant', [], ['half'],
                                   value=helper.make_tensor('h', TensorProto.FLOAT, [1], [0.5]))
    sub = helper.make_node('Sub', ['m', 'half'], ['s'])
    flatten = helper.make_node('Flatten', ['s'], ['logits'], axis=1)

    graph = helper.make_graph([rm, half_const, sub, flatten], 'stub', [inp], [out])
    model = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 17)])
    # onnx 1.18 emits IR=11 by default; onnxruntime 1.20.1 supports up to IR=10
    model.ir_version = 10
    onnx.checker.check_model(model)
    p = tmp_path_factory.mktemp("onnx") / "stub.onnx"
    onnx.save(model, str(p))
    return str(p)
