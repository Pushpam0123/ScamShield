"""Stage A (design.md section 9.4): "Fine-tune `google/muril-base-cased`, two heads,
class-weighted cross-entropy (scam class weight 1.5 to bias toward recall). lr 2e-5, batch 32,
4 epochs, early stop on val macro-F1."

The class weight applies to the binary head only -- "bias toward recall" is specifically about
not missing scams, which is a binary-head concern; the category head's 13-way loss is
unweighted plain cross-entropy. The two head losses are summed with equal weight (design.md
doesn't specify a ratio, so per implementation.md's own working rule -- "choose the simpler
option ... record it" -- this file records that choice here rather than in a since-deleted
DECISIONS.md). Early stopping watches binary macro-F1 on the validation split, matching the
metric design.md section 9.5 treats as the headline number.

`train()` takes an already-constructed tokenizer and model rather than a model-name string, so
tests can pass in the tiny from-scratch fixtures in `tests/conftest.py` without any network
access or the real ~1 GB `google/muril-base-cased` download. `main()` is the real entry point
and does default to that real model name.
"""

from __future__ import annotations

import argparse
import time
from pathlib import Path
from typing import Any

import torch
from sklearn.metrics import f1_score
from torch.utils.data import DataLoader, Dataset
from transformers import AutoTokenizer, PreTrainedTokenizerBase

from .experiment_log import append_run
from .jsonl_io import read_jsonl
from .model import MAX_SEQ_LEN, ScamShieldClassifier
from .schema import CATEGORIES, Row

TEACHER_MODEL_NAME = "google/muril-base-cased"
DEFAULT_LR = 2e-5
DEFAULT_BATCH_SIZE = 32
DEFAULT_EPOCHS = 4
DEFAULT_SCAM_CLASS_WEIGHT = 1.5

_CATEGORY_TO_ID = {name: i for i, name in enumerate(CATEGORIES)}


class RowDataset(Dataset):
    def __init__(self, rows: list[Row], tokenizer: PreTrainedTokenizerBase, max_seq_len: int):
        self.rows = rows
        self.tokenizer = tokenizer
        self.max_seq_len = max_seq_len

    def __len__(self) -> int:
        return len(self.rows)

    def __getitem__(self, idx: int) -> dict[str, Any]:
        row = self.rows[idx]
        return {"text": row.text, "label": row.label, "category": _CATEGORY_TO_ID[row.category]}


def collate(batch: list[dict[str, Any]], tokenizer: PreTrainedTokenizerBase, max_seq_len: int) -> dict[str, torch.Tensor]:
    # design.md section 2's own truncation rule -- "truncate from the *end*... the link is
    # usually early" -- is a *message-normalization* concern applied before this stage ever
    # sees the text (`ingest`, not `ml/`). Here it is just an ordinary tokenizer truncation to
    # the model's fixed max sequence length.
    encoded = tokenizer(
        [item["text"] for item in batch],
        padding=True,
        truncation=True,
        max_length=max_seq_len,
        return_tensors="pt",
    )
    return {
        "input_ids": encoded["input_ids"],
        "attention_mask": encoded["attention_mask"],
        "binary_label": torch.tensor([item["label"] for item in batch], dtype=torch.long),
        "category_label": torch.tensor([item["category"] for item in batch], dtype=torch.long),
    }


def load_split(dataset_path: Path, split_name: str) -> list[Row]:
    rows = read_jsonl(dataset_path)
    return [r for r in rows if r.is_labeled and r.split == split_name]


def make_loader(
    rows: list[Row], tokenizer: PreTrainedTokenizerBase, max_seq_len: int, batch_size: int, shuffle: bool
) -> DataLoader:
    return DataLoader(
        RowDataset(rows, tokenizer, max_seq_len),
        batch_size=batch_size,
        shuffle=shuffle,
        collate_fn=lambda batch: collate(batch, tokenizer, max_seq_len),
    )


def run_epoch(
    model: ScamShieldClassifier,
    loader: DataLoader,
    binary_criterion: torch.nn.Module,
    category_criterion: torch.nn.Module,
    optimizer: torch.optim.Optimizer | None,
) -> float:
    """One pass over `loader`. Trains (backprop + step) when `optimizer` is given; otherwise a
    pure forward pass for validation. Returns the mean total loss.
    """
    model.train(optimizer is not None)
    total_loss = 0.0
    n_batches = 0
    context = torch.enable_grad() if optimizer is not None else torch.no_grad()
    with context:
        for batch in loader:
            binary_logits, category_logits = model(batch["input_ids"], batch["attention_mask"])
            loss = binary_criterion(binary_logits, batch["binary_label"]) + category_criterion(
                category_logits, batch["category_label"]
            )
            if optimizer is not None:
                optimizer.zero_grad()
                loss.backward()
                optimizer.step()
            total_loss += loss.item()
            n_batches += 1
    return total_loss / max(n_batches, 1)


