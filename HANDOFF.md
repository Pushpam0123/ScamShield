# Handoff — ScamShield

Last updated 2026-07-25, end of the second session. Read `docs/architecture.md`, then
`docs/design.md`, then `docs/implementation.md` before touching code. This file only covers
what those documents cannot: the state of *this* machine and *this* repository.

---

## 1. Where the work stands

**Phase 0 (Scaffold) is complete and its build criteria are verified.**
**Phase 1.1 (rule-pack source data) is complete except for `reputation.bin`.**

Eleven commits on `main`, no remote configured:

```
46f27ce  Phase 1.1: 71 structural patterns across 7 languages
07f79e5  Phase 1.1: shortener list and confusable folding table
a0fdbe2  Phase 1.1: brand registry and Public Suffix List snapshot
4e5f301  Add HANDOFF.md and record D-009
a780621  Phase 0.5: privacy-boundary dependency check, APK size gate, CI
98dc3d9  Phase 0.4: :app skeleton with share-sheet ingest
636d79d  Phase 0.3: :core:model domain types
b4d2b2d  Phase 0.2: pin Gradle wrapper to 8.11.1
d3c1dcd  Phase 0.2: Gradle multi-module scaffold and version catalog
a364fe4  Phase 0.1: initialise repository layout
```

Working tree is clean.

### Phase 0 acceptance criteria

| Criterion | State |
|---|---|
| `./gradlew build` green | **Done.** Full clean build passes in ~4 min. |
| App installs; sharing text opens ScamShield with the text populated | **Still not verified.** No emulator run yet — see §2. |
| `:core:model` has zero Android dependencies, verified by a dependency-check task | **Done.** `./gradlew checkPrivacyBoundary`, and verified negatively by adding okhttp to `:core:analysis` and watching it fail. |
| CI green on push | **Not possible yet.** No git remote, and the workflow calls `rulepack/build_rulepack.py`, which Phase 1.2 creates. |

Only the emulator row is outstanding, and it is blocked on §2.

---

## 2. Toolchain on this machine

All of this was installed by the agent; none of it pre-existed.

| Tool | Location | Note |
|---|---|---|
| JDK 17 | `/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home` | **Required.** System default is JDK 20; Homebrew also has JDK 26. Neither is what AGP wants. |
| Gradle 8.11.1 | via `./gradlew` | Homebrew's `gradle` is 9.6.1, which AGP 8.7 does not support. Always use the wrapper. |
| Android SDK | `/opt/homebrew/share/android-commandlinetools` | platform 35, build-tools 35.0.0, platform-tools. Licences accepted. |
| Python | `python3` 3.10.6 | No venv yet. `jsonschema` is **not installed** — Phase 1.2 needs it. |

