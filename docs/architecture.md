# ScamShield — Architecture

> **Audience: the implementing agent.** Read this first, then `design.md`, then
> `implementation.md`. This document defines *what the system is made of and why*. It does not
> specify algorithms (see `design.md`) or build order (see `implementation.md`).
>
> **Rule for the agent:** if any instruction in this document conflicts with a later document,
> this one wins on structure and constraints; `design.md` wins on algorithm detail;
> `implementation.md` wins on sequencing. If you believe a constraint here is wrong, stop and
> raise it rather than silently deviating.

---

## 1. System goals

| ID | Goal | Verification |
|---|---|---|
| G1 | Classify a pasted/shared text message as SAFE / SUSPICIOUS / SCAM | Automated eval suite |
| G2 | Produce human-readable evidence for every verdict | Every verdict has ≥1 `Evidence` object |
| G3 | Operate fully offline with no message text leaving the device | Network-permission audit test |
| G4 | p95 end-to-end latency ≤ 150 ms on a Snapdragon 680-class device | Macrobenchmark in CI |
| G5 | APK ≤ 25 MB download size | Gradle size check in CI |
| G6 | Usable by a non-English-speaking 60-year-old | a11y test suite + 7-language coverage |

## 2. Non-goals — do not build these

- SMS/notification interception or any `READ_SMS` / `RECEIVE_SMS` permission. **Hard prohibition.**
  Play Store policy will reject it. Entry is share-sheet + clipboard + manual paste only.
- Any server-side classification path.
- Message blocking, deletion, auto-reply, or call blocking.
- User accounts, cloud sync, contact access, or any PII collection.
- LLM-generated explanations. Explanations are template-driven (deterministic, offline, translatable).

## 3. Hard constraints

| ID | Constraint | Rationale |
|---|---|---|
| C1 | Message text must never be written to network, logs, or crash reports | Core trust promise |
| C2 | Only `INTERNET` permission may be requested, and only for opt-in domain lookup | Minimal surface |
| C3 | Model artifact ≤ 18 MB on disk | APK budget (G5) |
| C4 | Inference must run on CPU with no GPU/NNAPI dependency | Device fragmentation |
| C5 | All user-visible strings live in `strings.xml` — no hardcoded literals | 7-language i18n |
| C6 | The rules engine must function with the model absent or failed to load | Graceful degradation |

**C6 is architecturally load-bearing.** The app must be a working product before any ML exists.
Build in that order.

## 4. Technology stack

| Layer | Choice | Version | Why this and not the alternative |
|---|---|---|---|
| Platform | Android | minSdk 26, targetSdk 35 | API 26 covers ~97% of live Indian devices; below that, ONNX Runtime support degrades |
| Language | Kotlin | 2.0+ | Coroutines map cleanly onto the parallel-analyzer fan-out |
| UI | Jetpack Compose | BOM 2024.09+ | Dynamic text scaling for a11y is far less painful than XML layouts |
| DI | Hilt | 2.52+ | Needed to swap real/fake analyzers in tests |
| Local store | Room | 2.6+ | History persistence; SQLite is already present |
| Inference | ONNX Runtime Mobile | 1.19+ | Smaller binary than LiteRT for transformer ops; mature INT8 support |
| Tokenizer | HuggingFace `tokenizers` Android bindings | 0.20+ | Must byte-match the training tokenizer — do not reimplement WordPiece by hand |
| Training | PyTorch + Transformers | 2.4+ / 4.44+ | Teacher/student distillation |
| Export | ONNX + onnxruntime quantization tools | opset 17 | Reproducible INT8 pipeline |
| Testing | JUnit5, Turbine, Robolectric, Compose UI Test, Macrobenchmark | current | — |

**Do not substitute the tokenizer implementation.** Training/serving tokenizer mismatch is the
single most common cause of silent on-device accuracy collapse. The exact `tokenizer.json` produced
at training time is shipped as an asset and loaded by the same library family.

## 5. Component structure

