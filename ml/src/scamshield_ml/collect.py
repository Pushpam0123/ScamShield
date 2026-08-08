"""Phase 2.1 (implementation.md's own words): "source adapters writing normalized JSONL
(design.md section 9.1). Scrub PII to placeholders at ingest, never later."

This module ships two *local-file* source formats (plain-text-lines and CSV) and deliberately
no adapter that reaches out to an external site (Reddit, a cybercrime advisory portal, an
academic corpus download, etc.). Actually sourcing the >=12,000-row corpus design.md section
9.2 calls for is real data-collection work with real ethical and legal constraints attached
(design.md's own words: "use only publicly posted messages... never collect from private
inboxes without consent") -- which source, on what terms, is a decision for a human to make
explicitly, not one an agent should assume mid-session. Adding a new local-file adapter for a
specific, agreed-upon export is meant to be a small, obvious extension once that decision is
made; `read_csv_texts` and `read_plaintext_lines` below are the shape a new one should follow.

Every row this module writes starts unlabeled (`label` and `category` both `None`) --
`label.py` is the only place a row gets a ground-truth label, and it is always a human decision.
"""

from __future__ import annotations

import argparse
import csv
import sys
from collections.abc import Iterator
from datetime import datetime, timezone
from pathlib import Path

from .jsonl_io import write_jsonl
from .pii_scrub import scrub
from .schema import LANGUAGES, Row

# Public/name-free sources where pii_scrub's partial name scrubbing is acceptable; anything else is
# gated behind an explicit `--names-verified` sign-off.
PUBLIC_NAME_SAFE_SOURCES = frozenset({"uci_sms_spam_collection"})


def read_csv_texts(path: Path, column: str = "text") -> Iterator[str]:
    with path.open(newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            text = (row.get(column) or "").strip()
            if text:
                yield text


def read_plaintext_lines(path: Path) -> Iterator[str]:
    """One message per line -- the simplest possible local source format."""
    with path.open(encoding="utf-8") as f:
        for line in f:
            text = line.strip()
            if text:
                yield text


def collect(
    texts: Iterator[str],
    source: str,
    lang: str,
    id_prefix: str = "sms",
    names_verified: bool = False,
) -> list[Row]:
    """Scrubs PII and assembles the shared [Row] schema, one call per source/language batch --
    a single collection run is assumed to be one source in one language, which keeps this
    simple and matches how sources are actually gathered in practice (a Hindi advisory bulletin
    and an English one are two separate runs, not one mixed-language CSV with a language
    column to get wrong).

    Name gate (see pii_scrub.py): in-body names aren't regex-scrubbable, so unless [source] is on
    [PUBLIC_NAME_SAFE_SOURCES] or [names_verified] is set, this refuses -- at-volume collection
    can't silently write name-bearing rows to disk.
    """
    if lang not in LANGUAGES:
        raise ValueError(f"unknown language: {lang!r}")
    if not names_verified and source not in PUBLIC_NAME_SAFE_SOURCES:
        raise ValueError(
            f"source {source!r} is not on the name-safe allowlist and --names-verified was not set. "
            "pii_scrub.py does not scrub in-body personal names; wire an NER pass (or confirm the "
            "source carries none) before collecting at volume. See pii_scrub.py's module docstring."
        )
    collected_at = datetime.now(timezone.utc).date().isoformat()
    rows = []
    for i, text in enumerate(texts):
        rows.append(
            Row(
                id=f"{id_prefix}_{source}_{i:06d}",
                text=scrub(text),
                lang=lang,
                source=source,
                collected_at=collected_at,
            )
        )
    return rows


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--format", choices=["lines", "csv"], default="lines")
    parser.add_argument("--column", default="text", help="CSV column holding the message text (--format csv only)")
    parser.add_argument("--source", required=True, help="Recorded on every row, e.g. cybercrime_advisory_2026")
    parser.add_argument("--lang", required=True, choices=LANGUAGES)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument(
        "--names-verified",
        action="store_true",
        help="Assert that in-body personal names were handled out-of-band (NER pass / human review). "
        "Required for any source not on PUBLIC_NAME_SAFE_SOURCES — see pii_scrub.py.",
    )
    args = parser.parse_args(argv)

    texts = read_csv_texts(args.input, args.column) if args.format == "csv" else read_plaintext_lines(args.input)
    rows = collect(texts, source=args.source, lang=args.lang, names_verified=args.names_verified)
    write_jsonl(rows, args.output)
    print(f"Wrote {len(rows)} unlabeled rows to {args.output}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
