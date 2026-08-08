"""Stage C (design.md section 9.4): "Tokenize the full corpus plus a general Indic sample; keep
tokens with frequency >= 5 plus all special tokens, targeting ~32K. Slice the embedding matrix,
remap IDs, regenerate `tokenizer.json`. Fine-tune 2 further epochs to recover. This stage
produces the single largest size reduction (embedding drops from ~151M to ~12M parameters)."

**Known simplification, worth stating plainly (the same honesty pattern as `pii_scrub.py`'s own
documented name-scrubbing gap):** this file treats vocabulary pruning purely as an *id-remapping*
problem -- slice the embedding matrix down to the surviving rows, rebuild a `tokenizer.json` that
maps exactly those token strings to their new ids -- and always rebuilds the tokenizer as a plain
`WordLevel` model. The real `google/muril-base-cased` tokenizer is WordPiece, where pruning a
subword (especially a `##`-continuation piece) changes how *other*, still-kept, whole words get
segmented, not just whether the pruned token itself is reachable. Getting that fully right needs
re-deriving merge behavior, which is real work belonging to whoever runs Stage C for real, not
speculative work to do now with no real corpus to prune against. So the simplification is now
*enforced* rather than merely noted: [_assert_wordlevel] refuses a WordPiece (or any non-WordLevel)
input with the exact checklist of what to implement, so a real MuRIL run fails loudly here instead
of silently shipping a mis-segmenting tokenizer. This module is exercised and
tested end-to-end regardless, because the id-remapping mechanics (frequency counting, keep/drop
selection, embedding slicing, id remap, tokenizer round-trip, the 2-epoch recovery fine-tune) are
the same regardless of the underlying tokenizer model class.
"""

from __future__ import annotations

import argparse
import time
from collections import Counter
from pathlib import Path
from typing import Any

import torch
from tokenizers import Tokenizer
from tokenizers.models import WordLevel
from tokenizers.pre_tokenizers import Whitespace
from torch import nn
from transformers import PreTrainedTokenizerBase, PreTrainedTokenizerFast

from .experiment_log import append_run
from .jsonl_io import read_jsonl
from .model import ScamShieldClassifier
from .schema import Row
from .train_teacher import DEFAULT_LR, load_split, train

MIN_TOKEN_FREQ = 5
TARGET_VOCAB_SIZE = 32_000
RECOVERY_EPOCHS = 2

_SPECIAL_TOKEN_ATTRS = ("pad_token", "unk_token", "cls_token", "sep_token", "mask_token", "bos_token", "eos_token")


def special_token_ids(tokenizer: PreTrainedTokenizerBase) -> set[int]:
    ids = set()
    for attr in _SPECIAL_TOKEN_ATTRS:
        token = getattr(tokenizer, attr, None)
        if token is None:
            continue
        token_id = tokenizer.convert_tokens_to_ids(token)
        if token_id is not None and token_id >= 0:
            ids.add(token_id)
    return ids


def count_token_frequencies(tokenizer: PreTrainedTokenizerBase, texts: list[str]) -> Counter[int]:
    counts: Counter[int] = Counter()
    for text in texts:
        counts.update(tokenizer(text, add_special_tokens=False)["input_ids"])
    return counts


def select_kept_token_ids(
    freq: Counter[int],
    special_ids: set[int],
    min_freq: int = MIN_TOKEN_FREQ,
    target_size: int = TARGET_VOCAB_SIZE,
) -> list[int]:
    """Specials are always kept. Everything else needs `freq >= min_freq`; if that still leaves
    more than `target_size` total, keep the highest-frequency survivors (ties broken by
    original id, for a deterministic result independent of `Counter` iteration order).
    """
    frequent = [tid for tid, count in freq.items() if count >= min_freq and tid not in special_ids]
    frequent.sort(key=lambda tid: (-freq[tid], tid))
    budget = max(target_size - len(special_ids), 0)
    frequent = frequent[:budget]
    return sorted(special_ids | set(frequent))


def slice_embedding(embedding_weight: torch.Tensor, kept_ids: list[int]) -> torch.Tensor:
    return embedding_weight[kept_ids].clone()


def build_id_remap(kept_ids: list[int]) -> dict[int, int]:
    """Old id -> new id, new ids assigned 0..len(kept_ids)-1 in the same order as `kept_ids`
    (which `slice_embedding` also iterates in) -- the two must always agree, or the sliced
    embedding row for a token would land at a different id than its own tokenizer entry.
    """
    return {old: new for new, old in enumerate(kept_ids)}


def _assert_wordlevel(tokenizer: PreTrainedTokenizerBase) -> None:
    """The gate for the WordPiece simplification documented at the top of this module. Rather than
    silently rebuild a WordPiece tokenizer as WordLevel (which would mis-segment every kept word
    whose subword pieces changed), refuse and spell out exactly what a real Stage C run must add.
    """
    backend = getattr(tokenizer, "backend_tokenizer", None)
    model_class = type(backend.model).__name__ if backend is not None else "WordLevel"
    if model_class != "WordLevel":
        raise NotImplementedError(
            f"prune_vocab only rebuilds WordLevel tokenizers, but the input is {model_class} "
            "(e.g. real google/muril-base-cased is WordPiece). To prune WordPiece correctly you must: "
            "(1) keep whole tokens AND the ## continuation pieces any kept word still decomposes into; "
            "(2) rebuild a WordPiece backend (tokenizers.models.WordPiece) with its unk_token and "
            "continuing_subword_prefix, not WordLevel+Whitespace; (3) re-verify segmentation of the "
            "kept corpus is unchanged for surviving words before slicing the embedding. Implement that "
            "here, or prune against a WordLevel student. See this module's docstring."
        )


