# Handoff — ScamShield

Last updated 2026-07-29, end of the third session. Read `docs/architecture.md`, then
`docs/design.md`, then `docs/implementation.md` before touching code. This file only covers
what those documents cannot: the state of *this* machine and *this* repository.

---

## 1. Where the work stands

**Phase 0 (Scaffold) is complete and verified**, except the emulator share-sheet check (§2).
**Phase 1.1 and 1.2 (rule-pack data + compiler) are complete.**
**Phase 1.3 (normalization, URL extraction, language detection, brand claims) is complete.**
**Phase 1.4 (`:analyzer:url`) is complete.**

Not started: 1.5 (`:analyzer:sender`), 1.6 (`:analyzer:pattern`), 1.7 (orchestrator + fusion),
1.8 (`:core:explain`), 1.9 (fixture corpus), 1.10 (UI + DI wiring + rule-pack loading).

18 commits on `main`, no remote configured. Working tree clean. Full `./gradlew build` passes
(~1.5 min), `checkPrivacyBoundary` passes on every restricted module.

Commit style changed mid-session: **from `47a9244` onward, commits have short human-style
messages and no `Co-Authored-By` trailer.** Earlier commits still have the longer, attributed
style. Keep using the short style going forward unless told otherwise.

---

## 2. Toolchain on this machine

