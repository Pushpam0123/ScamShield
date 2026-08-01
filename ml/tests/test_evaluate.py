import numpy as np
import torch

from scamshield_ml.evaluate import (
    compute_metrics,
    measure_latency,
    model_size_bytes,
    parity_gate,
    predict_pytorch,
)
from scamshield_ml.export_onnx import export_to_onnx
from scamshield_ml.model import ScamShieldClassifier
from scamshield_ml.schema import Row

from .conftest import tiny_student_config

import onnxruntime as ort


def row(id_, label, category, lang="EN", is_hard_negative=False):
    return Row(
        id=id_,
        text="placeholder text",
        lang=lang,
        source="test",
        collected_at="2026-08-01",
        label=label,
        category=category,
        is_hard_negative=is_hard_negative,
        split="test",
    )


def test_compute_metrics_perfect_predictions():
    rows = [
        row("1", 1, "KYC_PHISHING"),
        row("2", 0, "NOT_SCAM"),
        row("3", 1, "LOTTERY_PRIZE"),
        row("4", 0, "NOT_SCAM"),
    ]
    binary_preds = [1, 0, 1, 0]
    category_preds = [0, 12, 2, 12]  # KYC_PHISHING=0, NOT_SCAM=12, LOTTERY_PRIZE=2

    metrics = compute_metrics(rows, binary_preds, category_preds)
    assert metrics["macro_f1"] == 1.0
    assert metrics["scam_recall"] == 1.0
    assert metrics["meets_scam_recall_target"] is True


def test_compute_metrics_fpr_only_counts_hard_negative_genuine_rows():
    rows = [
        row("1", 0, "NOT_SCAM", is_hard_negative=True),
        row("2", 0, "NOT_SCAM", is_hard_negative=True),
        row("3", 0, "NOT_SCAM", is_hard_negative=False),  # easy genuine, excluded from this metric
    ]
    # both hard negatives misclassified as scam, the easy one is not misclassified
    binary_preds = [1, 1, 0]
    category_preds = [12, 12, 12]

    metrics = compute_metrics(rows, binary_preds, category_preds)
    assert metrics["fpr_genuine_bank_sms"] == 1.0  # 2/2, the easy-genuine row is excluded
    assert metrics["meets_fpr_target"] is False


def test_compute_metrics_fpr_is_none_with_no_hard_negatives():
    rows = [row("1", 1, "KYC_PHISHING")]
    metrics = compute_metrics(rows, [1], [0])
    assert metrics["fpr_genuine_bank_sms"] is None
    assert metrics["meets_fpr_target"] is False


def test_compute_metrics_per_language_f1_none_for_absent_language():
    rows = [row("1", 1, "KYC_PHISHING", lang="EN"), row("2", 0, "NOT_SCAM", lang="EN")]
    metrics = compute_metrics(rows, [1, 0], [0, 12])
    assert metrics["per_language_f1"]["EN"] == 1.0
    assert metrics["per_language_f1"]["TA"] is None


def test_compute_metrics_confusion_matrix_shape():
    rows = [row("1", 1, "KYC_PHISHING"), row("2", 0, "NOT_SCAM")]
    metrics = compute_metrics(rows, [1, 0], [0, 12])
    assert len(metrics["confusion_matrix"]) == 13
    assert len(metrics["confusion_matrix"][0]) == 13


def test_model_size_bytes(tmp_path):
    f = tmp_path / "x.onnx"
    f.write_bytes(b"0" * 1234)
    assert model_size_bytes(f) == 1234


def test_predict_pytorch_preserves_row_order(tiny_tokenizer):
    config = tiny_student_config(tiny_tokenizer.vocab_size)
    model = ScamShieldClassifier.from_config(config)
    rows = [row("1", 1, "KYC_PHISHING"), row("2", 0, "NOT_SCAM"), row("3", 1, "LOTTERY_PRIZE")]
    result = predict_pytorch(model, tiny_tokenizer, rows, max_seq_len=16, batch_size=2)
    assert len(result["binary_preds"]) == 3
    assert len(result["binary_probs"]) == 3
    assert len(result["category_preds"]) == 3


class _StubOnnxSession:
    """Mimics onnxruntime's `.run(None, feed_dict)` but returns a fixed, injectable delta from
    whatever the PyTorch model would produce -- lets the parity gate's pass/fail branches be
    tested deterministically without depending on real export/quantization drift.
    """

    def __init__(self, torch_model, delta: float):
        self.torch_model = torch_model
        self.delta = delta

    def run(self, output_names, input_feed):
        ids = torch.from_numpy(input_feed["input_ids"])
        mask = torch.from_numpy(input_feed["attention_mask"])
        with torch.no_grad():
            binary_logits, category_logits = self.torch_model(ids, mask)
        binary = binary_logits.numpy().copy()
        binary[:, 1] += self.delta  # perturb the scam logit to inject a controlled probability delta
        return [binary, category_logits.numpy()]


def test_parity_gate_passes_when_delta_is_small(tiny_tokenizer):
    config = tiny_student_config(tiny_tokenizer.vocab_size)
    model = ScamShieldClassifier.from_config(config)
    rows = [row("1", 1, "KYC_PHISHING"), row("2", 0, "NOT_SCAM")]

    session = _StubOnnxSession(model, delta=0.0)
    result = parity_gate(model, session, tiny_tokenizer, rows, max_seq_len=16)
    assert result["passes"] is True
    assert result["max_abs_delta"] < 1e-6


def test_parity_gate_fails_when_delta_is_large(tiny_tokenizer):
    config = tiny_student_config(tiny_tokenizer.vocab_size)
    model = ScamShieldClassifier.from_config(config)
    rows = [row("1", 1, "KYC_PHISHING")]

    session = _StubOnnxSession(model, delta=5.0)  # large logit shift -> large probability shift
    result = parity_gate(model, session, tiny_tokenizer, rows, max_seq_len=16)
    assert result["passes"] is False
    assert result["max_abs_delta"] > 0.02


def test_measure_latency_reports_p50_le_p95(tiny_tokenizer, tmp_path):
    config = tiny_student_config(tiny_tokenizer.vocab_size)
    model = ScamShieldClassifier.from_config(config)
    onnx_path = export_to_onnx(model, tmp_path / "m.onnx", seq_len=8)
    session = ort.InferenceSession(str(onnx_path))

    ids = np.ones((1, 8), dtype=np.int64)
    mask = np.ones((1, 8), dtype=np.int64)
    latency = measure_latency(session, ids, mask, n_runs=5)
    assert latency["p50_ms"] <= latency["p95_ms"]
    assert latency["p50_ms"] >= 0
