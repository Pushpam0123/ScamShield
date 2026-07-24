# ScamShield — Detailed Design

> **Audience: the implementing agent.** Read `architecture.md` first. This document specifies
> domain types, algorithms, the ML pipeline, the fusion policy, and the UI. Where a numeric
> constant appears, use exactly that value unless the eval harness justifies changing it — and if
> you change one, record the before/after metric in `ml/EXPERIMENTS.md`.

---

## 1. Domain model (`:core:model`)

Pure Kotlin. No Android imports. No I/O. All types immutable.

```kotlin
@JvmInline value class MessageId(val value: String)   // UUIDv4

data class RawMessage(
    val id: MessageId,
    val text: String,
    val senderHint: String?,      // sender ID if the sharing app supplied one
    val source: MessageSource,    // SHARE_SHEET | CLIPBOARD | MANUAL
    val receivedAt: Instant,
)

data class NormalizedMessage(
    val id: MessageId,
    val original: String,         // preserved verbatim — needed for homograph detection
    val normalized: String,       // NFKC, collapsed whitespace, lowercased
    val urls: List<ExtractedUrl>,
    val senderHint: String?,
    val detectedLanguage: Language,
)

data class ExtractedUrl(
    val raw: String,
    val scheme: String?,
    val host: String,             // as written, pre-punycode
    val registrableDomain: String?, // eTLD+1 via Public Suffix List
    val path: String?,
    val isPunycode: Boolean,
    val displayText: String?,     // anchor text, when the source was rich text
    val spanStart: Int,           // offsets into `original`, for UI highlighting
    val spanEnd: Int,
)

enum class Language { EN, HI, HI_LATN, BN, TA, TE, MR, UNKNOWN }

enum class Verdict { SAFE, SUSPICIOUS, SCAM }

enum class ScamCategory {
    KYC_PHISHING, FAKE_JOB, LOTTERY_PRIZE, DIGITAL_ARREST, COURIER_CUSTOMS,
    LOAN_APP, UPI_COLLECT, INVESTMENT_TRADING, TECH_SUPPORT, SEXTORTION,
    ELECTRICITY_DISCONNECTION, OTHER_SCAM, NOT_SCAM
}

enum class EvidenceType {
    // URL analyzer
    DOMAIN_VERY_NEW, TYPOSQUAT_OF_KNOWN_BRAND, HOMOGRAPH_CHARACTERS,
    URL_SHORTENER, LINK_TEXT_MISMATCH, IP_ADDRESS_HOST, SUSPICIOUS_TLD,
    NON_HTTPS_CREDENTIAL_PAGE,
    // Sender analyzer
    BRAND_CLAIM_WITHOUT_DLT_HEADER, UNREGISTERED_NUMERIC_SENDER, DLT_HEADER_MISMATCH,
    // Pattern analyzer
    OTP_SOLICITATION, UPI_COLLECT_FRAMING, URGENCY_DEADLINE, THREAT_OF_LOSS,
    UNREALISTIC_REWARD, PREMIUM_RATE_NUMBER, CRYPTO_WALLET_ADDRESS,
    ADVANCE_FEE_REQUEST,
    // Classifier
    MODEL_HIGH_SCAM_SCORE, MODEL_LOW_SCAM_SCORE,
}

data class Evidence(
    val type: EvidenceType,
    val severity: Severity,               // INFO | WARN | CRITICAL
    val slots: Map<String, String>,       // template fill values, e.g. {"domain": "sbi-kyc.xyz"}
    val highlightSpan: IntRange?,         // span in `original` to highlight in the UI
)

sealed interface Signal {
    val analyzerId: AnalyzerId
    data class Scored(
        override val analyzerId: AnalyzerId,
        val scamWeight: Float,            // 0.0..1.0 contribution
        val categoryHints: Map<ScamCategory, Float>,
        val evidence: List<Evidence>,
        val forceVerdict: Verdict? = null, // only URL + SENDER may set this
    ) : Signal
    data class Unavailable(
        override val analyzerId: AnalyzerId,
        val reason: String,
    ) : Signal
}

data class AnalysisResult(
    val messageId: MessageId,
    val verdict: Verdict,
    val confidence: Confidence,           // LOW | MEDIUM | HIGH
    val category: ScamCategory,
    val categoryConfidence: Confidence,
    val evidence: List<Evidence>,         // sorted by severity desc, then weight desc
    val analyzersRun: Set<AnalyzerId>,
    val latencyMs: Long,
    val modelVersion: String?,
    val rulepackVersion: String,
)
```

