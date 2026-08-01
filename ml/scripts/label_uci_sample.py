"""Drives the *real* `collect.py` and `label.py` code paths (not a hand-rolled shortcut) over
the small UCI sample from `sample_uci.py`, using the dataset's own ground-truth spam/ham label
plus a keyword heuristic for the 12-way scam category (the UCI corpus only has binary labels).

This is scripted, not interactive -- `label_session`'s `input_fn`/`print_fn` injection (built
for exactly this kind of testability) is fed a precomputed answer per row instead of a human
typing at a keyboard. `labeler="uci_ground_truth+keyword_heuristic"` records that honestly on
every row: the binary label is real ground truth from a trusted academic source, the *category*
is a heuristic guess, not a human-verified judgment call the way `LABELING_GUIDE.md` describes
for real labeling sessions.
"""

import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from scamshield_ml.collect import collect, read_plaintext_lines  # noqa: E402
from scamshield_ml.jsonl_io import write_jsonl  # noqa: E402
from scamshield_ml.label import label_session  # noqa: E402

DATA_DIR = Path(__file__).resolve().parents[1] / "data"
TEXTS_PATH = DATA_DIR / "raw" / "uci_sample_texts.txt"
LABELS_PATH = DATA_DIR / "raw" / "uci_sample_labels.txt"
OUTPUT_PATH = DATA_DIR / "samples" / "dataset.jsonl"

LABELER = "uci_ground_truth+keyword_heuristic"

# Order matters: first match wins. Deliberately narrow, high-precision patterns -- a wrong
# category is worse than falling through to OTHER_SCAM, since OTHER_SCAM is an honest "we don't
# know", not a wrong claim.
_CATEGORY_RULES = [
    (re.compile(r"\b(won|winner|prize|award|claim|congratulations)\b", re.I), "LOTTERY_PRIZE"),
    (re.compile(r"\b(loan|cash advance|overdraft)\b", re.I), "LOAN_APP"),
    (re.compile(r"\b(verify|account suspended|update your (account|details)|kyc)\b", re.I), "KYC_PHISHING"),
    (re.compile(r"\b(vacancy|part.?time job|earn (\$|£|per week))\b", re.I), "FAKE_JOB"),
    (re.compile(r"\b(invest|shares|stock tip|double your money)\b", re.I), "INVESTMENT_TRADING"),
    (re.compile(r"\b(parcel|delivery|courier|customs)\b", re.I), "COURIER_CUSTOMS"),
    (re.compile(r"\b(virus|your (pc|computer)|tech support)\b", re.I), "TECH_SUPPORT"),
]
DEFAULT_SCAM_CATEGORY = "OTHER_SCAM"


def categorize(text: str) -> str:
    for pattern, category in _CATEGORY_RULES:
        if pattern.search(text):
            return category
    return DEFAULT_SCAM_CATEGORY


def main() -> None:
    texts = list(read_plaintext_lines(TEXTS_PATH))
    true_labels = [int(line) for line in LABELS_PATH.read_text(encoding="utf-8").splitlines()]
    assert len(texts) == len(true_labels), "sample_uci.py's two output files must stay line-aligned"

    unlabeled_rows = collect(iter(texts), source="uci_sms_spam_collection", lang="EN")

    # `label_session` visits `rows` in list order and reads `prompt_label`'s answers strictly
    # in the order it asks -- one answer for a genuine row ("0"), two for a scam row ("1", then
    # the category menu index). Building the full flattened answer sequence up front, keyed to
    # `unlabeled_rows`' own order (which is `texts`' order, same order `true_labels` is in),
    # is what makes a scripted, non-interactive `input_fn` correct here.
    from scamshield_ml.schema import SCAM_CATEGORIES

    answers = []
    for text, true_label in zip(texts, true_labels):
        if true_label == 0:
            answers.append("0")
        else:
            answers.append("1")
            category = categorize(text)
            answers.append(str(SCAM_CATEGORIES.index(category) + 1))
    answer_iter = iter(answers)

    labeled_rows = label_session(
        unlabeled_rows,
        labeler=LABELER,
        input_fn=lambda _prompt: next(answer_iter),
        print_fn=lambda _msg: None,
    )

    OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    write_jsonl(labeled_rows, OUTPUT_PATH)

    n_scam = sum(1 for r in labeled_rows if r.label == 1)
    n_genuine = sum(1 for r in labeled_rows if r.label == 0)
    print(f"wrote {len(labeled_rows)} labeled rows to {OUTPUT_PATH} ({n_scam} scam, {n_genuine} genuine)")


if __name__ == "__main__":
    main()
