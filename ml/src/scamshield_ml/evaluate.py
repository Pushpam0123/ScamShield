"""design.md section 9.5's eval protocol: "Run on the held-out test set after every stage and
record in `ml/EXPERIMENTS.md`: Macro F1, per-class P/R/F1; per-language F1 (report all 7
separately -- the aggregate hides Tamil/Telugu weakness); FPR on the genuine-bank-SMS subset
specifically -- the headline safety metric, target <= 3%; Scam-class recall, target >= 0.93;
Confusion matrix over the 13 categories; Size on disk, p50/p95 CPU latency... Parity gate: run
200 fixed samples through both the PyTorch student and the exported ONNX INT8 model. Max
absolute probability delta must be < 0.02."

"The genuine-bank-SMS subset" is `schema.py`'s existing `Row.is_hard_negative` field restricted
to genuine rows -- design.md section 9.2 defines that bucket as exactly "real transactional bank
SMS, OTP delivery messages, delivery notifications, genuine promotional offers, government
service SMS", i.e. the subset this metric means. No new field was needed.

`parity_gate` takes anything with an onnxruntime-`InferenceSession`-shaped `.run(None,
feed_dict)` method, not a concrete session type, so tests can substitute a small stub instead of
requiring a real exported model.
"""

from __future__ import annotations

import argparse
import time
from pathlib import Path
from typing import Any, Protocol

import numpy as np
import torch
from sklearn.metrics import confusion_matrix, f1_score, precision_recall_fscore_support

from .model import ScamShieldClassifier
from .schema import CATEGORIES, LANGUAGES, Row
from .train_teacher import make_loader

REPORTED_LANGUAGES = tuple(lang for lang in LANGUAGES if lang != "UNKNOWN")
TARGET_SCAM_RECALL = 0.93
TARGET_FPR_GENUINE_BANK = 0.03
PARITY_MAX_DELTA = 0.02
PARITY_SAMPLE_SIZE = 200

_CATEGORY_TO_ID = {name: i for i, name in enumerate(CATEGORIES)}


class OnnxSessionLike(Protocol):
    def run(self, output_names: list[str] | None, input_feed: dict[str, Any]) -> list[np.ndarray]: ...


@torch.no_grad()
def predict_pytorch(
    model: ScamShieldClassifier, tokenizer, rows: list[Row], max_seq_len: int = 128, batch_size: int = 64
) -> dict[str, list]:
    """Runs `rows` through `model` in its own on-disk order (`make_loader(..., shuffle=False)`
    preserves it), so the returned lists line up positionally with `rows`.
    """
    model.eval()
    loader = make_loader(rows, tokenizer, max_seq_len, batch_size, shuffle=False)
    binary_probs: list[float] = []
    binary_preds: list[int] = []
    category_preds: list[int] = []
    for batch in loader:
        binary_logits, category_logits = model(batch["input_ids"], batch["attention_mask"])
        binary_probs.extend(torch.softmax(binary_logits, dim=-1)[:, 1].tolist())
        binary_preds.extend(binary_logits.argmax(dim=-1).tolist())
        category_preds.extend(category_logits.argmax(dim=-1).tolist())
    return {"binary_probs": binary_probs, "binary_preds": binary_preds, "category_preds": category_preds}


def compute_metrics(rows: list[Row], binary_preds: list[int], category_preds: list[int]) -> dict[str, Any]:
    labels = [r.label for r in rows]
    macro_f1 = float(f1_score(labels, binary_preds, average="macro", zero_division=0))
    precision, recall, f1, _ = precision_recall_fscore_support(labels, binary_preds, labels=[0, 1], zero_division=0)
    scam_recall = float(recall[1])

    category_labels = [_CATEGORY_TO_ID[r.category] for r in rows]
    confusion = confusion_matrix(category_labels, category_preds, labels=list(range(len(CATEGORIES)))).tolist()

    per_language_f1: dict[str, float | None] = {}
    for lang in REPORTED_LANGUAGES:
        idx = [i for i, r in enumerate(rows) if r.lang == lang]
        if not idx:
            per_language_f1[lang] = None
            continue
        per_language_f1[lang] = float(
            f1_score([labels[i] for i in idx], [binary_preds[i] for i in idx], average="macro", zero_division=0)
        )

    hard_negative_idx = [i for i, r in enumerate(rows) if r.label == 0 and r.is_hard_negative]
    fpr_genuine_bank_sms = (
        sum(1 for i in hard_negative_idx if binary_preds[i] == 1) / len(hard_negative_idx)
        if hard_negative_idx
        else None
    )

    return {
        "macro_f1": macro_f1,
        "precision": {"genuine": float(precision[0]), "scam": float(precision[1])},
        "recall": {"genuine": float(recall[0]), "scam": float(recall[1])},
        "f1": {"genuine": float(f1[0]), "scam": float(f1[1])},
        "scam_recall": scam_recall,
        "meets_scam_recall_target": scam_recall >= TARGET_SCAM_RECALL,
        "fpr_genuine_bank_sms": fpr_genuine_bank_sms,
        "meets_fpr_target": fpr_genuine_bank_sms is not None and fpr_genuine_bank_sms <= TARGET_FPR_GENUINE_BANK,
        "confusion_matrix": confusion,
        "confusion_matrix_labels": list(CATEGORIES),
        "per_language_f1": per_language_f1,
    }


