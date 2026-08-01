import numpy as np
import onnxruntime as ort
import torch

from scamshield_ml.export_onnx import INPUT_NAMES, OUTPUT_NAMES, export_to_onnx
from scamshield_ml.model import NUM_CATEGORIES, ScamShieldClassifier

from .conftest import tiny_student_config


def test_export_produces_a_loadable_onnx_file(tiny_tokenizer, tmp_path):
    model = ScamShieldClassifier.from_config(tiny_student_config(tiny_tokenizer.vocab_size))
    output = export_to_onnx(model, tmp_path / "model.onnx", seq_len=8)
    assert output.exists()

    session = ort.InferenceSession(str(output))
    input_names = {i.name for i in session.get_inputs()}
    output_names = {o.name for o in session.get_outputs()}
    assert input_names == set(INPUT_NAMES)
    assert output_names == set(OUTPUT_NAMES)


def test_export_handles_dynamic_batch_and_sequence_length(tiny_tokenizer, tmp_path):
    model = ScamShieldClassifier.from_config(tiny_student_config(tiny_tokenizer.vocab_size))
    output = export_to_onnx(model, tmp_path / "model.onnx", seq_len=8)
    session = ort.InferenceSession(str(output))

    for batch, seq in [(1, 8), (3, 5), (2, 20)]:
        ids = np.ones((batch, seq), dtype=np.int64)
        mask = np.ones((batch, seq), dtype=np.int64)
        binary_logits, category_logits = session.run(None, {"input_ids": ids, "attention_mask": mask})
        assert binary_logits.shape == (batch, 2)
        assert category_logits.shape == (batch, NUM_CATEGORIES)


def test_export_matches_pytorch_forward(tiny_tokenizer, tmp_path):
    model = ScamShieldClassifier.from_config(tiny_student_config(tiny_tokenizer.vocab_size))
    model.eval()
    output = export_to_onnx(model, tmp_path / "model.onnx", seq_len=8)

    ids = torch.randint(0, tiny_tokenizer.vocab_size, (2, 8))
    mask = torch.ones((2, 8), dtype=torch.long)
    with torch.no_grad():
        torch_binary, torch_category = model(ids, mask)

    session = ort.InferenceSession(str(output))
    onnx_binary, onnx_category = session.run(
        None, {"input_ids": ids.numpy(), "attention_mask": mask.numpy()}
    )

    assert np.allclose(torch_binary.numpy(), onnx_binary, atol=1e-4)
    assert np.allclose(torch_category.numpy(), onnx_category, atol=1e-4)
