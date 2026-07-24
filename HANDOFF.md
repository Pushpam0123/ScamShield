# Handoff — ScamShield

Written 2026-07-25, end of the first build session. Read `docs/architecture.md`, then
`docs/design.md`, then `docs/implementation.md` before touching code. This file only covers
what those documents cannot tell you: the state of *this* machine and *this* repository.

---

## 1. Where the work stands

**Phase 0 (Scaffold) is complete.** Phase 1 has not started.

Five commits on `main`, no remote configured:

```
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
| `./gradlew build` green | **Not verified.** `:app:assembleDebug`, `:app:assembleRelease`, `:core:model:test`, `checkPrivacyBoundary` and `:app:checkApkSize` have each been run green individually. The full `build` task has not completed a clean run — see §4. |
| App installs; sharing text opens ScamShield with the text populated | **Not verified.** No emulator or device has been run. The intent filters and `MainActivity.extractSharedText` are written but untested at runtime. |
| `:core:model` has zero Android dependencies, verified by a dependency-check task | **Done.** `./gradlew checkPrivacyBoundary`. Also verified negatively: adding okhttp to `:core:analysis` fails the build with the intended message. |
| CI green on push | **Not possible yet.** No git remote exists, and `.github/workflows/ci.yml` invokes `rulepack/build_rulepack.py`, which Phase 1.2 creates. |

Do not mark Phase 0 closed until the first two rows are actually green.

---

## 2. Toolchain on this machine

None of this was installed before the session; all of it was installed during it.

| Tool | Location | Note |
|---|---|---|
| JDK 17 | `/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home` | **Required.** System default is JDK 20; Homebrew also has JDK 26. Neither is what AGP wants. |
| Gradle 8.11.1 | via `./gradlew` | Homebrew's `gradle` is 9.6.1, which AGP 8.7 does not support. Always use the wrapper. |
| Android SDK | `/opt/homebrew/share/android-commandlinetools` | platform 35, build-tools 35.0.0, platform-tools. Licences accepted. |
| Python | `python3` 3.10.6 | For `ml/` and `rulepack/`. No venv created yet. |

`local.properties` points at the SDK and is gitignored, so it exists only on this machine.

**Every Gradle command must set `JAVA_HOME`:**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home && ./gradlew build
```

Without it the build picks up JDK 20 and behaviour is untested. Consider adding this to a
`.envrc` or to the session's shell profile early rather than repeating it.

No emulator image is installed. Phase 1's last acceptance criterion ("share a real phishing
SMS, get a correct explained verdict") and every instrumented test will need one:

```bash
sdkmanager --install "system-images;android-35;google_apis;arm64-v8a" "emulator"
```

---

## 3. What exists in the code

Structure follows `implementation.md` §0 exactly, with two deviations, both in `DECISIONS.md`:
specs live in `docs/`, and `:benchmark` is commented out of `settings.gradle.kts` until Phase 4.

- **`:core:model`** — every type from `design.md` §1, plus the `Analyzer` interface (D-002),
  `NormalizedMessage.brandClaims` (D-003), and the parsed rule-pack types `RulePack` /
  `BankEntry` / `PatternRule` / `ConfusableTable` / `DomainReputationIndex` /
  `PublicSuffixList` (D-004). Plain Kotlin JVM, zero dependencies. Five contract tests pin the
  13-wide category taxonomy to the classifier head.
- **`:app`** — manifest (INTERNET only; `SEND` and `PROCESS_TEXT` filters), Hilt application,
  `MainActivity`, Compose Material3 theme with the `design.md` §10.2 verdict colours and 18 sp
  body text, seeded `strings.xml`, backup rules excluding every domain. `ScamShieldApp` is a
  placeholder that displays the shared text; Phase 1.10 replaces it.
- **`:core:analysis`, `:analyzer:{url,sender,pattern}`** — build files only, no source. JVM
  libraries carrying the `scamshield.privacy-boundary` plugin.
