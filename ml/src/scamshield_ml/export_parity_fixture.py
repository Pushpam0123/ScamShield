"""Export the on-device parity fixture (design.md section 9.5's parity gate, from the Android
side this time).

`evaluate.py`'s `parity_gate` proves the exported ONNX model agrees with the PyTorch student on
the *host*. It does not prove that the model, once bundled into the app and driven through the
DJL tokenizer + ONNX Runtime Mobile, still produces those same numbers -- and design.md is blunt
that a mismatch there is "almost always a tokenizer discrepancy". This script writes the ground
truth that the instrumented test (`ClassifierParityTest`) checks against: a fixed set of texts and
each one's `p_scam` as computed *here*, from the same `model.int8.onnx` and `tokenizer.json` that
get copied into `assets/model/`.

`p_scam` is the raw `softmax(binary_logits)[1]` -- no temperature. Parity is about the model and
the tokenizer matching across runtimes; temperature is a fixed downstream constant applied
identically on both sides, so it would only hide a discrepancy, never reveal one. (This matches
`evaluate.py:_softmax_scam_prob`, deliberately.)

The tokenizer is loaded with the `tokenizers` Rust library straight from `tokenizer.json` -- the
exact same artifact, and the exact same implementation, that DJL's `HuggingFaceTokenizer` loads on
device. Padding is forced to the full `max_seq_len` with `pad_id=0`; the attention mask zeroes it
out, so the ONNX output is identical whatever the padded length, and this keeps the input_ids
byte-for-byte what `ClassifierScoring.pack` builds in Kotlin.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np


def _softmax_scam_prob(binary_logits_row: np.ndarray) -> float:
    exp = np.exp(binary_logits_row - binary_logits_row.max())
    return float((exp / exp.sum())[1])


def build_fixture(
    onnx_model: Path,
    tokenizer_json: Path,
    texts: list[str],
    max_seq_len: int = 128,
) -> dict:
    import onnxruntime as ort
    from tokenizers import Tokenizer

    tokenizer = Tokenizer.from_file(str(tokenizer_json))
    # Mirror ClassifierScoring.pack: truncate the tail to max_seq_len, right-pad to it with [PAD]=0.
    tokenizer.enable_truncation(max_length=max_seq_len, direction="right")
    tokenizer.enable_padding(length=max_seq_len, pad_id=0, direction="right")

    session = ort.InferenceSession(str(onnx_model))
    samples = []
    for text in texts:
        encoded = tokenizer.encode(text)
        input_ids = np.asarray([encoded.ids], dtype=np.int64)
        attention_mask = np.asarray([encoded.attention_mask], dtype=np.int64)
        binary_logits, _ = session.run(None, {"input_ids": input_ids, "attention_mask": attention_mask})
        samples.append({"text": text, "p_scam": _softmax_scam_prob(binary_logits[0])})

    return {
        "model": onnx_model.name,
        "max_seq_len": max_seq_len,
        "max_delta": 0.02,  # design.md section 9.5 parity gate
        "samples": samples,
    }


def _load_texts(dataset: Path, split: str, limit: int) -> list[str]:
    from .jsonl_io import read_jsonl

    rows = [r for r in read_jsonl(dataset) if r.is_labeled and r.split == split]
    return [r.text for r in rows[:limit]]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--onnx-model", required=True, type=Path, help="The quantized model the app bundles.")
    parser.add_argument("--tokenizer", required=True, type=Path, help="tokenizer.json (the bundled one).")
    parser.add_argument("--dataset", required=True, type=Path)
    parser.add_argument("--split", default="test")
    parser.add_argument("--max-seq-len", type=int, default=128)
    parser.add_argument("--limit", type=int, default=200, help="At most this many rows from the split.")
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args(argv)

    texts = _load_texts(args.dataset, args.split, args.limit)
    if not texts:
        raise SystemExit(f"no labeled '{args.split}' rows in {args.dataset}")

    fixture = build_fixture(args.onnx_model, args.tokenizer, texts, max_seq_len=args.max_seq_len)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(fixture, ensure_ascii=False, indent=2) + "\n")
    print(f"wrote {len(fixture['samples'])} parity samples to {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
