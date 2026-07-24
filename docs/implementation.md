# ScamShield — Implementation Plan

> **Audience: the implementing agent.** Read `architecture.md` and `design.md` before this file.
> This document defines repository layout, build order, and the acceptance criteria for each phase.
>
> **Working rules:**
> 1. Phases are sequential. Do not start phase N+1 until phase N's acceptance criteria pass.
> 2. Every phase ends with a green build, green tests, and a commit.
> 3. When a decision is underdetermined by these docs, choose the simpler option, implement it, and
>    record the choice in `DECISIONS.md` with one sentence of rationale. Do not stall.
> 4. Never weaken a test or an eval threshold to make a phase pass. If a target is unreachable,
>    record the actual number and continue — a truthful 0.87 F1 is worth more than a fabricated 0.92.
> 5. Do not fabricate benchmark numbers. Every metric in the README must come from a committed
>    script that can be re-run.

---

## 0. Repository layout

```
scamshield/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle/libs.versions.toml          # version catalog — all deps declared here
├── app/
│   └── src/{main,test,androidTest}/
├── core/
│   ├── model/                          # pure Kotlin, no Android
│   ├── analysis/                       # orchestrator + fusion
│   ├── explain/                        # evidence -> localized strings
│   └── data/                           # Room, rulepack loader, prefs
├── analyzer/
│   ├── classifier/
│   ├── url/
│   ├── sender/
│   └── pattern/
├── benchmark/                          # Macrobenchmark, not shipped
├── ml/
│   ├── pyproject.toml
│   ├── data/
│   │   ├── raw/                        # gitignored
│   │   ├── processed/                  # gitignored
│   │   └── samples/                    # 200 committed rows for CI smoke tests
│   ├── src/scamshield_ml/
│   │   ├── collect.py  label.py  split.py
│   │   ├── train_teacher.py  distill.py  prune_vocab.py
│   │   ├── export_onnx.py  quantize.py  calibrate.py
│   │   └── evaluate.py
│   ├── EXPERIMENTS.md                  # append-only log of every run
│   └── Makefile
├── rulepack/
│   ├── src/                            # authored JSON
│   ├── build_rulepack.py               # validates + emits to app assets
│   └── schema/                         # JSON schemas
├── docs/{architecture,design,description}.md
├── DECISIONS.md
└── README.md
```

---

## Phase 0 — Scaffold

**Build:**
- Gradle multi-module project per §0, version catalog in `libs.versions.toml`.
- Hilt wired in `:app`. Compose + Material3. minSdk 26, targetSdk 35.
- `:core:model` with every type from `design.md` §1. No logic — types only.
- `AndroidManifest.xml` declaring the share-sheet intent filter:
  ```xml
  <intent-filter>
      <action android:name="android.intent.action.SEND" />
      <category android:name="android.intent.category.DEFAULT" />
      <data android:mimeType="text/plain" />
  </intent-filter>
  ```
- CI (GitHub Actions): assemble, unit tests, lint, APK size report.

**Acceptance:**
- [ ] `./gradlew build` green
- [ ] App installs; sharing text from any app opens ScamShield with the text populated
- [ ] `:core:model` has zero Android dependencies (verified by a dependency-check task)
- [ ] CI green on push

---

## Phase 1 — Rules engine and working app (no ML)

**This is the most important phase.** At its end you have a genuinely useful, shippable app. The
model is an enhancement, not a prerequisite (architecture C6).

**Build:**
1. `rulepack/src/*.json` with real data:
   - `banks.json` — ≥ 120 Indian banks/wallets/govt portals: registrable domain, display names, aliases, DLT header prefixes
   - `shorteners.json` — ≥ 40 shortener domains
   - `typosquat.json` — confusable character map
   - `patterns.json` — ≥ 60 patterns across 7 languages (`design.md` §5)
   - PSL snapshot
   - `reputation.bin` — Bloom filter; seed from a public top-1M domain list
2. `build_rulepack.py`: JSON-schema validate → emit to `app/src/main/assets/rulepack/v1/`. **Build
   fails on invalid pack.**
3. Normalization + URL extraction (`design.md` §2)
4. `:analyzer:url`, `:analyzer:sender`, `:analyzer:pattern` — full spec in `design.md` §3–5
5. `:core:analysis` — orchestrator (coroutine fan-out, 400 ms timeout) + fusion policy (§7), with
   the classifier signal permanently `Unavailable`
6. `:core:explain` — templates for every `EvidenceType`, English only for now
7. Compose UI: Check and Result screens