- **`:core:explain`, `:core:data`, `:analyzer:classifier`** — build files and empty manifests
  only. Android libraries.
- **`buildSrc`** — the `scamshield.privacy-boundary` convention plugin.

---

## 4. Open problems, in the order you will hit them

1. **`./gradlew build` has never completed.** The last attempt failed on
   `ai.djl.huggingface:tokenizers:0.20.3` not existing. That is fixed in commit `a780621`
   (0.33.0 plus `ai.djl.android:tokenizer-native`), but the fixed build was interrupted before
   it finished. **Run it first**, and expect other unresolved-dependency failures in the two
   modules that have never been compiled: `:core:data` (Room + KSP + serialization) and
   `:analyzer:classifier` (ONNX Runtime).

2. **The tokenizer choice is unverified.** `architecture.md` §4 names HuggingFace `tokenizers`
   Android bindings and forbids reimplementing WordPiece. The DJL packaging above is the
   closest available artifact, but nothing has confirmed it loads on-device or byte-matches a
   training tokenizer. That verification belongs to Phase 4's parity gate, and a mismatch there
   is the single most common cause of silent accuracy collapse (`implementation.md`, failure
   modes table). Do not let it slide to "probably fine".

3. **CI cannot pass until Phase 1.2.** `.github/workflows/ci.yml` calls
   `rulepack/build_rulepack.py`. Either land Phase 1.1–1.2 quickly or the workflow is dead
   weight on the first push.

4. **`resourceConfigurations` in `app/build.gradle.kts` lists `en, hi, bn, ta, te, mr`.**
   `hi-Latn` is deliberately absent — `design.md` §10.4 makes it an in-app preference layered
   over the `en` resource set, not an Android locale. Do not "fix" this by adding it.

---

## 5. Next task: Phase 1.1 — rule-pack source data

`implementation.md` Phase 1 is the most important phase in the project: at its end there is a
shippable, useful app with no ML in it at all. The task list left in the session tracker breaks
it into 1.1 through 1.10; 1.1 is the next one.

Author `rulepack/src/`:

- `banks.json` — **≥ 120** Indian banks, wallets, telecoms, and government portals. Each entry
  needs `id`, `displayName`, `aliases`, `domains`, `dltHeaders`, matching `BankEntry` in
  `core/model/src/main/kotlin/com/scamshield/core/model/RulePack.kt`.
- `shorteners.json` — ≥ 40 shortener domains.
- `typosquat.json` — confusable character map plus the distance thresholds of `design.md` §3.2.
- `patterns.json` — ≥ 60 regexes across 7 languages, shape given in `design.md` §5.
- A Public Suffix List snapshot.
- `reputation.bin` — Bloom filter seeded from a public top-1M domain list.

Two things to hold on to while writing it:

- The **substring** typosquat rule (`design.md` §3.2) is the highest-yield of the three. Real
  phishing domains contain the brand far more often than they misspell it. `aliases` quality
  therefore matters more than domain-list completeness.
- `banks.json` aliases feed brand-claim extraction (§4.1), which gates the URL analyzer's
  force-verdict rule (§3.5). An alias that is too generic — `bank`, `pay` — will force SCAM
  verdicts on legitimate messages. Prefer precision.

Then Phase 1.2 writes `rulepack/build_rulepack.py` and the JSON schemas, and the root
`buildRulepack` Gradle task (already registered in `build.gradle.kts`) starts working.

---

## 6. Working agreements from this session

- Commit after each sub-task; the commit message says which phase and why, not just what.
- `DECISIONS.md` gets a row for every choice the specs left open. Rows are append-only and
  numbered; nine exist, D-001…D-009.
- Never weaken a test or threshold to make a phase pass (`implementation.md` working rule 4).
  Record the real number instead.
- Do not fabricate a metric. Every number in the README must come from a committed script.
