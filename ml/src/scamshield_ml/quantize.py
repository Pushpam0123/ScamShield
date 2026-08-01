"""Stage D, quantize half (design.md section 9.4): "Dynamic INT8 quantization on
MatMul/Attention; **keep embeddings and LayerNorm in FP32** -- quantizing embeddings costs 3-4
F1 points for ~2 MB."

`onnxruntime.quantization.quantize_dynamic`'s default op set (when `op_types_to_quantize` is
left `None`) includes `Gather` (the op an embedding lookup lowers to), which would quantize the
embedding table -- exactly what design.md says not to do. Passing `op_types_to_quantize=
["MatMul", "Attention"]` explicitly is what keeps `Gather` (embeddings) and `LayerNorm`
untouched; nothing else needs a separate exclusion list.
"""

from __future__ import annotations

import argparse
from pathlib import Path

from onnxruntime.quantization import QuantType, quantize_dynamic

QUANTIZED_OP_TYPES = ("MatMul", "Attention")


def quantize_int8(model_path: Path, output_path: Path) -> Path:
    output_path = Path(output_path)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    quantize_dynamic(
        model_input=str(model_path),
        model_output=str(output_path),
        op_types_to_quantize=list(QUANTIZED_OP_TYPES),
        weight_type=QuantType.QInt8,
    )
    return output_path


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--model", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args(argv)

    quantize_int8(args.model, args.output)
    before = args.model.stat().st_size
    after = args.output.stat().st_size
    print(f"{before} -> {after} bytes ({after / before:.0%})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
