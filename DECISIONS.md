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