**Acceptance:**
- [ ] Fixture suite (`design.md` §12) passes with the classifier stubbed
- [ ] URL analyzer correctly flags: `sbı.com` (homograph), `xn--sb-xkc.com` (punycode), `sbi-kyc-verify.xyz` (substring typosquat), `http://192.168.1.1/login` (IP host), `bit.ly/x` (shortener)
- [ ] Genuine bank SMS fixtures return SAFE — **zero tolerance, all 15 must pass**
- [ ] Every verdict carries ≥ 1 `Evidence`
- [ ] Corrupt rulepack → falls back to bundled pack, app still functions
- [ ] End-to-end analysis p95 < 50 ms (rules only)
- [ ] Manual: share a real phishing SMS, get a correct explained verdict

---

## Phase 2 — Dataset

**Budget ~30% of total project time here.** It determines the model's ceiling.

**Build:**
1. `collect.py` — source adapters writing normalized JSONL (`design.md` §9.1). Scrub PII to
   placeholders **at ingest**, never later.
2. `label.py` — CLI labeling tool: shows a message, accepts binary + category, writes with
   provenance. Support re-labeling and an `--audit` mode that re-serves 5% for consistency checking.
3. `LABELING_GUIDE.md` — the decision rule for every category, plus the four hardest boundary cases
   (promotional-but-genuine, genuine-with-shortener, scam-without-URL, debt-collection-vs-threat).
4. `split.py` — cluster-aware splitting (`design.md` §9.3). **Must assert zero near-duplicate
   overlap across splits** (MinHash, Jaccard > 0.8 → same split). Fail loudly.

**Targets:** ≥ 12,000 labeled rows, ≥ 30% genuine hard negatives, ≥ 1,000 rows per major language
for HI/EN/HI_LATN and ≥ 400 for BN/TA/TE/MR.

**Acceptance:**
- [ ] ≥ 12,000 rows, composition matching `design.md` §9.2 within 10%
- [ ] `split.py` leakage assertion passes
- [ ] Labeling consistency (audit re-labels) ≥ 0.85 agreement
- [ ] Zero raw phone numbers, account numbers, or personal names remain — verified by a regex scan test
- [ ] 200-row sample committed to `ml/data/samples/` for CI

---

## Phase 3 — Model training

**Build:** stages A–E from `design.md` §9.4, each as a separate script with a `make` target.

```
make teacher    # Stage A
make distill    # Stage B
make prune      # Stage C
make export     # Stage D
make calibrate  # Stage E
make evaluate   # full eval report
```

Every run appends to `ml/EXPERIMENTS.md`: config hash, dataset version, all metrics from
`design.md` §9.5, wall-clock, artifact size. **Append-only — never edit a past entry.**

**Acceptance:**
- [ ] Teacher macro-F1 ≥ 0.92 on test
- [ ] Final quantized student macro-F1 ≥ 0.90, and within 2.5 points of teacher
- [ ] Scam-class recall ≥ 0.93
- [ ] FPR on the genuine-bank-SMS subset ≤ 3%
- [ ] Per-language F1 reported for all 7; none below 0.80
- [ ] Artifact ≤ 18 MB (model + tokenizer)
- [ ] **ONNX parity gate passes** — max probability delta < 0.02 across 200 fixed samples
- [ ] `EXPERIMENTS.md` contains every run, including failures

> If macro-F1 misses 0.90: apply the recovery order in `architecture.md` §8 (more distillation
> epochs → hidden 512 → 6 layers). Re-check size after each. **Do not** shrink the test set,
> re-split, or drop hard negatives.

---

## Phase 4 — On-device integration

**Build:**
1. Model + `tokenizer.json` + `meta.json` into `app/src/main/assets/model/`
2. `:analyzer:classifier`: ONNX Runtime session (lazy init, `Dispatchers.IO`, 1-thread intra-op),
   HF tokenizer bindings, temperature calibration from `meta.json`, all failures → `Signal.Unavailable`
3. Enable the classifier in the fusion policy; re-tune nothing — thresholds in `design.md` §7 assume
   a calibrated model
4. `:benchmark` Macrobenchmark: cold start, single inference, end-to-end p50/p95/p99, peak memory

**Acceptance:**
- [ ] **On-device parity:** the same 200 samples run through the Android classifier match the ONNX
      Python output within 0.02. A mismatch here is almost always a tokenizer discrepancy — fix the
      tokenizer, do not adjust thresholds to compensate.
