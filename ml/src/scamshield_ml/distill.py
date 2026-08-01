"""Stage B (design.md section 9.4): "Student: 4 layers, hidden 384, 4 heads, intermediate 1536,
initialized from the teacher's embedding matrix (projected) and every 3rd layer."

```
L = 0.5 * KL(student_logits/tau, teacher_logits/tau) * tau^2   # tau = 3.0
  + 0.3 * CE(student_logits, hard_labels)
  + 0.2 * MSE(student_hidden_proj, teacher_hidden[3,6,9,12])
```

Two judgment calls this file has to make that design.md's formula (written for one set of
logits) leaves underdetermined for our two-head architecture -- recorded here per
implementation.md's own working rule ("choose the simpler option, implement it, record the
choice") since there is no longer a DECISIONS.md to put this in:

1. **The formula is applied once per head** (binary and category), each with the same 0.5
   KL / 0.3 CE split design.md gives, rather than picking one head as "the" logits. The 0.2 MSE
   hidden-state term is head-agnostic (it compares shared encoder hidden states, not head
   outputs) and is applied once, not once per head.
2. **"initialized from the teacher's embedding matrix (projected)" and "every 3rd layer"** means
   projecting teacher tensors (hidden size 768 in the real model, arbitrary in tests) down to the
   student's smaller hidden size. There is more than one way to do this (a trained projection,
   PCA, a fixed random projection); this file uses **adaptive average pooling** along the
   mismatched dimension(s) -- deterministic, no extra training step, and it preserves relative
   position within the original dimension the way a random projection would not. "Every 3rd
   layer" means student layer `i` (0-indexed) is seeded from teacher hidden-state index
   `3*(i+1)` (1-indexed layer output, matching the MSE term's own `[3, 6, 9, 12]`), i.e. teacher
   transformer layers 3, 6, 9, 12 (1-indexed) feed student layers 1-4.

`student_config()` returns the real design.md dimensions (hidden 384 / 4 layers / 4 heads /
intermediate 1536) by default; tests override every dimension with the tiny fixtures in
`conftest.py` so nothing here needs the real teacher checkpoint or GPU time to exercise.
"""

from __future__ import annotations

import argparse
import time
from pathlib import Path
from typing import Any

import torch
import torch.nn.functional as F
from torch import nn
from torch.utils.data import DataLoader
from transformers import BertConfig, PreTrainedTokenizerBase

from .experiment_log import append_run
from .model import ScamShieldClassifier
from .schema import Row
from .train_teacher import make_loader

STUDENT_HIDDEN_SIZE = 384
STUDENT_LAYERS = 4
STUDENT_HEADS = 4
STUDENT_INTERMEDIATE_SIZE = 1536
TEACHER_LAYER_INDICES = (3, 6, 9, 12)  # 1-indexed hidden_states positions (0 = embeddings output)

DISTILL_TEMPERATURE = 3.0
KL_WEIGHT = 0.5
CE_WEIGHT = 0.3
MSE_WEIGHT = 0.2

DEFAULT_LR = 5e-5
DEFAULT_BATCH_SIZE = 64
DEFAULT_EPOCHS = 15


def student_config(
    vocab_size: int,
    *,
    hidden_size: int = STUDENT_HIDDEN_SIZE,
    num_hidden_layers: int = STUDENT_LAYERS,
    num_attention_heads: int = STUDENT_HEADS,
    intermediate_size: int = STUDENT_INTERMEDIATE_SIZE,
    max_position_embeddings: int = 512,
    type_vocab_size: int = 2,
) -> BertConfig:
    return BertConfig(
        vocab_size=vocab_size,
        hidden_size=hidden_size,
        num_hidden_layers=num_hidden_layers,
        num_attention_heads=num_attention_heads,
        intermediate_size=intermediate_size,
        max_position_embeddings=max_position_embeddings,
        type_vocab_size=type_vocab_size,
    )


