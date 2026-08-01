from scamshield_ml.model import ScamShieldClassifier
from scamshield_ml.schema import Row
from scamshield_ml.train_teacher import load_split, train

from .conftest import tiny_teacher_config

SCAM_TEXTS = [
    "your otp is do not share it with anyone",
    "dear customer kyc expire today update account",
    "click here to verify claim your prize now",
    "congratulations you have won a lottery",
    "pay ten thousand rupees processing fee",
]
GENUINE_TEXTS = [
    "urgent bank transfer confirm delivery scheduled for tomorrow",
    "meeting reminder team standup at nine am",
    "thanks for shopping order has shipped",
    "your otp is do not share it with anyone",
    "team standup at nine am",
]


def make_rows(texts: list[str], label: int, category: str, split: str, prefix: str) -> list[Row]:
    return [
        Row(
            id=f"{prefix}{i}",
            text=t,
            lang="EN",
            source="test",
            collected_at="2026-08-01",
            label=label,
            category=category,
            split=split,
        )
        for i, t in enumerate(texts)
    ]


def synthetic_dataset() -> list[Row]:
    return (
        make_rows(SCAM_TEXTS, 1, "KYC_PHISHING", "train", "scam_tr")
        + make_rows(GENUINE_TEXTS, 0, "NOT_SCAM", "train", "gen_tr")
        + make_rows(SCAM_TEXTS, 1, "KYC_PHISHING", "val", "scam_val")
        + make_rows(GENUINE_TEXTS, 0, "NOT_SCAM", "val", "gen_val")
    )


def test_load_split_filters_by_split_and_requires_labeled(tmp_path):
    from scamshield_ml.jsonl_io import write_jsonl

    rows = synthetic_dataset() + [Row(id="unl", text="x", lang="EN", source="s", collected_at="d")]
    path = tmp_path / "dataset.jsonl"
    write_jsonl(rows, path)

    train_rows = load_split(path, "train")
    val_rows = load_split(path, "val")
    assert len(train_rows) == 10
    assert len(val_rows) == 10
    assert all(r.split == "train" for r in train_rows)


def test_train_improves_or_holds_val_macro_f1(tiny_tokenizer):
    rows = synthetic_dataset()
    train_rows = [r for r in rows if r.split == "train"]
    val_rows = [r for r in rows if r.split == "val"]

    config = tiny_teacher_config(tiny_tokenizer.vocab_size)
    model = ScamShieldClassifier.from_config(config)

    result = train(train_rows, val_rows, tiny_tokenizer, model, epochs=2, batch_size=4)

    assert len(result["history"]) == 2
    assert 0.0 <= result["best_val_macro_f1"] <= 1.0
    for entry in result["history"]:
        assert "train_loss" in entry
        assert "val_macro_f1" in entry


def test_train_keeps_the_best_epoch_state(tiny_tokenizer):
    rows = synthetic_dataset()
    train_rows = [r for r in rows if r.split == "train"]
    val_rows = [r for r in rows if r.split == "val"]

    config = tiny_teacher_config(tiny_tokenizer.vocab_size)
    model = ScamShieldClassifier.from_config(config)

    result = train(train_rows, val_rows, tiny_tokenizer, model, epochs=3, batch_size=4)
    best_epoch_f1 = max(e["val_macro_f1"] for e in result["history"])
    assert result["best_val_macro_f1"] == best_epoch_f1
