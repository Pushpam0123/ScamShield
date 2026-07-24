package com.scamshield.core.model

/**
 * The three-way outcome shown to the user.
 *
 * `design.md` §7 deliberately makes the SAFE band narrow and the SUSPICIOUS band wide:
 * a false "safe" costs the user money, a false "suspicious" costs them ten seconds.
 */
enum class Verdict {
    SAFE,
    SUSPICIOUS,
    SCAM,
}

enum class Confidence {
    LOW,
    MEDIUM,
    HIGH,
}

enum class Severity {
    INFO,
    WARN,
    CRITICAL,
}

/**
 * The 13-way scam taxonomy. Drives which advice template the Result screen shows.
 *
 * Ordinal order is the classifier's `category_logits` order (`design.md` §6.1) and must
 * not be rearranged without retraining — the model's head is positional.
 */
enum class ScamCategory {
    KYC_PHISHING,
    FAKE_JOB,
    LOTTERY_PRIZE,
    DIGITAL_ARREST,
    COURIER_CUSTOMS,
    LOAN_APP,
    UPI_COLLECT,
    INVESTMENT_TRADING,
    TECH_SUPPORT,
    SEXTORTION,
    ELECTRICITY_DISCONNECTION,
    OTHER_SCAM,
    NOT_SCAM,
}
