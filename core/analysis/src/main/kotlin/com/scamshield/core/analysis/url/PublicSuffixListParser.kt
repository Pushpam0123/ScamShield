package com.scamshield.core.analysis.url

import com.scamshield.core.model.PublicSuffixList
import java.net.IDN
import java.util.Locale

/**
 * Parses a Public Suffix List snapshot and implements the real eTLD+1 algorithm against it.
 *
 * design.md section 2.2 is explicit that "last two labels" is not an acceptable approximation
 * -- co.in and gov.in both break it immediately, and both are common in Indian phishing. This
 * is the actual algorithm published at publicsuffix.org:
 *
 * 1. Split the host into labels and find every listed rule that matches it. A plain rule
 *    ("co.in") matches when its labels equal the host's trailing labels one-for-one; a
 *    wildcard rule ("*.example.com") matches the same way but a `*` label matches any
 *    single host label.
 * 2. If no rule matches at all, the implicit "prevailing rule" is a single wildcard label:
 *    the host's own last label is treated as the public suffix. This is why an unlisted
 *    brand-new TLD still resolves to *something* rather than null.
 * 3. If any exception rule matches (a rule prefixed with `!`, e.g. `!city.kawasaki.jp`),
 *    it wins over any wildcard rule that would otherwise be more specific, and the public
 *    suffix is the exception rule's labels with the leftmost label removed.
 * 4. Otherwise the prevailing rule is whichever matching rule has the most labels (the
 *    most specific match).
 * 5. The public suffix is the host's trailing N labels, where N is the prevailing rule's
 *    label count. The registrable domain (eTLD+1) is the public suffix plus one more label
 *    to the left. If the host has no label to spare -- it *is* exactly the public suffix,
 *    e.g. the bare host "co.in" -- there is no registrable domain and this returns null.
 *
 * The parser only reads rule lines (this class does not itself do file I/O): `rulepack/
 * build_rulepack.py` strips comments and blank lines at pack-build time, so the asset this
 * loads from is already just one rule per line. See DECISIONS.md for why this pure algorithm
 * lives in `:core:analysis` (a plain JVM module, unit-testable without Android) rather than in
 * `:core:data`, which owns only the asset *I/O* around it.
 */
class PublicSuffixListParser private constructor(
    private val plainRules: Set<List<String>>,
    private val wildcardRules: Set<List<String>>,
    private val exceptionRules: Set<List<String>>,
) : PublicSuffixList {

    override fun registrableDomain(host: String): String? {
        if (isIpLiteral(host)) return null

        val asciiHost = toAsciiLabels(host) ?: return null
        if (asciiHost.isEmpty()) return null

        val exceptionMatch = exceptionRules
            .filter { rule -> matches(rule, asciiHost) }
            .maxByOrNull { it.size }
        if (exceptionMatch != null) {
            // Public suffix = exception rule's labels minus the leftmost one.
            val suffixLabelCount = exceptionMatch.size - 1
            return trailingLabelsPlusOne(asciiHost, suffixLabelCount)
        }

        val wildcardMatch = wildcardRules
            .filter { rule -> matches(rule, asciiHost) }
            .maxByOrNull { it.size }
        val plainMatch = plainRules
            .filter { rule -> matches(rule, asciiHost) }
            .maxByOrNull { it.size }

        val prevailing = listOfNotNull(wildcardMatch, plainMatch).maxByOrNull { it.size }
        val suffixLabelCount = prevailing?.size ?: 1 // implicit "*" rule: last label is the suffix

        return trailingLabelsPlusOne(asciiHost, suffixLabelCount)
    }

    override fun isRecognizedTld(label: String): Boolean {
        val normalized = label.trim().lowercase(Locale.ROOT)
        if (normalized.isEmpty()) return false
        return listOf(normalized) in plainRules
    }

    /** A rule's labels, right-aligned against the host's labels, `*` matching any one label. */
    private fun matches(ruleLabels: List<String>, hostLabels: List<String>): Boolean {
        if (ruleLabels.size > hostLabels.size) return false
        val hostTail = hostLabels.takeLast(ruleLabels.size)
        return ruleLabels.indices.all { i -> ruleLabels[i] == "*" || ruleLabels[i] == hostTail[i] }
    }

    private fun trailingLabelsPlusOne(hostLabels: List<String>, suffixLabelCount: Int): String? {
        val registrableLabelCount = suffixLabelCount + 1
        if (hostLabels.size < registrableLabelCount) return null
        return hostLabels.takeLast(registrableLabelCount).joinToString(".")
    }

    /**
     * `design.md`'s test fixtures include `http://192.168.1.1/login`, a bare IPv4 host with
     * no registrable domain at all. Detected with plain regex, not `InetAddress`: the latter
     * can trigger a DNS lookup for non-literal input, and a URL-forensics module handling raw
     * scam-message text must not go anywhere near a network API even by accident
     * (`architecture.md` section 10.1).
     */
    private fun isIpLiteral(host: String): Boolean {
        val stripped = host.trim().removeSurrounding("[", "]")
        if (IPV4_LITERAL.matches(stripped)) {
            return stripped.split(".").all { (it.toIntOrNull() ?: -1) in 0..255 }
        }
        return stripped.contains(':') && IPV6_LITERAL.matches(stripped)
    }

    /** Punycode-encodes and lowercases; returns null for a host that IDN cannot process. */
    private fun toAsciiLabels(host: String): List<String>? {
        val trimmed = host.trim().trim('.')
        if (trimmed.isEmpty()) return null
        return try {
            IDN.toASCII(trimmed, IDN.ALLOW_UNASSIGNED)
                .lowercase(Locale.ROOT)
                .split('.')
                .filter { it.isNotEmpty() }
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    companion object {
        private val IPV4_LITERAL = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
        private val IPV6_LITERAL = Regex("^[0-9a-fA-F:]+$")

        /** [ruleLines] is one PSL rule per line, no comments, no blank lines. */
        fun parse(ruleLines: List<String>): PublicSuffixListParser {
            val plain = mutableSetOf<List<String>>()
            val wildcard = mutableSetOf<List<String>>()
            val exception = mutableSetOf<List<String>>()

            for (rawLine in ruleLines) {
                val line = rawLine.trim()
                if (line.isEmpty()) continue

                when {
                    line.startsWith("!") -> {
                        exception += line.substring(1).lowercase(Locale.ROOT).split('.')
                    }
                    line.startsWith("*.") -> {
                        wildcard += line.lowercase(Locale.ROOT).split('.')
                    }
                    else -> {
                        plain += line.lowercase(Locale.ROOT).split('.')
                    }
                }
            }

            return PublicSuffixListParser(plain, wildcard, exception)
        }
    }
}
