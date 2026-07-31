package com.scamshield.analyzer.sender

/**
 * TRAI DLT sender-ID format: a two-letter operator prefix, a hyphen, then a 3-9 character
 * alphanumeric header body -- `VM-SBIINB` has prefix `VM` and body `SBIINB`. Kept separate
 * from [SenderAnalyzer] so the format itself is independently testable.
 */
object DltHeaderFormat {
    private val PATTERN = Regex("^[A-Z]{2}-[A-Z0-9]{3,9}$")

    fun isValid(senderHint: String): Boolean = PATTERN.matches(senderHint)

    /** The header body after the operator prefix, e.g. `SBIINB` from `VM-SBIINB`. Assumes [isValid]. */
    fun body(senderHint: String): String = senderHint.substringAfter('-')
}
