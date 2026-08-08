"""PII scrubbing -- design.md section 9.2: "Scrub all phone numbers, account numbers, names,
and amounts to placeholders (<PHONE>, <ACCT>, <NAME>, <AMT>) -- in both training data and any
published dataset." Runs at ingest, inside `collect.py`, and nowhere else -- an unscrubbed row
must never reach disk, so there is deliberately no code path that writes a `Row` without going
through `scrub()` first.

**Names -- the decision (Phase 4).** General name scrubbing needs NER; a bare regex either misses
most names or falsely scrubs ordinary capitalized words. So this module does two things:

  1. Scrubs the one high-precision, most-common leak: the salutation-anchored name ("Dear Rahul,",
     "Hi Priya Sharma", "Mr. Kumar"). Anchoring on a salutation + a Title-case token keeps false
     positives near zero (generic addressees are stop-listed; all-caps brands don't match). See
     [_scrub_names].
  2. Leaves general in-body names to a real NER pass, gated one level up: `collect.collect` refuses
     any source off its allowlist unless `--names-verified` is passed -- so at-volume collection is
     blocked in code, not just flagged in a comment. See `collect.py` and `LABELING_GUIDE.md`.
"""

from __future__ import annotations

import re

# A Reliance/Jio/Airtel-style Indian mobile number, with or without a +91/91 prefix, not
# already part of a longer digit run (the lookaround guards keep it from firing on the middle
# of a 12+ digit account or reference number).
_PHONE = re.compile(r"(?<!\d)(?:\+?91[-\s]?)?[6-9]\d{9}(?!\d)")

# Rs./INR/₹-prefixed amounts, with optional thousands separators and paise. Runs before the
# account-number pass so a bare amount like "2,500" (no currency marker, therefore ambiguous)
# is not left for that pass to also consider -- see ACCOUNT's own note.
_AMOUNT = re.compile(r"(?:₹|rs\.?|inr)\s?[\d,]+(?:\.\d+)?", re.IGNORECASE)

# Any remaining long digit run (9-18 digits) is treated as an account/card/reference number.
# Deliberately after PHONE and AMOUNT so it only catches what neither of those already claimed.
_ACCOUNT = re.compile(r"(?<!\d)\d{9,18}(?!\d)")

# A salutation (group 1, kept) + a Title-case name of one or two words (group 2, replaced). The
# leading \b stops "hi"/"ms"/etc. matching inside words ("Delhi"); Title-case-only excludes all-caps
# brands ("SBI") and the generic addressees stop-listed below.
_SALUTATION_NAME = re.compile(
    r"\b((?i:dear|hi|hello|hey|mr|mrs|ms|shri|smt|dr)\.?)\s+"
    r"([A-Z][a-z]+(?:\s+[A-Z][a-z]+)?)"
)

# Words that follow a salutation but are not personal names -- do not scrub these to <NAME>.
_GENERIC_ADDRESSEES = frozenset(
    {"customer", "sir", "madam", "user", "member", "team", "friend", "valued", "guest", "all"}
)


def _scrub_names(text: str) -> str:
    def replace(match: re.Match) -> str:
        salutation, name = match.group(1), match.group(2)
        if name.split()[0].lower() in _GENERIC_ADDRESSEES:
            return match.group(0)
        return f"{salutation} <NAME>"

    return _SALUTATION_NAME.sub(replace, text)


def scrub(text: str) -> str:
    scrubbed = _PHONE.sub("<PHONE>", text)
    scrubbed = _AMOUNT.sub("<AMT>", scrubbed)
    scrubbed = _ACCOUNT.sub("<ACCT>", scrubbed)
    scrubbed = _scrub_names(scrubbed)
    return scrubbed