def _pool_dim(tensor: torch.Tensor, dim: int, target_size: int) -> torch.Tensor:
    if tensor.shape[dim] == target_size:
        return tensor
    moved = tensor.movedim(dim, -1)
    flat = moved.reshape(-1, 1, moved.shape[-1])
    pooled = F.adaptive_avg_pool1d(flat, target_size)
    pooled = pooled.reshape(*moved.shape[:-1], target_size)
    return pooled.movedim(-1, dim)


def project_tensor(source: torch.Tensor, target_shape: tuple[int, ...]) -> torch.Tensor:
    """Resizes `source` to `target_shape` one dimension at a time via adaptive average pooling.
    A no-op along any dimension that already matches -- e.g. projecting an embedding matrix only
    changes the hidden dimension, never the vocab dimension, since both models share a vocab
    until `prune_vocab.py` (Stage C) runs.
    """
    if len(target_shape) != source.dim():
        raise ValueError(f"rank mismatch: source {tuple(source.shape)} vs target {target_shape}")
    result = source
    for dim, size in enumerate(target_shape):
        result = _pool_dim(result, dim, size)
    return result.contiguous()


def _project_module_from(student_module: nn.Module, teacher_module: nn.Module) -> None:
    student_state = student_module.state_dict()
    teacher_state = teacher_module.state_dict()
    projected = {
        key: project_tensor(teacher_state[key], tuple(tensor.shape))
        for key, tensor in student_state.items()
        if key in teacher_state
    }
    student_module.load_state_dict(projected, strict=False)


def _transformer_layers(encoder: nn.Module) -> nn.ModuleList:
    """The student is always our own `AutoModel.from_config(BertConfig(...))`, always
    `.encoder.layer` (standard BERT module naming). The teacher can be any HF encoder design.md
    or a session's own substitution picks -- `google/muril-base-cased` is BERT-family
    (`.encoder.layer`), but e.g. DistilBERT (no separate encoder wrapper, no token-type
    embeddings) names the same thing `.transformer.layer`. Both are tried rather than assuming
    one, since `model.py`'s `from_pretrained` accepts any encoder `AutoModel` resolves.
    """
    if hasattr(encoder, "encoder") and hasattr(encoder.encoder, "layer"):
        return encoder.encoder.layer
    if hasattr(encoder, "transformer") and hasattr(encoder.transformer, "layer"):
        return encoder.transformer.layer
    raise AttributeError(f"don't know how to find transformer layers on {type(encoder).__name__}")


def init_student_from_teacher(
    student: ScamShieldClassifier,
    teacher: ScamShieldClassifier,
    teacher_layer_indices: tuple[int, ...] = TEACHER_LAYER_INDICES,
) -> None:
    """In-place. Projects the teacher's embeddings into the student's embedding module, and
    seeds each student transformer layer from the teacher layer named by
    `teacher_layer_indices` (1-indexed) -- see the module docstring for why those specific
    layers and why pooling, not training, does the resizing.
    """
    with torch.no_grad():
        _project_module_from(student.encoder.embeddings, teacher.encoder.embeddings)
        student_layers = _transformer_layers(student.encoder)
        teacher_layers = _transformer_layers(teacher.encoder)
        if len(student_layers) != len(teacher_layer_indices):
            raise ValueError(
                f"student has {len(student_layers)} layers but {len(teacher_layer_indices)} "
                "teacher layer indices were given -- these must match 1:1"
            )
        for student_layer, teacher_layer_idx in zip(student_layers, teacher_layer_indices):
            teacher_layer = teacher_layers[teacher_layer_idx - 1]  # 1-indexed -> 0-indexed
            _project_module_from(student_layer, teacher_layer)


class HiddenProjector(nn.Module):
    """A small trainable projection from the student's hidden size up to the teacher's, used
    only to compute the Stage B MSE term -- design.md's `student_hidden_proj`. Not part of the
    exported student model; discarded once distillation finishes.
    """

    def __init__(self, student_hidden: int, teacher_hidden: int):
        super().__init__()
        self.proj = nn.Linear(student_hidden, teacher_hidden)

    def forward(self, student_hidden_states: torch.Tensor) -> torch.Tensor:
        return self.proj(student_hidden_states)


