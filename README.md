# ScamShield

An offline-first Android app that tells a person whether a message they received is a scam —
and, more importantly, *why*.

Paste or share a suspicious SMS/WhatsApp message. ScamShield analyses it entirely on the device
and returns one of three verdicts — **No danger signs found**, **Be careful with this message**,
or **This looks like a scam** — together with plain-language evidence a non-technical reader can
check for themselves.

> **Status: under construction.** This README is a stub. Per `docs/implementation.md` Phase 7,
> every metric published here must come from a committed, re-runnable script. Nothing is claimed
> until it is measured.

## Design promises

- **No message text leaves the device.** No server classification path exists at all.
- **No SMS permissions.** Entry is share-sheet, clipboard, and manual paste only.
- **Works with the model absent.** The rules engine is a complete product on its own.
- **Every verdict carries evidence.** No unexplained scores.

## Documentation

| Document | Contents |
|---|---|
| [docs/architecture.md](docs/architecture.md) | System structure, constraints, ADRs |
| [docs/design.md](docs/design.md) | Domain types, algorithms, ML pipeline, fusion policy, UI |
| [docs/implementation.md](docs/implementation.md) | Build order, phases, acceptance criteria |
| [docs/description.md](docs/description.md) | Problem statement and scope |
| [DECISIONS.md](DECISIONS.md) | Underdetermined choices and their rationale |

## Building

Requires JDK 17+ and the Android SDK (platform 35, build-tools 35).

```bash
./gradlew build
```

## Licence

Not yet chosen.