Unchanged from the last handoff. Every Gradle command needs `JAVA_HOME` set explicitly:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home && ./gradlew build
```

JDK 17 at that path, Gradle 8.11.1 via `./gradlew` (never the bare `gradle` on PATH — it's
9.6.1 and incompatible with AGP 8.7), Android SDK at
`/opt/homebrew/share/android-commandlinetools` (platform 35, build-tools 35.0.0). The
`android-35` emulator system image and the `emulator` binary are both installed, but **no AVD
has been created and the emulator has never been launched** — Phase 0's "share text from
another app" acceptance row is still open, waiting on this one step:

```bash
avdmanager create avd -n scamshield-35 -k "system-images;android-35;google_apis;arm64-v8a" --device pixel_6
```

`jsonschema` is installed for the user running the shell (`python3 -m pip install --user
jsonschema`), not in a venv. `rulepack/build_rulepack.py` also needs `curl` on PATH for the
Tranco fetch (works; Python's own `urllib` fails with a cert-store error on this machine, so
the script shells out to `curl` instead — see the script's own comment).

---

## 3. What exists in the code

Per-module rundown, `:core:model` through `:analyzer:url`:

- **`:core:model`** — every design.md §1 type, plus `Analyzer` (D-002), `brandClaims` (D-003),
  rule-pack types (D-004), `PatternRule.suppressIf` (D-010), `PublicSuffixList.isRecognizedTld`
  (D-013), `RulePack.shortenerBrandOperated` (D-014). Zero dependencies, contract tests only.
- **`:core:analysis`** — `ingest/TextNormalizer`, `ingest/MessageNormalizer`,
  `url/PublicSuffixListParser` (real publicsuffix.org algorithm, not last-two-labels),
  `url/UrlExtractor`, `language/LanguageDetector` + `MarathiMarkers` + `RomanizedHindiLexicon`,
  `brand/BrandClaimExtractor`. 77 tests.
- **`:analyzer:url`** — `UrlAnalyzer` (the `Analyzer` implementation) plus its supporting
  pieces: `DamerauLevenshtein`, `TyposquatDetector`, `ConfusableFolder`, `HomographDetector`,
  `BloomDomainReputationIndex` (reads `reputation.bin`), `OnlineDomainLookup` (interface stub,
  no implementation — RDAP is Phase 5). 62 tests.
- **`:analyzer:{sender,pattern}`, `:core:{explain,data}`, `:analyzer:classifier`** — build
  files only, no source yet.
- **`:app`** — unchanged since the last handoff; still the Phase 0 placeholder UI.
- **`rulepack/`** — `src/` (banks.json 150 brands, shorteners.json 63 shorteners,
  typosquat.json, patterns.json 71 patterns, PSL snapshot), `schema/` (4 JSON Schemas),
  `build_rulepack.py` (validates, cross-checks, emits to app assets, builds `reputation.bin`).

---

## 4. Bugs this session actually found and fixed — read before touching related code

Every one of these was caught by writing a test that turned out to fail, not by inspection.
They're worth knowing about because the *pattern*, not just the specific fix, recurs:

1. **`BloomDomainReputationIndex`'s original hash-index math used `Long`.** The Python builder
   (`build_rulepack.py`) computes `h1 + i * h2` with Python's arbitrary-precision integers;
   `h1`/`h2` are near the edges of the 64-bit signed range, and `i * h2` overflows `Long` for
   essentially every real domain once `i >= 1`. Fixed with `BigInteger` in
   `analyzer/url/.../BloomDomainReputationIndex.kt`. Verified two ways: generated a small
   `reputation.bin` fixture with the actual Python functions and checked the Kotlin reader
   against independently Python-computed answers (`BloomDomainReputationIndexTest.kt`), then
   confirmed the test fails if the `Long` version is reintroduced. **If you touch this file,
   re-run that test — it's the one that would catch a regression.**
2. **Confusable-folded exact match was being exempted, not flagged.** `TyposquatDetector`'s
   "candidate label == known brand label ⇒ legitimate, not a hit" rule is correct for a *raw*
   candidate (it really is the brand's own domain) but wrong when the match only appears
   *after* folding — that means the text the user actually sees is provably not the real
   domain. `UrlAnalyzer` now special-cases an exact fold match instead of routing it through
   the ordinary detector. See D-016.
3. **Devanagari word-splitting on `[^\p{L}]+` breaks words at vowel signs (matras).**
   `\p{L}` (Letter) does not include Devanagari combining marks (Mn/Mc), so "तुमचे" was
   splitting into "त" + "मच". Fixed with `[^\p{L}\p{M}]+` in `MarathiMarkers.kt`.
4. **The romanized-Hindi marker lexicon originally included words that collide with ordinary
   English** — "the", "to", "hi", "do", "ha", "ya", "main", plus the bare loanwords "bank",
   "account", "number", "mobile", "phone", "fees", "lottery". A genuine English banking SMS
   ("Your bank account number...") would have tripped the ≥2-hits threshold into HI_LATN.
   All removed; see the doc comment on `RomanizedHindiLexicon.kt` for the full list and why.
5. **`Character.UnicodeScript` classifies dotless-i (U+0131) as LATIN, not a cross-script
   character.** It looks like a classic homograph trick but cannot exercise
   `HomographDetector.hasMixedScript` — that check needs a genuinely different Unicode script
   (Cyrillic/Greek/Armenian). Dotless-i is exactly what confusable *folding* is for instead.

None of these were caught by "does it compile" or "does the happy path look right" — all five
needed either an independent expected-value source (the Python cross-check) or a constructed
adversarial input (the collision words, the matra-splitting, the exact-fold-match case).

---

## 5. Open problems, carried over or new

1. **Emulator AVD still not created** (§2) — Phase 0's last acceptance row.
2. **Tokenizer choice unverified on-device** (D-009) — Phase 4's parity gate.
3. **`OnlineDomainLookup` has no implementation anywhere** — expected; it's a Phase 5 concern,
   the interface just exists so `UrlAnalyzer` doesn't need a later rewrite.
4. **Nothing has wired the rule pack's JSON into `RulePack`/`ConfusableTable`/etc. instances
   yet.** Every analyzer and detector so far takes its config as constructor parameters and is
   tested with hand-built fixtures. The actual `rulepack/src/*.json` → `RulePack` parser is
   Phase 1.10's job (`:core:data`), and until it exists nothing in `:analyzer:url` has been
   exercised against the *real* `banks.json` (150 brands) or `typosquat.json` at once — only
   against small hand-built subsets in tests, plus the separate real-PSL-file and
   real-Tranco-fixture tests, which each check one piece in isolation.
5. **`isIpLiteral` is duplicated** between `:core:analysis`'s `PublicSuffixListParser` and
   `:analyzer:url`'s `UrlAnalyzer` (same ~5-line regex check, deliberately not shared — see
   the comment in `UrlAnalyzer.kt` on why pulling in a `:core:analysis` dependency felt like
   the wrong direction for one small function). If it needs a third copy, it's time to find it
   a proper home instead.

---

## 6. Next task: Phase 1.5 — brand-claim consumption + `:analyzer:sender`

`BrandClaimExtractor` (Phase 1.3) already populates `NormalizedMessage.brandClaims`. Phase 1.5
is the sender analyzer itself, design.md §4.2: DLT header format validation
(`^[A-Z]{2}-[A-Z0-9]{3,9}$`), and the three evidence rules —

- brand claimed + `senderHint` is a bare 10-digit mobile number → `UNREGISTERED_NUMERIC_SENDER`, CRITICAL
- brand claimed + `senderHint` matches DLT format but maps to a *different* brand →
  `DLT_HEADER_MISMATCH`, CRITICAL
- brand claimed + `senderHint` is null → `BRAND_CLAIM_WITHOUT_DLT_HEADER`, **INFO, not higher**
  (design.md is explicit: most input arrives with no sender hint at all, and treating absence
  as guilt would poison the whole verdict distribution)

**The banks.json DLT-header-to-brand mapping is many-to-many** (documented in `banks.json`
itself and in D-… — `GOOGLE` belongs to both `google` and `gpay`; `AMAZON` to both `amazon` and
`amazonpay`). Build `header -> Set<brandId>`, and only raise `DLT_HEADER_MISMATCH` when that
set is disjoint from the brands the message claims — a one-to-one map will false-positive on
genuine Google Pay or Amazon Pay messages. `forceVerdict = SCAM` is permitted only on
`UNREGISTERED_NUMERIC_SENDER` and `DLT_HEADER_MISMATCH`, never on the INFO case.

Follow the same pattern `:analyzer:url` established: pure logic in small focused files, rule
pack config passed in via constructor (hand-built fixtures in tests, not the real JSON — that
still waits on Phase 1.10), a real cross-check wherever there's an independently-computable
expected answer.

---

## 7. Working agreements

- Commit after each sub-task. **From `47a9244` onward: short, human-style messages, no
  `Co-Authored-By` trailer.**
- `DECISIONS.md` gets a numbered row for every choice the specs left open. Sixteen exist,
  D-001…D-016 (not in strict numeric file order — D-009 through D-012 were appended out of
  sequence in an earlier session; don't "fix" the ordering, the table is append-only).
- Never weaken a test or threshold to make a phase pass. Record the real number instead.
- When a test fails, work out *why* before changing anything — twice this session the
  analyzer code was right and the test's own premise was wrong (see §4 items 2 and the
  homograph/dotless-i mixup in item 5). Reflexively "fixing" the code first would have made
  both worse.