**Every Gradle command must set `JAVA_HOME`:**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home && ./gradlew build
```

### Emulator

`system-images;android-35;google_apis;arm64-v8a` is installed (verified: the directory
`/opt/homebrew/share/android-commandlinetools/system-images/android-35` exists). **No AVD has
been created and the emulator has never been launched.** Phase 0's last acceptance row — share
text from another app and see it populate ScamShield — is waiting on this:

```bash
avdmanager create avd -n scamshield-35 -k "system-images;android-35;google_apis;arm64-v8a" --device pixel_6
```

---

## 3. What exists in the code

Structure follows `implementation.md` §0, with the deviations recorded in `DECISIONS.md`
(D-001 specs in `docs/`, D-007 `:benchmark` deferred to Phase 4).

- **`:core:model`** — every type from `design.md` §1, plus `Analyzer` (D-002),
  `NormalizedMessage.brandClaims` (D-003), the rule-pack types (D-004), and
  `PatternRule.suppressIf` (D-010). Plain Kotlin JVM, zero dependencies. Five contract tests.
- **`:app`** — manifest (INTERNET only; `SEND` and `PROCESS_TEXT` filters), Hilt application,
  `MainActivity`, Compose theme with the §10.2 verdict colours and 18 sp body text, seeded
  `strings.xml`, backup rules excluding every domain. `ScamShieldApp` is a placeholder that
  displays shared text; Phase 1.10 replaces it.
- **`:core:analysis`, `:analyzer:{url,sender,pattern}`** — build files only, no source.
- **`:core:explain`, `:core:data`, `:analyzer:classifier`** — build files and empty manifests.
- **`buildSrc`** — the `scamshield.privacy-boundary` convention plugin.
- **`rulepack/src/`** — see §4.

---

## 4. Rule-pack data: what is authored and what each file assumes

Four of five files are done. **`reputation.bin` is not started** — it is the one remaining
Phase 1.1 deliverable, and it is produced by `build_rulepack.py` in Phase 1.2 rather than
authored by hand: a Bloom filter seeded from a public top-1M domain list plus an explicit
allowlist built from the `domains` field of `banks.json`.

| File | Size | Notes |
|---|---|---|
| `banks.json` | 150 brands, 220 domains, 292 aliases | Banks, SFBs, wallets, UPI apps, telecoms, government portals, NBFCs, brokers, couriers, marketplaces, electricity distributors. |
| `shorteners.json` | 63 shorteners, 2 brand-operated | |
| `typosquat.json` | 187 char folds, 4 sequence folds, 47 TLDs | |
| `patterns.json` | 71 patterns | EN 29, HI_LATN 36, HI 18, MR 18, BN 11, TA 11, TE 11. All 8 evidence types. |
| `public_suffix_list.dat` | 16,409 rules | |

**Three things a loader must honour, or the data will misbehave:**

1. **DLT headers are many-to-many.** `GOOGLE` belongs to both `google` and `gpay`; `AMAZON` to
   both `amazon` and `amazonpay`. The loader must build `header -> Set<brandId>` and fire
   `DLT_HEADER_MISMATCH` only when that set is *disjoint* from the brands the message claims.
   A one-to-one map raises the mismatch on genuine Google Pay SMS.
2. **`suppress_if` must be evaluated before `pattern`.** It is the hard-negative guard, and it
   is the reason the genuine-bank fixtures can pass. Verified working against a genuine-vs-scam
   smoke set: `"never asks you to share OTP"`, `"Bank will not ask you to share it"`, the
   Hinglish `"kisi ko share na karein"`, and `"card has been blocked as per your request"` all
   stay silent, while six scam phrasings all fire.
3. **Confusable folding runs on the ORIGINAL host string, never the normalized one.** NFKC
   already collapses some homographs, so folding the normalized form finds nothing. Verified:
   `sbı.com`, `ѕbi.com`, `раytm.com`, `ахisbank.com` and `hdfcbaηk.com` all fold to their real
   brands.

Deliberate omissions, both to protect the zero-tolerance genuine-bank fixtures — do not
"complete" them without re-running those fixtures:

- No leetspeak digit→letter folds. `design.md` §3.2's `squash` rule already strips digits.
- No mainstream TLDs in `suspicious_tlds` (`.info .online .site .store .shop .space .live
  .biz`). Indian small businesses use all of them.

---

## 5. Open problems, in the order you will hit them

1. **`jsonschema` is not installed.** Phase 1.2 and the CI workflow both need it.
   `python3 -m pip install jsonschema`. Consider a venv under `rulepack/` or `ml/`.
2. **The emulator image is unconfirmed** (§2), and Phase 0's last acceptance row depends on it.
3. **CI cannot pass until Phase 1.2** lands `rulepack/build_rulepack.py`.
4. **The tokenizer choice is unverified** (D-009). `ai.djl.huggingface:tokenizers:0.33.0` plus
   `ai.djl.android:tokenizer-native` resolves and compiles, but nothing has confirmed it loads
   on-device or byte-matches a training tokenizer. That is Phase 4's parity gate, and a
   mismatch there is the most common cause of silent accuracy collapse. Do not let it slide.
5. **Pattern regexes are validated with Python `re`, not Java.** The two dialects agree on
   everything used here, but Phase 1.6 must add a Kotlin test that compiles all 71 patterns
   through `kotlin.text.Regex` before trusting them.

---

## 6. Next task: Phase 1.2 — schemas and the rule-pack compiler

The Gradle task is already registered in the root `build.gradle.kts` as `buildRulepack`; it
shells out to `rulepack/build_rulepack.py`, which does not exist yet. Write:

- **`rulepack/schema/*.json`** — a JSON Schema per source file. Enforce the invariants the data
  files document in prose: every `single_char_folds` key exactly one character, every pattern
  `weight` in `0..1`, every `evidence` a member of the `EvidenceType` enum, every
  `category_hints` key a member of `ScamCategory`, no duplicate brand `id` or alias.
- **`rulepack/build_rulepack.py`** — validate, then emit to
  `app/src/main/assets/rulepack/v1/`. **Fail the build on an invalid pack**
  (`architecture.md` §11: the app must never run with a partially-loaded pack).
  It also builds `reputation.bin` (Bloom filter over a public top-1M list plus the `banks.json`
  domains as the allowlist) and writes `meta.json` with pack version, generated-at, and schema
  version.

Note the asset output path is gitignored, which is intentional — the pack is a build product.

Then Phase 1.3 (normalization, URL extraction, language detection) starts the Kotlin work.
Tasks 1.3–1.10 are in the session task list with one-line briefs.

---

## 7. Working agreements

- Commit after each sub-task; the message says which phase and *why*, not just what.
- `DECISIONS.md` gets a numbered row for every choice the specs left open. Ten exist,
  D-001…D-010.
- Never weaken a test or threshold to make a phase pass (`implementation.md` working rule 4).
  Record the real number instead.
- Do not fabricate a metric or a data point. When authoring rule-pack data, an entry that
  cannot be verified is left out — `brand_operated` in `shorteners.json` has two entries rather
  than a plausible-looking dozen for exactly this reason.