- [ ] p95 end-to-end ≤ 150 ms on a Snapdragon 680-class device or equivalent emulator profile
- [ ] Model load ≤ 400 ms, off the main thread, no jank on the Check screen
- [ ] Peak memory ≤ 220 MB
- [ ] Delete the model asset → app still gives rule-based verdicts, no crash
- [ ] Airplane-mode instrumented test: full functionality
- [ ] APK ≤ 25 MB download size

---

## Phase 5 — Localization, accessibility, polish

**Build:**
- Translate all strings to `hi, bn, ta, te, mr` + in-app `hi-Latn`. **Native-speaker review for
  Hindi and Hinglish is required, not optional** (`design.md` §10.4).
- History, Learn (8 cards × 7 languages), Settings screens
- TTS integration with per-language voice selection and graceful fallback when a voice is missing
- Full a11y pass per `design.md` §10.3
- Onboarding: 3 screens, no account, ending in a live demo check on a sample message

**Acceptance:**
- [ ] Zero hardcoded user-visible strings (lint rule enforced)
- [ ] All 7 languages render without clipping at 200% text scale
- [ ] Compose a11y test suite green; manual TalkBack walkthrough of Check → Result completes coherently
- [ ] Contrast ≥ 4.5:1 verified for every text/background pair
- [ ] TTS reads a full verdict in Hindi and English
- [ ] Onboarding completable by a first-time user in < 60 s

---

## Phase 6 — Privacy hardening and release

**Build:**
- Custom lint rule failing the build on any log call reachable from `RawMessage`/`NormalizedMessage`
- Dependency-check task: no analyzer or `:core:model` module may depend on a network library
- Opt-in toggles (both default **off**): online domain lookup, aggregate analytics
- Privacy policy page — plain language, hosted, linked from Settings and the Play listing
- Play Store listing: 7-language descriptions, screenshots, feature graphic
- Signed release build, Play Console internal testing track

**Acceptance:**
- [ ] Network-audit instrumented test: run 50 fixture messages with a traffic interceptor; **assert
      zero outbound requests** with default settings
- [ ] `logcat` scan during a full analysis run contains no message text
- [ ] Lint and dependency-check rules fail correctly when deliberately violated (test the test)
- [ ] Play pre-launch report clean
- [ ] Data-safety form matches actual behavior exactly

> **Do not ship to production until the network-audit test is green.** It is the one claim the whole
> project rests on.

---

## Phase 7 — Benchmarks, README, distribution

**Build:**
- `README.md`: one-paragraph problem statement, 30-second demo GIF, architecture diagram, **the
  metrics table with methodology**, model card, honest limitations (`description.md` §6), build
  instructions
- `MODEL_CARD.md`: training data composition and sources, per-language performance, known failure
  modes, intended use and misuse
- Reproducible benchmark script committed
- Distribution: Play Store release, city subreddits, local senior-citizen associations, regional
  newspaper tech desks

**Acceptance:**
- [ ] Every README number is reproducible from a committed script
- [ ] Limitations section is present and specific
- [ ] A stranger can build and run from the README alone
- [ ] Public release live

---

## Definition of done

- All 7 phases' criteria met
- `EXPERIMENTS.md` complete, including failed runs
- `DECISIONS.md` records every underdetermined choice
- Zero network calls in the default configuration, proven by test
- README metrics all reproducible

---

## Common failure modes — check these first when something is wrong

| Symptom | Almost always |
|---|---|
| On-device accuracy far below Python eval | Tokenizer mismatch. Compare `input_ids` for the same string on both sides before touching anything else. |
| Test F1 suspiciously high (> 0.97) | Split leakage. Re-run the MinHash assertion in `split.py`. |
| Model flags every urgent-sounding message | Not enough genuine hard negatives. Go back to phase 2. |
| Verdicts feel arbitrary to users | Evidence list is thin. Rules, not the model, produce explanations — add patterns. |
| p95 latency spikes | Model loading on the analysis path. It must be lazy-initialized ahead of first use. |
| INT8 model much worse than FP32 | Embeddings were quantized. Keep them FP32 (`design.md` §9.4 Stage D). |

---

## Suggested schedule (~5 weeks solo)

| Week | Focus |
|---|---|
| 1 | Phase 0 + Phase 1 (rules app working end-to-end) |
| 2 | Phase 2 (dataset — the long pole) |
| 3 | Phase 3 (training) + start Phase 4 |
| 4 | Phase 4 complete + Phase 5 |
| 5 | Phase 6 + Phase 7, release |

If time runs short, cut in this order: languages beyond HI/EN/HI_LATN → Learn screen → History
screen. **Never cut** the hard-negative dataset work, the parity gates, or the network-audit test.
