"""Shared row schema for the dataset pipeline (design.md section 9.1).

Every stage -- collect, label, split, train -- reads and writes this same JSONL row shape, so
it lives in one place rather than being redefined per script. A row starts unlabeled (`label`
and `category` both `None`) when `collect.py` writes it; `label.py` is the only stage that
fills those in, always via a human decision, never inferred.
"""

from __future__ import annotations

import dataclasses
from typing import Optional

# core/model's ScamCategory (Verdict.kt), kept in the exact same order -- that enum's ordinal
# position is the model's category head, and a future training script's label encoding should
# not need a lookup table to match it.
CATEGORIES = (
    "KYC_PHISHING",
    "FAKE_JOB",
    "LOTTERY_PRIZE",
    "DIGITAL_ARREST",
    "COURIER_CUSTOMS",
    "LOAN_APP",
    "UPI_COLLECT",
    "INVESTMENT_TRADING",
    "TECH_SUPPORT",
    "SEXTORTION",
    "ELECTRICITY_DISCONNECTION",
    "OTHER_SCAM",
    "NOT_SCAM",
)

SCAM_CATEGORIES = tuple(c for c in CATEGORIES if c != "NOT_SCAM")

# core/model's Language (Message.kt), same order.
LANGUAGES = ("EN", "HI", "HI_LATN", "BN", "TA", "TE", "MR", "UNKNOWN")


@dataclasses.dataclass
class Row:
    id: str
    text: str
    lang: str
    source: str
    collected_at: str  # ISO 8601 date
    label: Optional[int] = None  # 0 = genuine, 1 = scam; None = not yet labeled
    category: Optional[str] = None
    is_hard_negative: bool = False
    split: Optional[str] = None  # filled in later by split.py, not at collection or labeling time
    labeled_by: Optional[str] = None
    labeled_at: Optional[str] = None

    def __post_init__(self) -> None:
        if self.lang not in LANGUAGES:
            raise ValueError(f"unknown language: {self.lang!r}")
        if self.label is not None:
            if self.label not in (0, 1):
                raise ValueError(f"label must be 0 or 1, got {self.label!r}")
            if self.category is None:
                raise ValueError("a labeled row must also have a category")
            if self.category not in CATEGORIES:
                raise ValueError(f"unknown category: {self.category!r}")
            if self.label == 0 and self.category != "NOT_SCAM":
                raise ValueError("a genuine (label=0) row must be categorized NOT_SCAM")
            if self.label == 1 and self.category == "NOT_SCAM":
                raise ValueError("a scam (label=1) row cannot be categorized NOT_SCAM")

    @property
    def is_labeled(self) -> bool:
        return self.label is not None

    def to_json_dict(self) -> dict:
        return dataclasses.asdict(self)

    @staticmethod
    def from_json_dict(d: dict) -> "Row":
        return Row(**d)
