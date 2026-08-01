"""PII scrubbing -- design.md section 9.2: "Scrub all phone numbers, account numbers, names,
and amounts to placeholders (<PHONE>, <ACCT>, <NAME>, <AMT>) -- in both training data and any
published dataset." Runs at ingest, inside `collect.py`, and nowhere else -- an unscrubbed row
must never reach disk, so there is deliberately no code path that writes a `Row` without going
through `scrub()` first.

**Known gap, not silently glossed over: personal names are not scrubbed.** Phone numbers,
account-number-shaped digit runs, and currency amounts are reliably regex-matchable; names are
not -- a regex that tried would either miss most real names or falsely scrub ordinary capitalized
words (brand names, place names, "OTP"), and a labeler trusting a broken scrubber is worse than
one who knows scrubbing is incomplete. Real name-scrubbing needs an NER model, which is a real
dependency (spaCy or similar) this module does not pull in speculatively. Do not begin real data
collection at volume before this gap is closed -- see `LABELING_GUIDE.md`'s own note.
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


def scrub(text: str) -> str:
    scrubbed = _PHONE.sub("<PHONE>", text)
    scrubbed = _AMOUNT.sub("<AMT>", scrubbed)
    scrubbed = _ACCOUNT.sub("<ACCT>", scrubbed)
    return scrubbed
