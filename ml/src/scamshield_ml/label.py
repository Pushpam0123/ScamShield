"""Phase 2.2 (implementation.md's own words): "CLI labeling tool: shows a message, accepts
binary + category, writes with provenance. Support re-labeling and an --audit mode that
re-serves 5% for consistency checking."

The interactive I/O (reading answers, printing prompts) is injected rather than hardcoded to
`input`/`print`, so the actual labeling state machine -- resume, provenance, audit sampling and
scoring -- is unit-testable without a human at a keyboard. See `LABELING_GUIDE.md` for the
decision rule this tool expects the labeler to already know before they start a session.
"""

from __future__ import annotations

import argparse
import random
import sys
from collections.abc import Callable
from dataclasses import dataclass, replace
from datetime import datetime, timezone
from pathlib import Path

from .jsonl_io import read_jsonl, write_jsonl
from .schema import SCAM_CATEGORIES, Row

InputFn = Callable[[str], str]
PrintFn = Callable[[str], None]


class SkipRow(Exception):
    """Raised by [prompt_label] when the labeler skips a row -- not an error, a signal to move
    on and leave the row unlabeled for next time.
    """


def _default_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def prompt_label(row: Row, input_fn: InputFn, print_fn: PrintFn) -> tuple[int, str]:
    """Shows one message and collects (label, category). Loops on invalid input rather than
    raising, since a mistyped keystroke should not lose the labeler's place in a long session.
    """
    print_fn(f"\n[{row.id}] ({row.lang}, {row.source})\n{row.text}\n")
    while True:
        answer = input_fn("Scam? [1=yes, 0=no, s=skip]: ").strip().lower()
        if answer == "s":
            raise SkipRow()
        if answer in ("0", "1"):
            label = int(answer)
            break
        print_fn("Please enter 1, 0, or s.")

    if label == 0:
        return 0, "NOT_SCAM"

    print_fn("Category:")
    for i, category in enumerate(SCAM_CATEGORIES, start=1):
        print_fn(f"  {i}. {category}")
    while True:
        answer = input_fn(f"Choose 1-{len(SCAM_CATEGORIES)}: ").strip()
        if answer.isdigit() and 1 <= int(answer) <= len(SCAM_CATEGORIES):
            return 1, SCAM_CATEGORIES[int(answer) - 1]
        print_fn(f"Please enter a number from 1 to {len(SCAM_CATEGORIES)}.")


def label_session(
    rows: list[Row],
    labeler: str,
    input_fn: InputFn = input,
    print_fn: PrintFn = print,
    now_fn: Callable[[], str] = _default_now,
) -> list[Row]:
    """Labels every row with `label is None`, in the order given. Resume support falls out of
    this for free: an already-labeled row is passed through unchanged, never re-prompted here
    -- see [run_audit] for the deliberate, separate re-prompt path.
    """
    result = []
    for row in rows:
        if row.is_labeled:
            result.append(row)
            continue
        try:
            label, category = prompt_label(row, input_fn, print_fn)
        except SkipRow:
            result.append(row)
            continue
        result.append(replace(row, label=label, category=category, labeled_by=labeler, labeled_at=now_fn()))
    return result


@dataclass
class AuditResult:
    sampled: int
    agreed: int
    disagreements: list[tuple[str, str, str]]  # (row id, original category, re-labeled category)

    @property
    def agreement_rate(self) -> float:
        return self.agreed / self.sampled if self.sampled else 1.0


def run_audit(
    rows: list[Row],
    fraction: float,
    input_fn: InputFn = input,
    print_fn: PrintFn = print,
    rng: random.Random | None = None,
) -> AuditResult:
    """design.md's "--audit mode that re-serves 5% for consistency checking": blind re-label a
    random sample of already-labeled rows (the original label is never shown) and compare.
    Returns a report; never mutates the input rows or the file on disk -- an audit measures
    labeling consistency, it does not correct anything by itself.
    """
    rng = rng or random.Random()
    labeled = [r for r in rows if r.is_labeled]
    sample_size = max(1, round(len(labeled) * fraction)) if labeled else 0
    sample = rng.sample(labeled, k=min(sample_size, len(labeled)))

    agreed = 0
    disagreements: list[tuple[str, str, str]] = []
    for row in sample:
        blind_row = replace(row, label=None, category=None)
        try:
            new_label, new_category = prompt_label(blind_row, input_fn, print_fn)
        except SkipRow:
            continue
        if new_label == row.label and new_category == row.category:
            agreed += 1
        else:
            disagreements.append((row.id, row.category or "?", new_category))

    return AuditResult(sampled=len(sample), agreed=agreed, disagreements=disagreements)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output", type=Path, help="Defaults to overwriting --input")
    parser.add_argument("--labeler", required=True, help="Your name or id, recorded on every label for provenance")
    parser.add_argument(
        "--audit",
        action="store_true",
        help="Blind re-label a random sample of already-labeled rows instead of labeling new ones",
    )
    parser.add_argument("--audit-fraction", type=float, default=0.05)
    args = parser.parse_args(argv)

    rows = read_jsonl(args.input)

    if args.audit:
        result = run_audit(rows, args.audit_fraction)
        print(f"\nAudited {result.sampled} rows: {result.agreed} agreed ({result.agreement_rate:.1%})", file=sys.stderr)
        for row_id, original, relabel in result.disagreements:
            print(f"  disagreement on {row_id}: {original} -> {relabel}", file=sys.stderr)
        return 0

    labeled_rows = label_session(rows, labeler=args.labeler)
    write_jsonl(labeled_rows, args.output or args.input)
    newly_labeled = sum(1 for r in labeled_rows if r.is_labeled) - sum(1 for r in rows if r.is_labeled)
    print(f"Labeled {newly_labeled} new rows.", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
