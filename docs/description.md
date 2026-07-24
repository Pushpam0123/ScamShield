# ScamShield — Project Description

> **Audience: humans.** This document explains what the project is, why it exists, how it works,
> and what it is built with — for a reader with no prior context. It contains no build
> instructions. Agents building this project should read `architecture.md` → `design.md` →
> `implementation.md` instead.

---

## 1. The problem

India lost over ₹22,000 crore to cyber fraud in a single year, and the volume is growing faster
than any enforcement mechanism. The delivery vehicle is almost always a text message: an SMS, a
WhatsApp forward, a Telegram DM.

The messages are effective because they are *plausible*. They copy the tone, formatting, and
urgency of genuine bank and government communications:

```
Dear Customer, your SBI KYC will expire TODAY. Your account will be
frozen. Update immediately: http://sbi-kyc-verify.xyz/update
```

A person who has used a smartphone for two years and a person who has used one for two months
receive the identical message. The second person has no way to evaluate it. They cannot inspect a
domain, they do not know that real banks never send links for KYC, and they do not know that
`sbi-kyc-verify.xyz` was registered four days ago in a jurisdiction with no Indian presence.

The people hit hardest are the least equipped to defend themselves: parents, grandparents,
first-time internet users, people operating in a second language, people in a hurry.

**Existing tools do not solve this for them.** Bank awareness campaigns are broadcast, not
per-message. Spam filters silently drop messages without teaching anything. Web-based "check this
link" tools require a person to already suspect the message — which is exactly the judgment they
lack. And nearly all of them are English-only, while the messages that reach a Tier-3 town arrive
in Hindi, in Marathi, or most commonly in romanized Hinglish that no standard NLP pipeline handles.

## 2. What ScamShield is

An Android app that answers one question: **"Is this message trying to cheat me?"**

The user shares any suspicious message into the app — from the SMS app, from WhatsApp, from
anywhere with a share button — or pastes it. ScamShield responds in under a second, in the user's
own language, with three things:

1. **A verdict** — Safe, Suspicious, or Scam, with a confidence indication.
2. **The reasoning, in plain words.** Never a bare score. *"This message says it is from SBI. But
   the link goes to `sbi-kyc-verify.xyz`, a website created 4 days ago. Real SBI messages come from
   senders like `VM-SBIINB` and never ask you to update KYC through a link."*
3. **What to do next.** Concrete and actionable: do not click, do not share the OTP, call the number
   printed on the back of your card, report at cybercrime.gov.in.

The explanation matters more than the verdict. A verdict protects a person once. An explanation
teaches them the pattern, and the pattern generalizes to the next message — which will be slightly
different, because the people sending them adapt.

### The design constraint that shapes everything

**The message never leaves the phone.**

People forward ScamShield exactly the messages they are most anxious about — messages containing
account numbers, transaction amounts, employer names, personal details. Uploading those to a server
would make the app a liability rather than a protection, and would make it unusable for the trust-
sensitive users who need it most.

So classification runs entirely on-device. That single constraint drives most of the interesting
engineering in this project: it means a 240 MB multilingual transformer will not do, and the model
must be compressed roughly 16× while keeping its accuracy on romanized Hinglish — the hardest and
least-supported input class.

## 3. How it works

Four independent analyzers examine every message, and their outputs are fused into one verdict.

**Analyzer 1 — Text classifier (machine learning).**
A neural network trained on real scam messages, running on the phone. It reads the *language* of
the message: manufactured urgency, threats of account closure, requests for OTPs, promises of
lottery winnings or unrealistic job offers. It also predicts the *category* of scam, which is what
lets the app produce specific advice rather than a generic warning.

**Analyzer 2 — URL forensics (deterministic rules).**
Where the classifier reads language, this reads infrastructure, and it is where most of the
*explainable* evidence comes from. It checks how old the domain is, whether it impersonates a real
Indian bank through typo-squatting (`hdfcbank` vs `hdfcbanc`), whether it uses Unicode homograph
attacks (`sbı.com`, with a dotless Turkish ı, renders almost identically to `sbi.com`), whether it
hides behind a link shortener, whether the visible text of a link matches its actual destination.

These rules are not smarter than the model. They are *more certain*, and unlike the model they can
state their reasoning exactly — which is what the user actually needs.

**Analyzer 3 — Sender heuristics.**
Indian commercial SMS is regulated: legitimate senders must register a header under the TRAI DLT
framework, producing IDs like `VM-SBIINB` or `AD-HDFCBK`. A message claiming to be from a bank that
arrives from a 10-digit mobile number is, structurally, not from that bank. This is a strong and
cheap signal.