---

## 2. Ingest and normalization

### 2.1 Normalization — order matters

Apply in exactly this order. **Never discard the original string**; homograph and link-mismatch
detection operate on it.

1. Unicode **NFKC** normalization.
2. Strip zero-width characters: `U+200B U+200C U+200D U+2060 U+FEFF`. Scammers insert these to
   break naive keyword matching (`O​T​P`).
3. Normalize confusable whitespace (`U+00A0`, `U+2007`, `U+202F`) → `U+0020`; collapse runs.
4. Lowercase using `Locale.ROOT` — **not** the device locale (Turkish locale breaks `I` → `ı`).
5. Leave digits, punctuation, and emoji intact. Do not strip them; `₹`, `!!!`, and digit density
   are informative features.

### 2.2 URL extraction

Regex-based scan for `https?://`, `www.`, and bare-domain forms (`sbi-verify.xyz/kyc`) — scam SMS
frequently omits the scheme. For each hit:

- Compute the **registrable domain via the Public Suffix List** (bundle a PSL snapshot; do not
  approximate with "last two labels" — `.co.in` and `.gov.in` break that assumption immediately).
- Detect punycode (`xn--` prefix) and decode for display, retaining both forms.
- Record `spanStart`/`spanEnd` for UI highlighting.

### 2.3 Language detection

Script-based, not statistical — it is cheap and sufficient:

- ≥ 20% Devanagari codepoints → `HI` (or `MR`; disambiguate on a small marker-word list)
- ≥ 20% Bengali / Tamil / Telugu blocks → `BN` / `TA` / `TE`
- Otherwise Latin script: check against a ~300-token romanized-Hindi marker lexicon
  (`aapka`, `khata`, `turant`, `kripya`, `paisa`, `dhanyavad`, …). ≥ 2 hits → `HI_LATN`, else `EN`.

Language selects the explanation string set. It is **not** a classifier feature — the model handles
mixed script natively and hard-routing by language loses code-mixed signal.

---

## 3. URL analyzer

Highest-precision analyzer. May set `forceVerdict`.

### 3.1 Domain age

- **Offline path (default).** `reputation.bin` is a Bloom filter of domains observed as
  established (≥ 180 days old) at pack build time, plus an explicit allowlist of Indian bank,
  government, and major-service domains. A registrable domain absent from the filter is *unknown*,
  not *new* — emit `INFO`, never `CRITICAL`, on the offline path.
- **Online path (opt-in only).** RDAP lookup (`https://rdap.org/domain/{domain}`), 2 s timeout,
  result cached 7 days in Room. Age < 30 days → `DOMAIN_VERY_NEW`, severity `CRITICAL`.
  Transmit only the registrable domain (architecture §10.4).

### 3.2 Typosquat detection

Against `banks.json` (registrable domains of ~120 Indian banks, wallets, telecoms, government
portals):

```
for each known brand domain B and candidate domain D (compare label only, TLD stripped):
    if D == B: no hit
    if damerau_levenshtein(D, B) <= threshold(len(B)):   # 1 for len<=6, 2 for len>6
        hit TYPOSQUAT_OF_KNOWN_BRAND, CRITICAL, slots{claimed: B, actual: D}
    if B is a substring of D and D != B:                 # sbi-kyc-verify.xyz
        hit TYPOSQUAT_OF_KNOWN_BRAND, CRITICAL, slots{claimed: B, actual: D}
    if squash(D) == squash(B):                           # squash: strip -._ and digits
        hit TYPOSQUAT_OF_KNOWN_BRAND, CRITICAL
```

The substring rule is the highest-yield of the three in practice — real phishing domains far more
often *contain* the brand than misspell it.

### 3.3 Homograph detection

Operate on the **original** host string:

- Mixed-script detection: if a single domain label mixes Latin with Cyrillic/Greek/Armenian
  codepoints → `HOMOGRAPH_CHARACTERS`, `CRITICAL`.
