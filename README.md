# ScamShield 🛡️

**"Is this message trying to cheat me?"** — that's the only question ScamShield tries to answer.

India loses tens of thousands of crores to text-message scams every year, and the messages keep
getting better at sounding real. A fake "your SBI KYC will expire today" text copies the tone of
a genuine bank message almost perfectly. Someone who's used a smartphone for two months has no
real way to tell it apart from someone who's used one for ten years — they can't inspect a
domain, don't know real banks never ask for KYC over SMS, and have no idea `sbi-kyc-verify.xyz`
was registered four days ago by someone with no connection to any bank.

ScamShield is an attempt to close that gap: paste a suspicious SMS or WhatsApp forward in, and
get back a plain-language answer — not a score, not jargon, an actual explanation you could read
out loud to a parent or grandparent. *"This message says it's from SBI, but the link goes to a
website that's four days old. Real SBI texts never ask you to click a link to update your KYC."*
And then what to actually do about it.

## Why it's built the way it is

The one rule that shapes almost every engineering decision here: **the message never leaves the
phone.** People are going to share ScamShield exactly the texts they're most anxious about — the
ones with account numbers, OTPs, amounts, personal details in them. Sending that to a server
would defeat the entire point, so everything — the rule checks *and* the ML model — runs
on-device, fully offline. No `READ_SMS` permission, no network calls unless you explicitly
opt in to a domain-age lookup, no accounts, nothing.

That constraint is also what makes this an interesting project to build: a normal multilingual
transformer model is 200+ MB, and it has to be compressed down to something that fits comfortably
on a budget Android phone while still understanding scam messages written in Hindi, Marathi, and
— the hard one — romanized Hinglish, which almost no off-the-shelf NLP handles well.

Under the hood it's a hybrid: a handful of deterministic rule-based checks (domain reputation,
typosquat detection, homograph/lookalike-character detection, sender-ID validation, known scam
phrasing) working alongside a small on-device classifier, with a fusion layer that combines both
into one verdict plus the evidence behind it. The rules exist so the app can *explain itself* —
a raw ML score can't tell you a link goes to a domain registered four days ago, but a rule can.

## 🚧 Status: work in progress

This is under active development and not yet usable end to end. The rules engine (domain/URL
analysis, typosquat and homograph detection, message normalization, language detection) is
built and tested; the on-device ML classifier, the actual UI, and full end-to-end wiring are
still being built out. Nothing here is ready to install or rely on yet — treat this as a project
in motion, not a finished app. Check the commit history for the most honest picture of current
progress.

## What it promises, even half-built

- **No message text ever leaves the device.** There's no server-side classification path at all.
- **No SMS-reading permissions.** You share a message in, or paste it — that's the only way in.
- **Works even without the ML model.** The rule-based engine alone is meant to be a usable
  product on its own; the model is meant to be an enhancement, not a dependency.
- **Every verdict comes with a reason.** No unexplained "72% scam" numbers.

## Building

Requires JDK 17+ and the Android SDK (platform 35, build-tools 35).

```bash
./gradlew build
```

## License

Not decided yet.

---

*If you're reading this on GitHub and it looks unfinished — it is. Come back later, or better,
open an issue if something looks worth talking about.*