def distillation_loss(
    student_logits: torch.Tensor, teacher_logits: torch.Tensor, hard_labels: torch.Tensor, tau: float = DISTILL_TEMPERATURE
) -> torch.Tensor:
    """The KL + CE portion of design.md's Stage B loss, for one head. `teacher_logits` is
    treated as a constant target (the caller must compute it under `torch.no_grad()`).
    """
    kl = F.kl_div(
        F.log_softmax(student_logits / tau, dim=-1),
        F.softmax(teacher_logits / tau, dim=-1),
        reduction="batchmean",
    ) * (tau**2)
    ce = F.cross_entropy(student_logits, hard_labels)
    return KL_WEIGHT * kl + CE_WEIGHT * ce


def hidden_mse_loss(
    student_hidden_states: tuple[torch.Tensor, ...],
    teacher_hidden_states: tuple[torch.Tensor, ...],
    projector: HiddenProjector,
    teacher_layer_indices: tuple[int, ...] = TEACHER_LAYER_INDICES,
) -> torch.Tensor:
    """`student_hidden_states[0]` is the embeddings output, `[1:]` one entry per transformer
    layer -- so student layer `i` (0-indexed) is `student_hidden_states[i + 1]`, matched against
    `teacher_hidden_states[teacher_layer_indices[i]]`.
    """
    total = torch.tensor(0.0, device=student_hidden_states[0].device)
    for i, teacher_idx in enumerate(teacher_layer_indices):
        student_proj = projector(student_hidden_states[i + 1])
        total = total + F.mse_loss(student_proj, teacher_hidden_states[teacher_idx])
    return total / len(teacher_layer_indices)


def distill_epoch(
    student: ScamShieldClassifier,
    teacher: ScamShieldClassifier,
    projector: HiddenProjector,
    loader: DataLoader,
    optimizer: torch.optim.Optimizer | None,
    teacher_layer_indices: tuple[int, ...] = TEACHER_LAYER_INDICES,
) -> float:
    student.train(optimizer is not None)
    teacher.eval()
    total_loss = 0.0
    n_batches = 0
    context = torch.enable_grad() if optimizer is not None else torch.no_grad()
    with context:
        for batch in loader:
            input_ids, attention_mask = batch["input_ids"], batch["attention_mask"]

            with torch.no_grad():
                teacher_binary, teacher_category = teacher(input_ids, attention_mask)
                teacher_hidden = teacher.hidden_states(input_ids, attention_mask)

            student_binary, student_category = student(input_ids, attention_mask)
            student_hidden = student.hidden_states(input_ids, attention_mask)

            loss = (
                distillation_loss(student_binary, teacher_binary, batch["binary_label"])
                + distillation_loss(student_category, teacher_category, batch["category_label"])
                + MSE_WEIGHT
                * hidden_mse_loss(student_hidden, teacher_hidden, projector, teacher_layer_indices)
            )

            if optimizer is not None:
                optimizer.zero_grad()
                loss.backward()
                optimizer.step()
            total_loss += loss.item()
            n_batches += 1
    return total_loss / max(n_batches, 1)


def distill(
    train_rows: list[Row],
    val_rows: list[Row],
    tokenizer: PreTrainedTokenizerBase,
    teacher: ScamShieldClassifier,
    student: ScamShieldClassifier,
    *,
    lr: float = DEFAULT_LR,
    batch_size: int = DEFAULT_BATCH_SIZE,
    epochs: int = DEFAULT_EPOCHS,
    max_seq_len: int = 128,
    teacher_layer_indices: tuple[int, ...] = TEACHER_LAYER_INDICES,
) -> dict[str, Any]:
    init_student_from_teacher(student, teacher, teacher_layer_indices)

    projector = HiddenProjector(student.hidden_size, teacher.hidden_size)
    optimizer = torch.optim.AdamW(list(student.parameters()) + list(projector.parameters()), lr=lr)

    train_loader = make_loader(train_rows, tokenizer, max_seq_len, batch_size, shuffle=True)
    val_loader = make_loader(val_rows, tokenizer, max_seq_len, batch_size, shuffle=False)

    history = []
    for epoch in range(1, epochs + 1):
        train_loss = distill_epoch(student, teacher, projector, train_loader, optimizer, teacher_layer_indices)
        val_loss = distill_epoch(student, teacher, projector, val_loader, None, teacher_layer_indices)
        history.append({"epoch": epoch, "train_loss": train_loss, "val_loss": val_loss})

    return {"history": history}