- Confusable folding: map each character through `typosquat.json`'s confusable table
  (`а`→`a` Cyrillic, `ı`→`i`, `０`→`0`, `rn`→`m` at render level), then re-run §3.2 on the folded
  form. A fold that produces a known brand is a strong hit.

### 3.4 Other URL checks

| Check | Evidence | Severity |
|---|---|---|
| Host in `shorteners.json` | `URL_SHORTENER` | WARN |
| Host is a bare IPv4/IPv6 | `IP_ADDRESS_HOST` | CRITICAL |
| TLD in high-abuse list (`.xyz .top .tk .cn .buzz .rest .click .link` …) | `SUSPICIOUS_TLD` | WARN |
| Anchor text names a domain ≠ actual host | `LINK_TEXT_MISMATCH` | CRITICAL |
| `http://` and path matches `/(login|verify|kyc|update|otp|pay)/i` | `NON_HTTPS_CREDENTIAL_PAGE` | WARN |

### 3.5 Force-verdict rule

The URL analyzer sets `forceVerdict = SCAM` **only** when it produces ≥ 1 `CRITICAL` evidence item
**and** the message contains a brand claim (§4.1). A new domain alone is not sufficient — it would
false-positive on legitimate small businesses.

---

## 4. Sender analyzer

### 4.1 Brand claim extraction

Scan the normalized text for brand aliases from `banks.json` (each entry lists display names and
common abbreviations: `sbi`, `state bank`, `hdfc`, `icici`, `paytm`, `income tax`, `aadhaar`, …).
A hit means the message *claims* to be from that organization. This is a shared primitive — the URL
analyzer consumes its output through the fusion layer, not by calling this module (architecture §5).

> **Implementation note:** brand-claim extraction lives in `:core:analysis` as a shared pure
> function, and both analyzers receive its result as part of `NormalizedMessage`. This is the one
> deliberate exception to "analyzers share nothing," and it exists to preserve the module
> dependency rule.

### 4.2 DLT header validation

Indian commercial SMS headers follow `^[A-Z]{2}-[A-Z0-9]{3,9}$` (e.g. `VM-SBIINB`, `AD-HDFCBK`).

| Condition | Evidence | Severity |
|---|---|---|
| Brand claimed, `senderHint` is a 10-digit mobile number | `UNREGISTERED_NUMERIC_SENDER` | CRITICAL |
| Brand claimed, `senderHint` matches DLT format but maps to a different brand in `banks.json` | `DLT_HEADER_MISMATCH` | CRITICAL |
| Brand claimed, `senderHint` is null (WhatsApp forwards, pasted text) | `BRAND_CLAIM_WITHOUT_DLT_HEADER` | INFO |

The null case must stay `INFO`. Most input arrives without a sender hint, and treating absence as
guilt would poison the whole verdict distribution.

`forceVerdict = SCAM` is permitted only on `UNREGISTERED_NUMERIC_SENDER` and `DLT_HEADER_MISMATCH`.

---

## 5. Pattern analyzer

Regexes loaded from `patterns.json`. Never `forceVerdict`; contributes weight and category hints.

```json
{
  "id": "otp_solicit_en",
  "lang": ["EN", "HI_LATN"],
  "pattern": "(share|send|tell|provide|batao|bhejo)\\W+(me\\W+)?(the\\W+)?(otp|code|pin)",
  "evidence": "OTP_SOLICITATION",
  "severity": "CRITICAL",
  "weight": 0.35,
  "category_hints": { "KYC_PHISHING": 0.4, "UPI_COLLECT": 0.3 }
}
```

Minimum viable set (~60 patterns across 7 languages), by evidence type:

- `OTP_SOLICITATION` — any request to share an OTP/PIN/CVV. Highest-precision text pattern that exists.
- `UPI_COLLECT_FRAMING` — "approve to receive", "accept request to get refund". The mechanic behind a large share of UPI fraud: the victim authorizes an outbound payment believing it is inbound.
- `URGENCY_DEADLINE` — "within 24 hours", "today itself", "turant", "immediately or".
- `THREAT_OF_LOSS` — account block/freeze/suspend, electricity disconnection, legal action, arrest warrant.
- `UNREALISTIC_REWARD` — lottery, lucky draw, ₹ amount above a threshold with no prior relationship.
- `ADVANCE_FEE_REQUEST` — registration/processing/security fee to receive a job, prize, or loan.
- `PREMIUM_RATE_NUMBER`, `CRYPTO_WALLET_ADDRESS` — regex on number/address formats.