```
:app                      Compose UI, navigation, DI wiring
:core:model               Pure Kotlin domain types. Zero Android deps. Zero I/O.
:core:analysis            Orchestrator + fusion policy
:analyzer:classifier      ONNX model load, tokenize, infer
:analyzer:url             URL extraction + domain forensics
:analyzer:sender          DLT sender-ID heuristics
:analyzer:pattern         Structural regex patterns
:core:explain             Evidence -> localized explanation strings
:core:data                Room DB, rule-pack loading, preferences
:benchmark                Macrobenchmark module (not shipped)
ml/                       Python training pipeline (not part of Gradle build)
```

**Dependency rule — enforced, not advisory:** dependencies point inward toward `:core:model`.
`:core:model` depends on nothing. No `:analyzer:*` module may depend on another `:analyzer:*`
module. `:core:analysis` depends on the analyzer *interfaces* only, never their implementations —
implementations are bound by Hilt at the `:app` layer. Violating this makes the analyzers
untestable in isolation and will block the eval harness.

## 6. Data flow

```
        Share intent / clipboard / manual paste
                        │
                        ▼
              ┌──────────────────┐
              │  Ingest & Normalize │  unicode NFKC, zero-width strip,
              └──────────────────┘  homograph-preserving (see design.md §3.1)
                        │
                        │  RawMessage
                        ▼
              ┌──────────────────┐
              │    Orchestrator    │  coroutine fan-out, 400 ms budget
              └──────────────────┘
             ┌──────────┼──────────┬──────────┐
             ▼          ▼          ▼          ▼
        Classifier    URL      Sender     Pattern
        (ML, ~80ms) (rules)   (rules)    (rules)
             │          │          │          │
             └──────────┴────┬─────┴──────────┘
                             │  List<Signal>
                             ▼
                    ┌──────────────────┐
                    │   Fusion Policy    │  deterministic; rules can override model
                    └──────────────────┘
                             │  Verdict + List<Evidence>
                             ▼
                    ┌──────────────────┐
                    │ Explanation Builder │  template + string resources
                    └──────────────────┘
                             │
                    ┌────────┴────────┐
                    ▼                 ▼
              Result screen      Room (history)
```

**Concurrency contract:** all four analyzers run concurrently in a `coroutineScope` on
`Dispatchers.Default`. The orchestrator applies a 400 ms overall timeout. Any analyzer that times
out or throws yields a `Signal.Unavailable` and the fusion policy proceeds without it — it never
fails the request. The classifier is the only analyzer permitted to be absent entirely (C6).

## 7. The four analyzers

Every analyzer implements:

```kotlin
interface Analyzer {
    val id: AnalyzerId
    suspend fun analyze(message: NormalizedMessage): Signal
}
```

| Analyzer | Type | Latency budget | Can override model? | Notes |
|---|---|---|---|---|
| Classifier | ML | 120 ms | No | Distilled MuRIL student, INT8 ONNX |
| URL | Rules | 20 ms offline | **Yes** | Highest-precision evidence source |
| Sender | Rules | 5 ms | **Yes** | DLT header registry lookup |
| Pattern | Rules | 10 ms | No | Regex; contributes weight only |

Rule analyzers carry the explanatory burden; the classifier carries the recall burden. This split
is the reason for the hybrid design — see `design.md` §5 for the fusion policy.

## 8. The ML pipeline (offline, `ml/`)

```
Raw collection ──> Labeling ──> Splits ──> Teacher fine-tune (MuRIL-base, 236M)
                                                    │
                                                    ▼
                                          Knowledge distillation
                                                    │
                                                    ▼
                                    Student: 4 layers, hidden 384, 4 heads
                                                    │
                                                    ▼
                                    Vocabulary pruning 197K ──> 32K
                                                    │
                                                    ▼
                                    ONNX export ──> dynamic INT8 quantization
                                                    │
                                                    ▼
                                    ~15 MB artifact + tokenizer.json
```

Target: **≤ 2.5 points macro-F1 degradation** from teacher to final quantized student. If the drop
exceeds that, the recovery order is: (1) increase distillation epochs, (2) widen student hidden
size to 512, (3) fall back to 6 layers. Do not compensate by weakening the eval set.

Model outputs two heads:
- `binary`: scam probability (drives the verdict)
- `category`: 12-class scam taxonomy (drives which advice template is shown)

## 9. Non-functional requirements