**Analyzer 4 — Structural patterns.**
Regex-level detection of the mechanics of a scam: OTP solicitation, UPI collect-request framing
(where the victim *approves* a payment believing they are receiving one), premium-rate numbers,
crypto wallet addresses.

**Fusion.**
The four signals combine through a deterministic policy, not a black box. High-precision rule hits
can override the model outright: a 3-day-old domain impersonating a bank is a scam regardless of
how innocuous the wording is. Everything else is a calibrated weighted combination. This ordering
is deliberate — it means the app's most confident verdicts are always the ones it can explain best.

---

## 4. Technology stack

Every choice below is driven by one constraint: the message must never leave the phone, and the
whole thing must run in under 150 ms on a ₹8,000 Android device.

### The app

| Technology | Role | Why this |
|---|---|---|
| **Kotlin** | The app language | Coroutines map cleanly onto running four analyzers concurrently and cancelling any that runs over budget |
| **Jetpack Compose** | The user interface | Text scaling for accessibility is dramatically less painful than with XML layouts — and this app's users need 200% text scaling to actually work, not just technically exist |
| **Hilt** | Dependency injection | Lets real analyzers be swapped for fakes in tests. Without it, the analyzers can't be tested in isolation and the eval harness becomes impossible |
| **Room (SQLite)** | Local history storage | Keeps checked messages on-device. There is no server to sync to, by design |
| **Android share-sheet intent** | How messages get in | The user shares from WhatsApp/SMS into the app. Deliberately *not* SMS interception — see §5 |

### On-device machine learning — the interesting part

| Technology | Role | Why this |
|---|---|---|
| **MuRIL** (Google) | The teacher model | The only transformer family pretrained on *transliterated* Indian-language text. Romanized Hinglish (`aapka khata band ho jayega`) is the dominant real-world input, and it's exactly where general multilingual models like XLM-R degrade most |
| **PyTorch + HuggingFace Transformers** | Training pipeline | Fine-tuning the teacher, then knowledge distillation into a much smaller student |
| **Knowledge distillation** | Shrinks the model ~16× | MuRIL-base is 236M parameters — around 240 MB even quantized, far too large to ship. The student is 4 layers instead of 12, hidden size 384 instead of 768, trained to imitate the teacher's outputs rather than learning from scratch |
| **Vocabulary pruning** | The single biggest size win | MuRIL's vocabulary is 197,000 tokens, so the embedding table alone is ~151M parameters. Cutting it to the ~32,000 tokens that actually appear in scam messages drops that to ~12M — a bigger reduction than the layer cuts |
| **ONNX Runtime Mobile** | Runs the model on the phone | Smaller binary than the alternatives for transformer operations, and mature INT8 support. Runs on CPU with no GPU dependency, which matters given how fragmented Android hardware is |
| **INT8 dynamic quantization** | Final compression step | Weights stored as 8-bit integers instead of 32-bit floats. Applied to the attention and matrix-multiply layers but **not** the embeddings — quantizing those costs several accuracy points to save about 2 MB |
| **HuggingFace `tokenizers`** (Android bindings) | Converts text to model input | The *exact* tokenizer file from training ships as an app asset. A training/serving tokenizer mismatch is the most common cause of a model that scores well in Python and collapses on-device |

**End result:** roughly 15 MB on disk, under 100 ms per classification on a Snapdragon 680.

### The rules engine

| Technology | Role | Why this |
|---|---|---|
| **JSON rule packs** (app assets) | Bank domains, DLT headers, typosquat tables, regex patterns | Rules are **data, not code**, because scam infrastructure changes weekly while the app ships monthly. The pack is versioned separately and can later be updated over the network without an app release |
| **JSON Schema validation** | Guards the rule pack | An invalid pack falls back to the bundled one rather than running half-loaded |
| **Public Suffix List** (bundled) | Extracts the real domain | Naively taking "the last two labels" breaks immediately on `.co.in` and `.gov.in`, which are exactly the domains being impersonated |
| **Bloom filter** (bundled) | Offline domain reputation | Lets the app check whether a domain is well-established without any network call, preserving the offline-by-default promise |
| **Android TTS** | Reads verdicts aloud | For users who find listening easier than reading a screen — a large fraction of the target audience |

### Build and quality tooling

| Technology | Role |
|---|---|
| **Gradle** (multi-module) + version catalog | Enforces the module boundaries that keep analyzers independently testable |
| **JUnit5, Robolectric, Compose UI Test** | Unit, integration, and UI tests |
| **Macrobenchmark** | Measures real cold-start, latency, and memory on a device — these become the README's numbers |
| **Custom lint rule** | Fails the build if message text can reach a log call. The privacy promise is enforced by the compiler, not by discipline |
| **GitHub Actions** | CI: build, test, lint, APK size check |
| **Google Play** | Distribution — free, no account required to use the app |