@torch.no_grad()
def binary_macro_f1(model: ScamShieldClassifier, loader: DataLoader) -> float:
    model.eval()
    preds: list[int] = []
    labels: list[int] = []
    for batch in loader:
        binary_logits, _ = model(batch["input_ids"], batch["attention_mask"])
        preds.extend(binary_logits.argmax(dim=-1).tolist())
        labels.extend(batch["binary_label"].tolist())
    if not labels:
        return 0.0
    return float(f1_score(labels, preds, average="macro", zero_division=0))


def train(
    train_rows: list[Row],
    val_rows: list[Row],
    tokenizer: PreTrainedTokenizerBase,
    model: ScamShieldClassifier,
    *,
    lr: float = DEFAULT_LR,
    batch_size: int = DEFAULT_BATCH_SIZE,
    epochs: int = DEFAULT_EPOCHS,
    scam_class_weight: float = DEFAULT_SCAM_CLASS_WEIGHT,
    max_seq_len: int = MAX_SEQ_LEN,
) -> dict[str, Any]:
    """Runs up to `epochs` epochs, keeping the state dict from whichever epoch had the best val
    binary macro-F1 (early stopping by "just don't use a later, worse epoch" rather than an
    early `break` -- with as few as 4 epochs there's no wall-clock benefit to stopping the loop
    early, and keeping every epoch's val score is what makes `history` in the returned dict
    actually useful for `EXPERIMENTS.md`).
    """
    train_loader = make_loader(train_rows, tokenizer, max_seq_len, batch_size, shuffle=True)
    val_loader = make_loader(val_rows, tokenizer, max_seq_len, batch_size, shuffle=False)

    binary_weight = torch.tensor([1.0, scam_class_weight])
    binary_criterion = torch.nn.CrossEntropyLoss(weight=binary_weight)
    category_criterion = torch.nn.CrossEntropyLoss()
    optimizer = torch.optim.AdamW(model.parameters(), lr=lr)

    history = []
    best_f1 = -1.0
    best_state = None
    for epoch in range(1, epochs + 1):
        train_loss = run_epoch(model, train_loader, binary_criterion, category_criterion, optimizer)
        val_f1 = binary_macro_f1(model, val_loader)
        history.append({"epoch": epoch, "train_loss": train_loss, "val_macro_f1": val_f1})
        if val_f1 > best_f1:
            best_f1 = val_f1
            best_state = {k: v.detach().clone() for k, v in model.state_dict().items()}

    if best_state is not None:
        model.load_state_dict(best_state)

    return {"history": history, "best_val_macro_f1": best_f1}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dataset", required=True, type=Path, help="Split JSONL (rows carry a `split` field)")
    parser.add_argument("--model-name", default=TEACHER_MODEL_NAME)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--lr", type=float, default=DEFAULT_LR)
    parser.add_argument("--batch-size", type=int, default=DEFAULT_BATCH_SIZE)
    parser.add_argument("--epochs", type=int, default=DEFAULT_EPOCHS)
    parser.add_argument("--scam-class-weight", type=float, default=DEFAULT_SCAM_CLASS_WEIGHT)
    parser.add_argument("--max-seq-len", type=int, default=MAX_SEQ_LEN)
    parser.add_argument("--experiments-log", type=Path, default=Path(__file__).resolve().parents[2] / "EXPERIMENTS.md")
    args = parser.parse_args(argv)

    train_rows = load_split(args.dataset, "train")
    val_rows = load_split(args.dataset, "val")
    if not train_rows or not val_rows:
        raise SystemExit(
            f"need labeled+split rows in both train and val (got {len(train_rows)} train, "
            f"{len(val_rows)} val) -- run split.py on --dataset first"
        )

    tokenizer = AutoTokenizer.from_pretrained(args.model_name)
    model = ScamShieldClassifier.from_pretrained(args.model_name)

    config = {
        "stage": "teacher",
        "model_name": args.model_name,
        "lr": args.lr,
        "batch_size": args.batch_size,
        "epochs": args.epochs,
        "scam_class_weight": args.scam_class_weight,
        "max_seq_len": args.max_seq_len,
    }
    start = time.monotonic()
    result = train(
        train_rows,
        val_rows,
        tokenizer,
        model,
        lr=args.lr,
        batch_size=args.batch_size,
        epochs=args.epochs,
        scam_class_weight=args.scam_class_weight,
        max_seq_len=args.max_seq_len,
    )
    wall_clock = time.monotonic() - start

    args.output_dir.mkdir(parents=True, exist_ok=True)
    torch.save(model.state_dict(), args.output_dir / "teacher.pt")
    tokenizer.save_pretrained(args.output_dir)

    append_run(
        args.experiments_log,
        stage="teacher",
        config=config,
        dataset_version=str(args.dataset),
        metrics={"best_val_macro_f1": result["best_val_macro_f1"], "history": result["history"]},
        wall_clock_seconds=wall_clock,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
