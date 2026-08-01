"""One-off sampling script for the UCI SMS Spam Collection -> a small (~180 row) learning-project
corpus, per the user's own explicit scope call (2026-08-01: "collect small setup of data for
now, since this is learning project, 100-200 row data should be fine"). Not part of the
`scamshield_ml` package -- run once, output committed, doesn't need to run again.

Oversamples spam relative to the corpus's natural ~13% rate (747/5574) so the toy dataset has a
usable scam/genuine mix, matching design.md's own intent -- composition is a deliberate choice,
not raw random sampling -- while staying inside the available 747 real spam rows.
"""

import random
from pathlib import Path

SEED = 20260801
N_SPAM = 70
N_HAM = 110

here = Path(__file__).resolve().parents[1] / "data" / "raw"
lines = (here / "uci_sms_spam_collection" / "SMSSpamCollection").read_text(encoding="utf-8").splitlines()

spam, ham = [], []
for line in lines:
    label, _, text = line.partition("\t")
    if label == "spam":
        spam.append(text)
    elif label == "ham":
        ham.append(text)

rng = random.Random(SEED)
sampled_spam = rng.sample(spam, N_SPAM)
sampled_ham = rng.sample(ham, N_HAM)

combined = [(t, 1) for t in sampled_spam] + [(t, 0) for t in sampled_ham]
rng.shuffle(combined)

out_texts = here / "uci_sample_texts.txt"
out_labels = here / "uci_sample_labels.txt"
with out_texts.open("w", encoding="utf-8") as ft, out_labels.open("w", encoding="utf-8") as fl:
    for text, label in combined:
        ft.write(text.replace("\n", " ").replace("\r", " ") + "\n")
        fl.write(str(label) + "\n")

print(f"wrote {len(combined)} rows ({N_SPAM} spam, {N_HAM} ham) to {out_texts} / {out_labels}")
