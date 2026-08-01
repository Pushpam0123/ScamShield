"""Stage E (design.md section 9.4): "Fit temperature `T` on the validation set by minimizing
NLL; store `T` in `meta.json` alongside the model." Ties directly to design.md section 6.2's
score mapping -- `p_scam = softmax(binary_logits)[1]` is only meaningful once passed through
this calibration, since "uncalibrated distilled+quantized models are systematically
overconfident, and the fusion policy's thresholds assume calibrated probabilities."

Calibration targets the **binary head only** -- section 6.2 defines exactly one `T` for exactly
one score, `p_scam`. The category head has its own, separate confidence rule (section 6.2:
"Category is emitted only when `max(softmax(category_logits)) >= 0.45`"), not a temperature.

Temperature is optimized in log-space (`log_temperature`, exponentiated before use) so the
optimizer can't ever drive `T` to zero or negative, which would make `logits / T` blow up or
flip sign.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import torch
import torch.nn.functional as F

DEFAULT_MAX_ITER = 50


def nll(logits: torch.Tensor, labels: torch.Tensor, temperature: float = 1.0) -> float:
    return float(F.cross_entropy(logits / temperature, labels).item())


def fit_temperature(logits: torch.Tensor, labels: torch.Tensor, max_iter: int = DEFAULT_MAX_ITER) -> float:
    """Returns the scalar `T` minimizing NLL of `logits / T` against `labels`, via LBFGS (the
    standard choice for this one-parameter convex-ish problem; see Guo et al. 2017's original
    temperature scaling paper, which this follows directly).
    """
    logits = logits.detach()
    labels = labels.detach()
    log_temperature = torch.zeros(1, requires_grad=True)
    # `line_search_fn="strong_wolfe"` is not optional here -- without it, LBFGS's default line
    # search has no step-size safeguard and can take one catastrophic jump (observed: T~0.89 ->
    # T~3e-8 in a single step on a real small validation set), overflowing the loss and getting
    # permanently stuck driving T toward 0 afterward. Wolfe conditions bound the step so this
    # can't happen; this is also what the original temperature-scaling reference implementation
    # (Guo et al. 2017) uses.
    optimizer = torch.optim.LBFGS([log_temperature], lr=0.05, max_iter=max_iter, line_search_fn="strong_wolfe")

    def closure() -> torch.Tensor:
        optimizer.zero_grad()
        temperature = log_temperature.exp()
        loss = F.cross_entropy(logits / temperature, labels)
        loss.backward()
        return loss

    optimizer.step(closure)
    return float(log_temperature.exp().item())


def write_meta_json(path: Path, temperature: float, **extra: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps({"temperature": temperature, **extra}, indent=2, sort_keys=True))


def main(argv: list[str] | None = None) -> int:
    from transformers import AutoTokenizer

    from .distill import student_config
    from .model import ScamShieldClassifier
    from .train_teacher import load_split, make_loader

    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dataset", required=True, type=Path)
    parser.add_argument("--student-dir", required=True, type=Path)
    parser.add_argument("--state-dict", required=True, type=Path, help="e.g. student_pruned.pt")
    parser.add_argument("--output", required=True, type=Path, help="Where to write meta.json")
    parser.add_argument("--max-seq-len", type=int, default=128)
    args = parser.parse_args(argv)

    tokenizer = AutoTokenizer.from_pretrained(args.student_dir)
    model = ScamShieldClassifier.from_config(student_config(tokenizer.vocab_size))
    model.load_state_dict(torch.load(args.state_dict, map_location="cpu"))
    model.eval()

    val_rows = load_split(args.dataset, "val")
    loader = make_loader(val_rows, tokenizer, args.max_seq_len, batch_size=64, shuffle=False)

    all_logits, all_labels = [], []
    with torch.no_grad():
        for batch in loader:
            binary_logits, _ = model(batch["input_ids"], batch["attention_mask"])
            all_logits.append(binary_logits)
            all_labels.append(batch["binary_label"])
    logits = torch.cat(all_logits)
    labels = torch.cat(all_labels)

    temperature = fit_temperature(logits, labels)
    write_meta_json(
        args.output,
        temperature,
        nll_before=nll(logits, labels, 1.0),
        nll_after=nll(logits, labels, temperature),
        val_rows=len(val_rows),
    )
    print(f"T={temperature:.4f}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