**Why the split between ML and rules?** The model provides *coverage* — it catches scams whose
wording it has never seen. The rules provide *certainty and explanation* — they can state exactly
why a domain is suspicious in words a 60-year-old can follow. Neither alone produces a product that
both catches scams and teaches people to recognise them.

---

## 5. Who it is for

The primary user is someone the technology industry generally does not design for: 50+, not
confident in English, using a mid-range Android phone, and being actively targeted.

Design consequences:

- **No account, no login, no permissions beyond storage.** Anything that looks like a signup form
  will lose most of this audience at the first screen.
- **Large text and high contrast by default**, not as an accessibility toggle buried in settings.
- **Text-to-speech readout** of every verdict.
- **Full UI translation** across seven languages, including romanized Hinglish as a first-class
  option rather than a fallback.
- **The app works completely offline.** No network, no degradation.

The secondary user is the adult child who installs it on a parent's phone. That person is the
distribution channel, so the app must be trivially explainable in one sentence over a phone call.

## 6. What is deliberately not built

- **No automatic SMS interception.** Google Play severely restricts the SMS-read permission, and a
  solo-developer app requesting it will be rejected in policy review. Share-sheet and clipboard
  entry avoid the problem entirely — and are the better privacy posture regardless.
- **No cloud classification**, for the reasons in §2.
- **No blocking, deleting, or auto-replying.** ScamShield informs; the user decides. An app that
  silently deletes a message that turns out to be a genuine hospital bill reminder has done more
  harm than good.
- **No user-account or contact-graph features.** Nothing that turns a safety tool into a data
  business.

## 7. Honest limitations

- **Adversarial drift.** Scam wording changes continuously. A model trained today decays. The
  mitigation is that the rule engine — which carries most of the explanatory weight — degrades far
  more slowly than the classifier, and rules ship as updatable data rather than requiring a model
  release.
- **Language coverage is uneven.** Hindi and English are strongest; Tamil, Telugu, Bengali and
  Marathi have less training data and correspondingly lower recall. This is measured and published
  per-language rather than hidden behind an aggregate number.
- **Domain-age checks need network.** Full WHOIS lookup requires a request. The app ships with a
  bundled offline reputation snapshot so the default path stays fully local; live lookup is an
  explicit opt-in and transmits only the extracted domain, never message text.
- **It cannot catch a well-written scam with clean infrastructure.** A grammatically perfect message
  with no link, from a real number, asking the victim to call back, is close to indistinguishable
  from a legitimate message. Recall is not 100% and the app should never imply otherwise — "looks
  safe" is worded carefully to avoid becoming an endorsement.

## 8. How success is measured

| Dimension | Metric | Target |
|---|---|---|
| Model quality | Macro F1 on held-out test set | ≥ 0.90 |
| Model quality | Recall on scam class | ≥ 0.93 (missing a scam is worse than a false alarm) |
| Model quality | False-positive rate on genuine bank SMS | ≤ 3% |
| Performance | p95 end-to-end latency, Snapdragon 680 | ≤ 150 ms |
| Size | APK download size | ≤ 25 MB |
| Reach | Play Store installs, 8 weeks | 2,000+ |
| Reach | Play Store rating | ≥ 4.3 |

The false-positive target on *genuine* bank messages is the hardest and most important one. Real
bank SMS and phishing SMS are lexically near-identical; the entire difficulty of the ML problem
lives in that gap, which is why the dataset deliberately over-samples genuine bank messages as hard
negatives.

## 9. Why this is technically interesting

Three distinct problems, none of which have an off-the-shelf answer:

1. **Model compression under a hard accuracy floor.** Distillation, vocabulary pruning, and
   quantization compose in ways that interact badly; recovering the last few points of accuracy
   after 16× compression is genuine work.
2. **Dataset construction where no dataset exists.** There is no labeled corpus of Indian scam SMS.
   Building one — and specifically mining *hard negatives*, since the easy version of this problem
   is trivially solvable and the real version is not — is roughly half the total effort.
3. **Explainability as a product requirement, not a nice-to-have.** The output is not a score, it is
   an argument a 60-year-old can follow. That constraint is what forces the hybrid
   rules-plus-model architecture rather than an end-to-end classifier.

---

*Companion documents: `architecture.md` (system structure), `design.md` (detailed component design),
`implementation.md` (build plan).*