| Property | Requirement | How verified |
|---|---|---|
| Latency | p95 ≤ 150 ms end-to-end, p99 ≤ 300 ms | `:benchmark` Macrobenchmark, gated in CI |
| Cold start | ≤ 800 ms to interactive | Macrobenchmark |
| Model load | Lazy, off main thread, ≤ 400 ms | Instrumented test |
| Memory | Peak ≤ 220 MB | Macrobenchmark memory metric |
| APK | ≤ 25 MB download | Gradle task, CI-gated |
| Offline | 100% of core function with airplane mode on | Instrumented test with network disabled |
| a11y | All interactive targets ≥ 48 dp, full TalkBack labels, text scales to 200% without clipping | Compose a11y test suite |
| Crash-free | ≥ 99.5% sessions | Play Console vitals |

## 10. Privacy architecture

This is a security-relevant property, so it is enforced structurally rather than by convention:

1. **`:core:model` and all analyzer modules have no network dependency declared.** Only
   `:core:data` may declare one. A build-time dependency check enforces this.
2. **No message text in logs.** A custom lint rule fails the build on any log call whose argument
   type traces to `RawMessage` or `NormalizedMessage`.
3. **Crash reporting is opt-in and scrubbed.** Message content is never attached.
4. **Domain lookup**, if the user enables it, sends *only* the extracted registrable domain
   (`example.com`), never the URL path, query, or surrounding text. This is a separate opt-in from
   analytics.
5. **Analytics**, if enabled, transmits aggregate counters only: verdict distribution, category
   distribution, latency histograms. Never text, never domains, never timestamps precise enough to
   correlate.
6. **Room history is stored in app-private storage**, purgeable from Settings, with a one-tap
   "delete everything" that is genuinely a `DELETE FROM` and a `VACUUM`.

## 11. Rule packs — updatable without a release

Rules are **data**, not code, because scam infrastructure changes weekly while the app ships
monthly.

```
assets/rulepack/v1/
  banks.json          registrable domains + DLT headers of Indian banks/govt
  typosquat.json      confusable character map + distance thresholds
  shorteners.json     known URL shortener domains
  patterns.json       regex patterns with IDs, weights, category hints
  reputation.bin      bundled offline domain reputation snapshot (bloom filter)
  meta.json           pack version, generated-at, schema version
```

A rule pack is validated against a JSON schema at load. **If validation fails, the app falls back
to the bundled pack and reports a non-fatal error — it never runs with a partially-loaded pack.**
Rule packs are versioned independently of the app and may later be fetched over the network; v1
ships bundled only.

## 12. Architecture decision records

**ADR-001 — Hybrid rules + ML, not end-to-end ML.**
An end-to-end classifier gives a score, not an argument. G2 requires evidence a user can follow, and
a transformer cannot produce "this domain is 4 days old." Rules also degrade far more slowly under
adversarial drift. *Cost:* two systems to maintain and a fusion policy to calibrate. Accepted.

**ADR-002 — On-device inference, not server.**
Directly follows C1. *Cost:* 16× model compression, and no ability to hot-fix the model. Accepted;
the rule pack provides the hot-fix path instead.

**ADR-003 — MuRIL as teacher, not XLM-R or IndicBERT.**
MuRIL is explicitly pretrained on transliterated Indian-language text. Romanized Hinglish is the
dominant real-world input class and the one where XLM-R degrades most. *Cost:* MuRIL's 197K
vocabulary is unusually large, which is precisely why vocabulary pruning is a required pipeline
stage rather than an optimization.

**ADR-004 — Share-sheet entry, not SMS interception.**
Play policy (§3, C1). *Cost:* the user must take an action; the app cannot warn proactively.
Accepted — a rejected app protects nobody.

**ADR-005 — Templated explanations, not on-device LLM.**
A generative model large enough to explain well would blow the size and latency budgets, and would
be non-deterministic in a safety-critical output. Templates are auditable and translate cleanly into
7 languages. *Cost:* explanations are less fluent. Accepted.

**ADR-006 — Two-head model (binary + category), not 13-way single-head.**
Verdict confidence and category confidence have different operating points; a scam can be confidently
malicious but ambiguous in type. Separate heads let the app show a strong warning with a hedged
category rather than suppressing both.

---

*Next: `design.md` for algorithms, schemas, and UI specification.*