**Weight cap:** total pattern-analyzer weight is capped at `0.5` regardless of how many fire.
Uncapped, a verbose scam message stacks a dozen weak patterns and drowns out the model.

---

## 6. Classifier

### 6.1 Interface

```
input:  input_ids [1, 128] int64, attention_mask [1, 128] int64
output: binary_logits [1, 2], category_logits [1, 13]
```

Max sequence length **128** tokens. Truncate from the *end* — scam messages front-load the hook,
and the link is usually early. Log a `TRUNCATED` flag; do not surface it to the user.

### 6.2 Score mapping

`p_scam = softmax(binary_logits)[1]`, then map through a **temperature-calibrated** transform. Fit
temperature `T` on the validation set by minimizing NLL; store `T` in `meta.json` alongside the
model. Uncalibrated distilled+quantized models are systematically overconfident, and the fusion
policy's thresholds assume calibrated probabilities.

```
scamWeight = clamp(p_scam_calibrated, 0, 1)
evidence   = MODEL_HIGH_SCAM_SCORE  if p >= 0.75
             MODEL_LOW_SCAM_SCORE   if p <= 0.25
             (none)                 otherwise
```

Category is emitted only when `max(softmax(category_logits)) >= 0.45`; below that, category is
`OTHER_SCAM` with `Confidence.LOW`.

### 6.3 Failure handling

Model file missing, corrupt, ONNX session creation failure, or inference exception → return
`Signal.Unavailable`. The app must remain fully functional (C6). Show a one-line notice in Settings,
never a blocking dialog.

---

## 7. Fusion policy

Deterministic. Implemented as a pure function so it is unit-testable against a fixture table.

```
fun fuse(signals: List<Signal>): AnalysisResult
```

**Step 1 — Force check.** If any signal sets `forceVerdict = SCAM` (only URL §3.5 and sender §4.2
may), the verdict is `SCAM` with `Confidence.HIGH`. Skip to step 4.

**Step 2 — Weighted score.**

```
score = 0.55 * classifierWeight        (0 and renormalize others if unavailable)
      + 0.25 * urlWeight
      + 0.12 * senderWeight
      + 0.08 * patternWeight           (already capped at 0.5)
```

If the classifier is unavailable, redistribute its 0.55 proportionally across the rule analyzers and
**cap the achievable verdict at `SUSPICIOUS`** unless step 1 fired. Rules alone must not produce a
confident `SCAM` on wording evidence.

**Step 3 — Thresholds.**

| score | verdict | confidence |
|---|---|---|
| ≥ 0.70 | SCAM | HIGH if ≥ 0.85 else MEDIUM |
| 0.35 – 0.70 | SUSPICIOUS | MEDIUM if ≥ 0.50 else LOW |
| < 0.35 | SAFE | HIGH if ≤ 0.15 else LOW |

**Step 4 — Category.** Sum `categoryHints` across all signals, add the classifier's category
distribution weighted 0.6, take the argmax. If the verdict is `SAFE`, category is `NOT_SCAM`.

**Step 5 — Evidence ordering.** Sort `CRITICAL` → `WARN` → `INFO`, then by contributing weight
descending. The UI shows the top 3 with a "show all" expander.

**Asymmetry is intentional.** The `SAFE` band is narrow and the `SUSPICIOUS` band is wide because a
false "safe" causes real financial loss while a false "suspicious" costs the user ten seconds. Do
not "balance" these thresholds.

---

## 8. Explanation generation

Templates in `strings.xml`, one per `EvidenceType` per language, with named slots:

```xml
<string name="ev_typosquat">This link goes to <b>%1$s</b>, which is designed to look like the
real %2$s website. The real address is <b>%3$s</b>.</string>
<string name="ev_domain_very_new">This website was created only %1$d days ago. Real banks and
government services have used their websites for many years.</string>
```

Each verdict screen composes:

1. **Headline** — verdict + category (`"This looks like a fake KYC message"`).
2. **Evidence list** — top 3 rendered templates, each with the relevant message span highlighted.
3. **Action block** — from `ScamCategory` → action template. Always includes: do not click, do not
   share OTP, verify by calling the number on your card/bill, report at cybercrime.gov.in / 1930.

**Copy rules — enforce these in review:**
- Never say "safe" unqualified. Use "No danger signs found — but stay careful."
- Never blame the user. No "you should have known."
- Reading level: aim ≤ grade 6 in every language. Short sentences. No jargon: not "phishing", not
  "domain", not "malicious". Say "fake website", "web address".
- Every screen offers TTS playback of the full explanation.

---

## 9. ML pipeline detail (`ml/`)

### 9.1 Dataset schema (JSONL)

```json
{
  "id": "sms_004821",
  "text": "Dear Customer your SBI KYC expire today update http://sbi-kyc.xyz",
  "lang": "EN",
  "label": 1,
  "category": "KYC_PHISHING",
  "source": "cybercrime_advisory_2025",
  "split": "train",
  "is_hard_negative": false,
  "collected_at": "2026-03-14"
}
```

### 9.2 Sources and target composition

| Bucket | Target count | Sources |
|---|---|---|
| Scam — real | 4,000 | Public cybercrime advisories, RBI/TRAI awareness bulletins, r/IndianScams and similar public reports, published academic SMS-spam corpora filtered to fraud |
| Scam — augmented | 3,000 | Paraphrase, transliterate EN↔HI_LATN, entity swap (bank names, amounts, URLs) |
| **Genuine — hard negatives** | **4,000** | Real transactional bank SMS, OTP delivery messages, delivery notifications, genuine promotional offers, government service SMS |
| Genuine — easy | 1,000 | Personal messages, unrelated notifications |

**The hard-negative bucket is the project.** A genuine SBI transaction alert and a phishing SBI
alert share ~80% of their vocabulary. A model trained without hard negatives scores 0.97 on a naive
split and collapses in production. Target ≥ 30% of the corpus as genuine bank/service messages.

**Collection ethics:** use only publicly posted messages. Scrub all phone numbers, account numbers,
names, and amounts to placeholders (`<PHONE>`, `<ACCT>`, `<NAME>`, `<AMT>`) — in both training data
and any published dataset. Never collect from private inboxes without consent.

### 9.3 Splits

Split **by source cluster and by template family**, never randomly. Augmented variants must land in
the same split as their parent. Random splitting leaks near-duplicates across train/test and
inflates F1 by 5–10 points. Ratio 70/15/15.

### 9.4 Training stages

**Stage A — Teacher.** Fine-tune `google/muril-base-cased`, two heads, class-weighted cross-entropy
(scam class weight 1.5 to bias toward recall). lr 2e-5, batch 32, 4 epochs, early stop on val macro-F1.

**Stage B — Distillation.** Student: 4 layers, hidden 384, 4 heads, intermediate 1536, initialized
from the teacher's embedding matrix (projected) and every 3rd layer.

```
L = 0.5 * KL(student_logits/τ, teacher_logits/τ) * τ²   # τ = 3.0
  + 0.3 * CE(student_logits, hard_labels)
  + 0.2 * MSE(student_hidden_proj, teacher_hidden[3,6,9,12])
```

lr 5e-5, batch 64, 15 epochs.

**Stage C — Vocabulary pruning.** Tokenize the full corpus plus a general Indic sample; keep tokens
with frequency ≥ 5 plus all special tokens, targeting ~32K. Slice the embedding matrix, remap IDs,
regenerate `tokenizer.json`. Fine-tune 2 further epochs to recover. **This stage produces the single
largest size reduction** (embedding drops from ~151M to ~12M parameters).

**Stage D — Export and quantize.** ONNX opset 17, dynamic axes on batch and sequence. Dynamic INT8
quantization on MatMul/Attention; **keep embeddings and LayerNorm in FP32** — quantizing embeddings
costs 3–4 F1 points for ~2 MB.

**Stage E — Calibration.** Fit temperature on validation. Write `T` to `meta.json`.

### 9.5 Eval protocol — must be automated

Run on the held-out test set after every stage and record in `ml/EXPERIMENTS.md`:

