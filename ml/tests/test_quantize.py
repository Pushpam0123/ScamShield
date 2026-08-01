import numpy as np
import onnx
import onnxruntime as ort

from scamshield_ml.export_onnx import export_to_onnx
from scamshield_ml.model import ScamShieldClassifier
from scamshield_ml.quantize import quantize_int8

from .conftest import tiny_student_config


def _bigger_student_config(vocab_size: int):
    # A somewhat larger tiny config than the shared fixture -- INT8 quantization needs weight
    # matrices with enough elements to actually produce a measurable size difference; the
    # smallest fixture config is too small for the on-disk difference to be reliable.
    return tiny_student_config(vocab_size).__class__(
        vocab_size=vocab_size,
        hidden_size=64,
        num_hidden_layers=2,
        num_attention_heads=4,
        intermediate_size=128,
        max_position_embeddings=64,
        type_vocab_size=2,
    )


def test_quantized_model_is_smaller_on_disk(tiny_tokenizer, tmp_path):
    model = ScamShieldClassifier.from_config(_bigger_student_config(tiny_tokenizer.vocab_size))
    fp32_path = export_to_onnx(model, tmp_path / "model.onnx", seq_len=8)
    int8_path = quantize_int8(fp32_path, tmp_path / "model.int8.onnx")

    assert int8_path.stat().st_size < fp32_path.stat().st_size


def test_quantized_model_still_runs_and_matches_shapes(tiny_tokenizer, tmp_path):
    model = ScamShieldClassifier.from_config(_bigger_student_config(tiny_tokenizer.vocab_size))
    fp32_path = export_to_onnx(model, tmp_path / "model.onnx", seq_len=8)
    int8_path = quantize_int8(fp32_path, tmp_path / "model.int8.onnx")

    session = ort.InferenceSession(str(int8_path))
    ids = np.ones((2, 8), dtype=np.int64)
    mask = np.ones((2, 8), dtype=np.int64)
    binary_logits, category_logits = session.run(None, {"input_ids": ids, "attention_mask": mask})
    assert binary_logits.shape == (2, 2)
    assert category_logits.shape == (2, 13)


def test_quantized_model_keeps_embedding_gather_in_fp32(tiny_tokenizer, tmp_path):
    model = ScamShieldClassifier.from_config(_bigger_student_config(tiny_tokenizer.vocab_size))
    fp32_path = export_to_onnx(model, tmp_path / "model.onnx", seq_len=8)
    int8_path = quantize_int8(fp32_path, tmp_path / "model.int8.onnx")

    quantized = onnx.load(str(int8_path))
    gather_initializer_names = {
        inp
        for node in quantized.graph.node
        if node.op_type == "Gather"
        for inp in node.input
    }
    initializer_dtypes = {init.name: init.data_type for init in quantized.graph.initializer}
    # int8 == onnx.TensorProto.INT8 (3); embedding weight initializers feeding a Gather must
    # not have been converted to it.
    for name in gather_initializer_names:
        if name in initializer_dtypes:
            assert initializer_dtypes[name] != onnx.TensorProto.INT8