def main(argv: list[str] | None = None) -> int:
    from .train_teacher import load_split

    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dataset", required=True, type=Path)
    parser.add_argument("--teacher-dir", required=True, type=Path, help="Output dir from train_teacher.py")
    parser.add_argument(
        "--model-name",
        default=None,
        help="The teacher's base model name, as passed to train_teacher.py's --model-name. "
        "Defaults to train_teacher.TEACHER_MODEL_NAME (google/muril-base-cased) if not given.",
    )
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--lr", type=float, default=DEFAULT_LR)
    parser.add_argument("--batch-size", type=int, default=DEFAULT_BATCH_SIZE)
    parser.add_argument("--epochs", type=int, default=DEFAULT_EPOCHS)
    parser.add_argument("--max-seq-len", type=int, default=128)
    parser.add_argument(
        "--teacher-layer-indices",
        default=",".join(str(i) for i in TEACHER_LAYER_INDICES),
        help="Comma-separated 1-indexed teacher hidden-state layers to seed the 4 student layers "
        "from -- design.md's own [3,6,9,12] assumes a 12-layer teacher (muril-base-cased). A "
        "different teacher (e.g. DistilBERT's 6 layers) needs its own indices here.",
    )
    parser.add_argument("--experiments-log", type=Path, default=Path(__file__).resolve().parents[2] / "EXPERIMENTS.md")
    args = parser.parse_args(argv)
    teacher_layer_indices = tuple(int(i) for i in args.teacher_layer_indices.split(","))

    from transformers import AutoTokenizer

    from .train_teacher import TEACHER_MODEL_NAME

    model_name = args.model_name or TEACHER_MODEL_NAME
    tokenizer = AutoTokenizer.from_pretrained(args.teacher_dir)
    teacher = ScamShieldClassifier.from_pretrained(model_name)
    teacher.load_state_dict(torch.load(args.teacher_dir / "teacher.pt", map_location="cpu"))

    student = ScamShieldClassifier.from_config(student_config(tokenizer.vocab_size))

    train_rows = load_split(args.dataset, "train")
    val_rows = load_split(args.dataset, "val")

    config = {
        "stage": "distill",
        "teacher_model_name": model_name,
        "teacher_layer_indices": list(teacher_layer_indices),
        "lr": args.lr,
        "batch_size": args.batch_size,
        "epochs": args.epochs,
        "max_seq_len": args.max_seq_len,
        "student_hidden_size": STUDENT_HIDDEN_SIZE,
        "student_layers": STUDENT_LAYERS,
    }
    start = time.monotonic()
    result = distill(
        train_rows,
        val_rows,
        tokenizer,
        teacher,
        student,
        lr=args.lr,
        batch_size=args.batch_size,
        epochs=args.epochs,
        max_seq_len=args.max_seq_len,
        teacher_layer_indices=teacher_layer_indices,
    )
    wall_clock = time.monotonic() - start

    args.output_dir.mkdir(parents=True, exist_ok=True)
    torch.save(student.state_dict(), args.output_dir / "student.pt")
    tokenizer.save_pretrained(args.output_dir)

    append_run(
        args.experiments_log,
        stage="distill",
        config=config,
        dataset_version=str(args.dataset),
        metrics={"history": result["history"]},
        wall_clock_seconds=wall_clock,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
