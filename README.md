# ScamShield

"Is this message trying to cheat me?" That's the only question ScamShield tries to answer.

India loses tens of thousands of crores to text-message scams every year, and the messages keep
getting better at sounding real. A fake "your SBI KYC will expire today" text copies the tone of a
genuine bank message almost perfectly. Someone who's had a smartphone for two months has no real
way to tell it apart from someone who's had one for ten years. They can't inspect a domain, they
don't know that real banks never ask for KYC over SMS, and they have no idea `sbi-kyc-verify.xyz`
was registered four days ago by someone with no connection to any bank.

ScamShield tries to close that gap. Paste in a suspicious SMS or WhatsApp forward and you get back
a plain-language answer, the kind you could read out loud to a parent: "This message says it's from
SBI, but the link goes to a website that's four days old. Real SBI texts never ask you to click a
link to update your KYC." Then it tells you what to do about it.

## Why it's built the way it is

One rule shapes almost every decision here: the message never leaves the phone. People are going to
paste in exactly the texts they're most anxious about, the ones with account numbers, OTPs, amounts,
and personal details. Sending those to a server would defeat the point. So everything runs on-device
and offline, the rule checks and the ML model both. No `READ_SMS` permission, no accounts, and no
network calls unless you opt in to a domain-age lookup.

That constraint is also what makes it a fun problem. A normal multilingual transformer is over
200 MB, and it has to shrink down to something that fits on a budget Android phone while still
reading scam messages in Hindi, Marathi, and the hard one, romanized Hinglish, which almost no
off-the-shelf NLP handles well.

Under the hood it's a hybrid. A handful of deterministic rule checks (domain reputation, typosquat
detection, homograph and lookalike-character detection, sender-ID validation, known scam phrasing)
run alongside a small on-device classifier, and a fusion layer combines them into one verdict with
the evidence behind it. The rules are there so the app can explain itself. A raw ML score can't tell
you a link points at a domain registered four days ago. A rule can.

## Status: work in progress

Not usable end to end yet. Where things stand:

- Rules engine: built and tested. Domain and URL analysis, typosquat and homograph detection,
  sender-ID validation, scam-phrasing patterns, message normalization, language detection, and the
  fusion layer that combines them into one explained verdict.
- On-device classifier: integrated. An ONNX model and a HuggingFace tokenizer run on-device, the
  output is calibrated to a probability, and it's fused with the rules. An instrumented parity test
  confirms the on-device output matches the Python reference exactly (max delta 0.0 across the
  sample set), so the tokenizer and the runtime agree across platforms.
- Still open, stated plainly:
  - The bundled model is a toy (DistilBERT, English, about 180 rows) that only exists to prove the
    wiring. Its accuracy numbers don't mean anything yet, and with it live, several genuine bank SMS
    get wrongly flagged. A real multilingual model is the next big piece.
  - No UI. There's a share-sheet entry point and the analysis pipeline behind it, but no screens.
  - The APK is over budget. The full ONNX Runtime build plus the tokenizer's native library push it
    well past the 25 MB target. Getting there needs ONNX Runtime Mobile (the reduced-op build) and
    per-ABI app-bundle delivery.

Nothing here is ready to install or rely on. The commit history is the most honest picture.

### Measured so far

These come from an emulator with software rendering and the toy model, so read them as directional,
not a phone budget.

| What | Observed |
|---|---|
| Rule-pack load | ~296 ms |
| Model cold load + first inference | ~340 ms |
| Single inference (p50 / p95) | 13 / 28 ms |
| End-to-end verdict (p50 / p95 / p99) | 177 / 402 / 1238 ms |
| Peak process memory (PSS) | ~276 MB |

A real device and a reduced-op runtime would move all of these. The real-device Macrobenchmark run
is deferred rather than faked on an emulator.

## What it promises, even half-built

- No message text ever leaves the device. There is no server-side classification path.
- No SMS-reading permissions. You share a message in or paste it, and that's the only way in.
- Works without the model. The rule engine is meant to stand on its own, with the model as an
  enhancement rather than a dependency.
- Every verdict comes with a reason. No unexplained "72% scam" numbers.

## The model

A standalone `MODEL_CARD.md` would be the usual home for this, but the repo keeps exactly one tracked
markdown file, so the card lives here until there's a real model worth its own document.

- What it is: a small transformer text classifier, distilled and vocabulary-pruned, exported to ONNX
  and INT8-quantized. It has two heads, a binary scam/not-scam head (temperature-calibrated) and a
  13-way scam category head, and it runs on-device through ONNX Runtime with a HuggingFace tokenizer.
- What's bundled today: a toy model trained on about 180 English rows from the public UCI SMS Spam
  Collection. It's there to prove the on-device path works, not to catch real scams. It knows nothing
  about Hindi, romanized Hinglish, or Indian bank SMS.
- Intended use: one signal among several, fused with the rules, never the sole basis for a verdict.
  The rules alone are the shipped guarantee.
- Known limitations: English-only and tiny, so its accuracy numbers aren't meaningful, and with it
  live it wrongly flags genuine bank SMS. That's why it isn't bundled in release builds by default.
  The model assets are generated, git-ignored, and copied in only for local testing.
- Privacy: inference is fully on-device. No message text is ever sent anywhere for classification.
- Reproduce: `cd ml && make teacher distill prune export calibrate` (the pipeline lives in `ml/`).

## Building

You'll need JDK 17+ and the Android SDK (platform 35, build-tools 35).

```bash
./gradlew build
```

## License

Not decided yet.

---

If you're reading this on GitHub and it looks unfinished, it is. Come back later, or open an issue
if something here seems worth talking about.
