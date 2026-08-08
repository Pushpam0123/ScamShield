# ScamShield 🛡️

**"Is this message trying to cheat me?"** — that's the only question ScamShield tries to answer.

India loses tens of thousands of crores to text-message scams every year, and the messages keep
getting better at sounding real. A fake "your SBI KYC will expire today" text copies the tone of
a genuine bank message almost perfectly. Someone who's used a smartphone for two months has no
real way to tell it apart from someone who's used one for ten years — they can't inspect a
domain, don't know real banks never ask for KYC over SMS, and have no idea `sbi-kyc-verify.xyz`
was registered four days ago by someone with no connection to any bank.

ScamShield is an attempt to close that gap: paste a suspicious SMS or WhatsApp forward in, and
get back a plain-language answer — not a score, not jargon, an actual explanation you could read
out loud to a parent or grandparent. *"This message says it's from SBI, but the link goes to a
website that's four days old. Real SBI texts never ask you to click a link to update your KYC."*
And then what to actually do about it.

## Why it's built the way it is

The one rule that shapes almost every engineering decision here: **the message never leaves the
phone.** People are going to share ScamShield exactly the texts they're most anxious about — the
ones with account numbers, OTPs, amounts, personal details in them. Sending that to a server
would defeat the entire point, so everything — the rule checks *and* the ML model — runs
on-device, fully offline. No `READ_SMS` permission, no network calls unless you explicitly
opt in to a domain-age lookup, no accounts, nothing.

That constraint is also what makes this an interesting project to build: a normal multilingual
transformer model is 200+ MB, and it has to be compressed down to something that fits comfortably
on a budget Android phone while still understanding scam messages written in Hindi, Marathi, and
— the hard one — romanized Hinglish, which almost no off-the-shelf NLP handles well.

Under the hood it's a hybrid: a handful of deterministic rule-based checks (domain reputation,
typosquat detection, homograph/lookalike-character detection, sender-ID validation, known scam
phrasing) working alongside a small on-device classifier, with a fusion layer that combines both
into one verdict plus the evidence behind it. The rules exist so the app can *explain itself* —
a raw ML score can't tell you a link goes to a domain registered four days ago, but a rule can.

## 🚧 Status: work in progress

This is under active development and not yet usable end to end. Where things stand:

- **Rules engine — built and tested.** Domain/URL analysis, typosquat and homograph detection,
  sender-ID validation, scam-phrasing patterns, message normalization, language detection, and the
  fusion layer that combines them into one explained verdict.
- **On-device ML classifier — integrated.** The full path is real: an ONNX model + HuggingFace
  tokenizer run on-device, scored through a calibrated probability, fused with the rules. An
  instrumented parity gate confirms the on-device output matches the Python reference exactly
  (max Δ 0.0 across the sample set), so the tokenizer and runtime agree across platforms.
- **What's *not* done, stated plainly:**
  - The bundled model is a **toy** (distilbert, English, ~180 rows) used to prove the wiring, not
    a real classifier. Accuracy numbers are therefore not meaningful yet — with the toy model live,
    several genuine bank SMS are wrongly flagged. A real multilingual model is the next big piece.
  - No UI yet — there's a share-sheet entry point and the analysis pipeline, but not the screens.
  - APK size is over budget: the full ONNX Runtime build plus the tokenizer's native library push
    it well past the 25 MB target. Hitting that needs ONNX Runtime *Mobile* (reduced-op) and
    per-ABI app-bundle delivery — noted, not yet done.

Nothing here is ready to install or rely on. Check the commit history for the most honest picture.

### Measured so far (emulator, toy model — directional, not a phone budget)

| What | Observed |
|---|---|
| Rule-pack load | ~296 ms |
| Model cold load + first inference | ~340 ms |
| Single inference (p50 / p95) | 13 / 28 ms |
| End-to-end verdict (p50 / p95 / p99) | 177 / 402 / 1238 ms |
| Peak process memory (PSS) | ~276 MB |

These are from an emulator with software rendering and the toy model; a real device and a
reduced-op runtime would move them. Real-device Macrobenchmark numbers are deliberately deferred
rather than faked on an emulator.

## What it promises, even half-built

- **No message text ever leaves the device.** There's no server-side classification path at all.
- **No SMS-reading permissions.** You share a message in, or paste it — that's the only way in.
- **Works even without the ML model.** The rule-based engine alone is meant to be a usable
  product on its own; the model is meant to be an enhancement, not a dependency.
- **Every verdict comes with a reason.** No unexplained "72% scam" numbers.

## The model (a card, folded in here on purpose)

A standalone `MODEL_CARD.md` is the usual home for this, but this repo deliberately keeps exactly
one tracked markdown file, so the card lives here until there's a real model worth its own document.

- **What it is:** a small transformer text classifier, distilled and vocabulary-pruned, exported to
  ONNX and INT8-quantized, with two heads — binary scam/not-scam (temperature-calibrated) and a
  13-way scam category. Runs on-device via ONNX Runtime with a HuggingFace tokenizer.
- **What's bundled today:** a **toy** model (DistilBERT teacher, ~180 English rows from the public
  UCI SMS Spam Collection). It exists to prove the on-device path end to end, **not** to classify
  real scams. It knows nothing of Hindi, romanized Hinglish, or Indian bank SMS.
- **Intended use:** one signal among several, fused with deterministic rules — never the sole basis
  for a verdict. The rules alone are the shipped guarantee; the model is an enhancement.
- **Known limitations:** English-only and tiny, so accuracy numbers are not meaningful; with the toy
  model live it wrongly flags genuine bank SMS. It is therefore **not bundled in release builds** by
  default (the model assets are generated, git-ignored, and copied in only for local testing).
- **Privacy:** inference is fully on-device. No message text is transmitted for classification, ever.
- **Reproduce:** `cd ml && make teacher distill prune export calibrate` (see `ml/` for the pipeline).

## Building

Requires JDK 17+ and the Android SDK (platform 35, build-tools 35).

```bash
./gradlew build
```

## License

Not decided yet.

---

*If you're reading this on GitHub and it looks unfinished — it is. Come back later, or better,
open an issue if something looks worth talking about.*