def model_size_bytes(path: Path) -> int:
    return Path(path).stat().st_size


def measure_latency(session: OnnxSessionLike, input_ids: np.ndarray, attention_mask: np.ndarray, n_runs: int = 20) -> dict[str, float]:
    """Single-sample (batch=1) CPU inference latency on *this* machine -- a dev-time proxy, not
    the real on-device number Phase 4's `:benchmark` Macrobenchmark measures on actual hardware.
    """
    timings_ms = []
    for _ in range(n_runs):
        start = time.perf_counter()
        session.run(None, {"input_ids": input_ids, "attention_mask": attention_mask})
        timings_ms.append((time.perf_counter() - start) * 1000)
    timings_ms.sort()
    return {
        "p50_ms": timings_ms[len(timings_ms) // 2],
        "p95_ms": timings_ms[min(int(len(timings_ms) * 0.95), len(timings_ms) - 1)],
    }


def _softmax_scam_prob(binary_logits_row: np.ndarray) -> float:
    exp = np.exp(binary_logits_row - binary_logits_row.max())
    return float((exp / exp.sum())[1])


def parity_gate(
    pytorch_model: ScamShieldClassifier,
    onnx_session: OnnxSessionLike,
    tokenizer,
    rows: list[Row],
    max_seq_len: int = 128,
    max_delta: float = PARITY_MAX_DELTA,
) -> dict[str, Any]:
    pytorch_model.eval()
    deltas = []
    for row in rows:
        encoded = tokenizer([row.text], padding="max_length", truncation=True, max_length=max_seq_len, return_tensors="pt")
        with torch.no_grad():
            torch_binary, _ = pytorch_model(encoded["input_ids"], encoded["attention_mask"])
        torch_p = torch.softmax(torch_binary, dim=-1)[0, 1].item()

        onnx_binary, _ = onnx_session.run(
            None, {"input_ids": encoded["input_ids"].numpy(), "attention_mask": encoded["attention_mask"].numpy()}
        )
        onnx_p = _softmax_scam_prob(onnx_binary[0])

        deltas.append(abs(torch_p - onnx_p))

    max_observed = max(deltas) if deltas else 0.0
    return {"n_samples": len(rows), "max_abs_delta": max_observed, "passes": max_observed < max_delta}


def main(argv: list[str] | None = None) -> int:
    import onnxruntime as ort
    from transformers import AutoTokenizer

    from .distill import student_config
    from .experiment_log import append_run
    from .jsonl_io import read_jsonl

    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dataset", required=True, type=Path)
    parser.add_argument("--student-dir", required=True, type=Path)
    parser.add_argument("--state-dict", required=True, type=Path)
    parser.add_argument("--onnx-model", type=Path, help="Exported (optionally quantized) model, for the parity gate")
    parser.add_argument("--max-seq-len", type=int, default=128)
    parser.add_argument("--parity-samples", type=int, default=PARITY_SAMPLE_SIZE)
    parser.add_argument("--experiments-log", type=Path, default=Path(__file__).resolve().parents[2] / "EXPERIMENTS.md")
    args = parser.parse_args(argv)

    tokenizer = AutoTokenizer.from_pretrained(args.student_dir)
    model = ScamShieldClassifier.from_config(student_config(tokenizer.vocab_size))
    model.load_state_dict(torch.load(args.state_dict, map_location="cpu"))

    test_rows = [r for r in read_jsonl(args.dataset) if r.is_labeled and r.split == "test"]
    predictions = predict_pytorch(model, tokenizer, test_rows, max_seq_len=args.max_seq_len)
    metrics = compute_metrics(test_rows, predictions["binary_preds"], predictions["category_preds"])

    if args.onnx_model:
        metrics["model_size_bytes"] = model_size_bytes(args.onnx_model)
        session = ort.InferenceSession(str(args.onnx_model))
        parity_rows = test_rows[: args.parity_samples]
        metrics["parity_gate"] = parity_gate(model, session, tokenizer, parity_rows, max_seq_len=args.max_seq_len)
        sample = tokenizer(["ping"], padding="max_length", truncation=True, max_length=args.max_seq_len, return_tensors="np")
        metrics["latency"] = measure_latency(session, sample["input_ids"], sample["attention_mask"])

    append_run(
        args.experiments_log,
        stage="evaluate",
        config={"max_seq_len": args.max_seq_len, "onnx_model": str(args.onnx_model) if args.onnx_model else None},
        dataset_version=str(args.dataset),
        metrics=metrics,
        wall_clock_seconds=0.0,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
