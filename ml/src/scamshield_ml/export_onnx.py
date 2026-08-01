"""Stage D, export half (design.md section 9.4): "ONNX opset 17, dynamic axes on batch and
sequence." The exported graph's input/output names and shapes follow design.md section 6.1's
interface exactly (`input_ids`/`attention_mask` in, `binary_logits`/`category_logits` out) --
that interface is what `:analyzer:classifier` on the Android side is written against, so the
names here are a contract, not a style choice.
"""

from __future__ import annotations

import argparse
from pathlib import Path

import torch

from .model import MAX_SEQ_LEN, ScamShieldClassifier

ONNX_OPSET = 17
INPUT_NAMES = ("input_ids", "attention_mask")
OUTPUT_NAMES = ("binary_logits", "category_logits")
DYNAMIC_AXES = {
    "input_ids": {0: "batch", 1: "sequence"},
    "attention_mask": {0: "batch", 1: "sequence"},
    "binary_logits": {0: "batch"},
    "category_logits": {0: "batch"},
}


def export_to_onnx(
    model: ScamShieldClassifier, output_path: Path, seq_len: int = MAX_SEQ_LEN, opset: int = ONNX_OPSET
) -> Path:
    model.eval()
    dummy_input_ids = torch.ones((1, seq_len), dtype=torch.long)
    dummy_attention_mask = torch.ones((1, seq_len), dtype=torch.long)

    output_path = Path(output_path)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    torch.onnx.export(
        model,
        (dummy_input_ids, dummy_attention_mask),
        str(output_path),
        input_names=list(INPUT_NAMES),
        output_names=list(OUTPUT_NAMES),
        dynamic_axes=DYNAMIC_AXES,
        opset_version=opset,
        # The torch>=2.9 dynamo-based exporter needs the separate `onnxscript` package, which
        # this project doesn't otherwise depend on -- the older TorchScript-based tracer
        # (`dynamo=False`) still supports `dynamic_axes` directly and needs nothing extra.
        dynamo=False,
    )
    return output_path


def main(argv: list[str] | None = None) -> int:
    from transformers import AutoTokenizer

    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--student-dir", required=True, type=Path, help="Output dir from prune_vocab.py")
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--seq-len", type=int, default=MAX_SEQ_LEN)
    parser.add_argument("--opset", type=int, default=ONNX_OPSET)
    args = parser.parse_args(argv)

    from .distill import student_config

    tokenizer = AutoTokenizer.from_pretrained(args.student_dir)
    model = ScamShieldClassifier.from_config(student_config(tokenizer.vocab_size))
    model.load_state_dict(torch.load(args.student_dir / "student_pruned.pt", map_location="cpu"))

    export_to_onnx(model, args.output, seq_len=args.seq_len, opset=args.opset)
    print(f"wrote {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