- Macro F1, per-class P/R/F1
- **Per-language** F1 (report all 7 separately; the aggregate hides Tamil/Telugu weakness)
- **FPR on the genuine-bank-SMS subset specifically** — the headline safety metric, target ≤ 3%
- Scam-class recall, target ≥ 0.93
- Confusion matrix over the 13 categories
- Size on disk, p50/p95 CPU latency measured on-device

**Parity gate:** run 200 fixed samples through both the PyTorch student and the exported ONNX INT8
model. Max absolute probability delta must be < 0.02. A parity failure means the export is broken,
and no downstream number is trustworthy until it passes.

---

## 10. UI specification

### 10.1 Screens

| Screen | Purpose |
|---|---|
| **Check** | Home. Big paste box, "Paste from clipboard" button, "Check" CTA. Empty state explains sharing from WhatsApp/SMS in one sentence with an illustration. |
| **Result** | Verdict banner (color + icon + text — never color alone), message with evidence spans highlighted, evidence list, action block, TTS button, "Was this helpful?" (local only). |
| **History** | Past checks, newest first. Verdict chip + first line. Swipe to delete. |
| **Learn** | 8 short cards, one per common scam category, in the user's language. Static content, no network. |
| **Settings** | Language, text size, TTS voice, online domain lookup toggle (off by default), analytics toggle (off by default), delete all history, about/version. |

### 10.2 Verdict presentation

| Verdict | Color | Icon | Headline pattern |
|---|---|---|---|
| SCAM | `#B3261E` | filled shield-alert | "This looks like a scam" |
| SUSPICIOUS | `#7A5900` | outlined warning | "Be careful with this message" |
| SAFE | `#1B6B3A` | outlined check | "No danger signs found" |

Never communicate verdict by color alone (colorblind users, and it fails the a11y suite).

### 10.3 Accessibility — treated as requirements, not polish

- Default body text **18 sp**, scaling to 200% without clipping or truncation.
- All touch targets ≥ 48 dp.
- Every element has a TalkBack `contentDescription`; the Result screen reads as a coherent
  paragraph, not a list of fragments.
- Full-screen TTS playback of the verdict and explanation, one tap from Result.
- Contrast ≥ 4.5:1 for all text (verify the `#7A5900` amber on your actual surface color).
- Works entirely one-handed; no gestures beyond tap and vertical scroll.

### 10.4 Localization

`en`, `hi`, `bn`, `ta`, `te`, `mr`, plus `hi-Latn` as a user-selectable option (not auto-detected
for UI — Android does not resolve it reliably as a locale, so it is an in-app preference layered
over the `en` resource set).

Have native speakers review the Hindi and Hinglish strings. Machine-translated safety copy reads as
untrustworthy to exactly the audience that needs to trust it.

---

## 11. Persistence

```kotlin
@Entity(tableName = "check_history")
data class CheckHistoryEntity(
    @PrimaryKey val id: String,
    val messagePreview: String,   // first 120 chars, for the list row
    val messageFull: String,      // app-private storage only, never leaves device
    val verdict: String,
    val confidence: String,
    val category: String,
    val evidenceJson: String,
    val modelVersion: String?,
    val rulepackVersion: String,
    val checkedAt: Long,
)

@Entity(tableName = "domain_reputation_cache")
data class DomainCacheEntity(
    @PrimaryKey val domain: String,
    val ageDays: Int?,
    val fetchedAt: Long,          // TTL 7 days
)
```

History retention: 90 days, pruned on app start. Settings offers immediate full deletion
(`DELETE FROM` + `VACUUM`, then confirm to the user that it is gone).

---

## 12. Test fixture corpus

Maintain `app/src/test/resources/fixtures/verdicts.json` — a table of `(message, senderHint,
expectedVerdict, expectedEvidenceTypes)` covering at minimum:

- One canonical example per `ScamCategory` (12)
- Genuine bank SMS that must return SAFE (15) — **the highest-value fixtures in the suite**
- Homograph, punycode, typosquat, and shortener URL cases (10)
- Every supported language (7)
- Degenerate inputs: empty, whitespace only, 10,000 chars, emoji only, no-URL, URL only (6)

The fusion policy is tested against this table with the classifier stubbed to a fixed score, so rule
behavior is verified independently of model quality.

---

*Next: `implementation.md` for build order, milestones, and acceptance criteria.*