def build_pruned_tokenizer(tokenizer: PreTrainedTokenizerBase, kept_ids: list[int]) -> PreTrainedTokenizerFast:
    _assert_wordlevel(tokenizer)
    tokens = tokenizer.convert_ids_to_tokens(kept_ids)
    new_vocab = {tok: i for i, tok in enumerate(tokens)}
    backend = Tokenizer(WordLevel(vocab=new_vocab, unk_token=tokenizer.unk_token))
    backend.pre_tokenizer = Whitespace()
    return PreTrainedTokenizerFast(
        tokenizer_object=backend,
        unk_token=tokenizer.unk_token,
        pad_token=tokenizer.pad_token,
        cls_token=tokenizer.cls_token,
        sep_token=tokenizer.sep_token,
    )


def prune_model_and_tokenizer(
    model: ScamShieldClassifier,
    tokenizer: PreTrainedTokenizerBase,
    corpus_texts: list[str],
    indic_sample_texts: list[str] = (),
    min_freq: int = MIN_TOKEN_FREQ,
    target_size: int = TARGET_VOCAB_SIZE,
) -> tuple[ScamShieldClassifier, PreTrainedTokenizerFast, dict[str, Any]]:
    """In-place on `model` (its embedding module is replaced). Returns the same model, the new
    tokenizer, and a small stats dict for `EXPERIMENTS.md`.
    """
    freq = count_token_frequencies(tokenizer, list(corpus_texts) + list(indic_sample_texts))
    specials = special_token_ids(tokenizer)
    kept_ids = select_kept_token_ids(freq, specials, min_freq, target_size)

    new_tokenizer = build_pruned_tokenizer(tokenizer, kept_ids)

    old_embedding_module = model.encoder.embeddings.word_embeddings
    old_weight = old_embedding_module.weight.data
    new_weight = slice_embedding(old_weight, kept_ids)

    new_pad_id = new_tokenizer.pad_token_id
    new_embedding_module = nn.Embedding(len(kept_ids), new_weight.shape[1], padding_idx=new_pad_id)
    with torch.no_grad():
        new_embedding_module.weight.copy_(new_weight)
    model.encoder.embeddings.word_embeddings = new_embedding_module
    model.encoder.config.vocab_size = len(kept_ids)

    stats = {
        "original_vocab_size": old_weight.shape[0],
        "pruned_vocab_size": len(kept_ids),
        "embedding_params_before": old_weight.numel(),
        "embedding_params_after": new_weight.numel(),
    }
    return model, new_tokenizer, stats


def recover_after_prune(
    model: ScamShieldClassifier,
    tokenizer: PreTrainedTokenizerBase,
    train_rows: list[Row],
    val_rows: list[Row],
    epochs: int = RECOVERY_EPOCHS,
    lr: float = DEFAULT_LR,
) -> dict[str, Any]:
    """The "fine-tune 2 further epochs to recover" step -- plain reuse of `train_teacher.train`,
    since pruning only changed the embedding layer and the rest of the fine-tuning loop
    (two-head loss, class weighting, early-stop-by-best-epoch) is identical.
    """
    return train(train_rows, val_rows, tokenizer, model, epochs=epochs, lr=lr)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dataset", required=True, type=Path)
    parser.add_argument("--student-dir", required=True, type=Path, help="Output dir from distill.py")
    parser.add_argument("--indic-sample", type=Path, help="Plain-text file, one sentence per line")
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--min-freq", type=int, default=MIN_TOKEN_FREQ)
    parser.add_argument("--target-size", type=int, default=TARGET_VOCAB_SIZE)
    parser.add_argument("--recovery-epochs", type=int, default=RECOVERY_EPOCHS)
    parser.add_argument("--experiments-log", type=Path, default=Path(__file__).resolve().parents[2] / "EXPERIMENTS.md")
    args = parser.parse_args(argv)

    from .distill import student_config
    from transformers import AutoTokenizer

    tokenizer = AutoTokenizer.from_pretrained(args.student_dir)
    model = ScamShieldClassifier.from_config(student_config(tokenizer.vocab_size))
    model.load_state_dict(torch.load(args.student_dir / "student.pt", map_location="cpu"))

    all_rows = read_jsonl(args.dataset)
    corpus_texts = [r.text for r in all_rows]
    indic_texts = args.indic_sample.read_text(encoding="utf-8").splitlines() if args.indic_sample else []

    start = time.monotonic()
    model, new_tokenizer, prune_stats = prune_model_and_tokenizer(
        model, tokenizer, corpus_texts, indic_texts, min_freq=args.min_freq, target_size=args.target_size
    )

    train_rows = load_split(args.dataset, "train")
    val_rows = load_split(args.dataset, "val")
    recovery_result = recover_after_prune(model, new_tokenizer, train_rows, val_rows, epochs=args.recovery_epochs)
    wall_clock = time.monotonic() - start

    args.output_dir.mkdir(parents=True, exist_ok=True)
    torch.save(model.state_dict(), args.output_dir / "student_pruned.pt")
    new_tokenizer.save_pretrained(args.output_dir)

    append_run(
        args.experiments_log,
        stage="prune_vocab",
        config={"min_freq": args.min_freq, "target_size": args.target_size, "recovery_epochs": args.recovery_epochs},
        dataset_version=str(args.dataset),
        metrics={**prune_stats, "recovery_history": recovery_result["history"]},
        wall_clock_seconds=wall_clock,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
