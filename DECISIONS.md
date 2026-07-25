# DECISIONS

Append-only record of choices that `docs/architecture.md`, `docs/design.md`, and
`docs/implementation.md` left underdetermined. One sentence of rationale each
(`implementation.md` working rule 3).

| # | Date | Decision | Rationale |
|---|---|---|---|
| D-001 | 2026-07-25 | Spec documents moved from the repository root into `docs/`. | `implementation.md` §0 places them at `docs/{architecture,design,description}.md`; `implementation.md` itself is kept alongside them. |
| D-002 | 2026-07-25 | The `Analyzer` interface lives in `:core:model`, not `:core:analysis`. | Lets an analyzer module compile against the domain types alone, keeping the dependency graph a strict fan-in and letting the eval harness instantiate analyzers in isolation. |
| D-003 | 2026-07-25 | `NormalizedMessage` carries a `brandClaims: List<BrandClaim>` field. | `design.md` §1 does not list it but §4.1 requires both the URL and sender analyzers to receive brand-claim results "as part of `NormalizedMessage`"; §4.1 is the more specific instruction. |
| D-004 | 2026-07-25 | Parsed rule-pack types (`RulePack`, `BankEntry`, `PatternRule`, …) live in `:core:model`; parsing and validation live in `:core:data`. | Analyzers need the pack but must not depend on `:core:data`, which is the only module allowed a network or storage dependency (`architecture.md` §10.1). |
| D-005 | 2026-07-25 | Gradle pinned to 8.11.1 by wrapper; JVM target 17 set per module rather than via a Gradle toolchain block. | AGP 8.7 does not support Gradle 9.x, and a toolchain block would require every contributor's machine to auto-provision a JDK; an explicit `jvmTarget` works on any JDK 17+. |
| D-006 | 2026-07-25 | Material You dynamic colour is not enabled. | Verdict colours carry safety meaning and `design.md` §10.3 requires a *verified* 4.5:1 contrast ratio for every pair; a wallpaper-derived scheme cannot be verified ahead of time. |
| D-007 | 2026-07-25 | `:benchmark` is commented out of `settings.gradle.kts` until Phase 4. | An empty `com.android.test` module has no `benchmark` build type to target and only contributes an unassemblable variant; `implementation.md` introduces it in Phase 4. |
| D-008 | 2026-07-25 | A `PROCESS_TEXT` intent filter is added alongside the required `SEND` filter. | It puts ScamShield in the text-selection toolbar itself, one tap shorter than the share sheet, and uses no additional permission — it does not widen the ingest surface `architecture.md` §2 restricts. |
| D-010 | 2026-07-25 | `PatternRule` gains a `suppressIf: Regex?` field, not specified in `design.md` §5. | The most common genuine bank SMS in India is an OTP delivery saying "do not share this OTP with anyone", on which a plain `share.*otp` pattern fires; encoding the exception as its own field rather than a negative lookahead keeps it reviewable, and §12 allows zero false positives on the genuine-bank fixtures. |
| D-009 | 2026-07-25 | Tokenizer dependency is `ai.djl.huggingface:tokenizers:0.33.0` plus `ai.djl.android:tokenizer-native:0.33.0`, not the `0.20.3` implied by `architecture.md` §4. | No artifact exists at 0.20.3; DJL 0.33.0 wraps HuggingFace `tokenizers` ≥ 0.20 and is the only packaging shipping an Android native library. **Unverified on-device** — Phase 4's parity gate must confirm it byte-matches the training tokenizer. |
