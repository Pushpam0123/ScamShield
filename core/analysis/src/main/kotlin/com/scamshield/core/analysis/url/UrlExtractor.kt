package com.scamshield.core.analysis.url

import com.scamshield.core.model.ExtractedUrl
import com.scamshield.core.model.PublicSuffixList

/**
 * Finds URLs in a message, design.md section 2.2.
 *
 * Runs against the message's *original* text, not the normalized form: normalization
 * lowercases and NFKC-folds the string, which for a homograph domain like `xn--sb-xkc.com`
 * vs `sb` + dotless-i + `.com` would erase exactly the character difference the URL analyzer
 * (design.md section 3.3) needs to see. Every span this returns indexes into that same
 * original string.
 *
 * Zero-width characters inserted mid-URL to evade extraction are not specifically handled --
 * design.md section 2.1 only documents that evasion for keyword matching, and unlike a
 * keyword, a URL with an invisible character spliced into its scheme or host would not
 * actually resolve for the victim either. Revisit only if Phase 2's real-world corpus shows
 * this pattern in practice (see DECISIONS.md).
 *
 * Three forms are matched, tried in this order at every position so a more specific prefix
 * always wins over a looser one starting at the same point:
 *   1. `scheme://...`      -- unambiguous, kept whole up to the next space or quote.
 *   2. `www.host[/path]`   -- unambiguous, "www." cannot appear in ordinary prose as a domain.
 *   3. bare `host[/path]`  -- design.md's own example is "sbi-verify.xyz/kyc": scam SMS
 *      routinely omits the scheme entirely. This is the risky case for false positives
 *      ("no.of", "e.g."), so a bare candidate is kept only when its last label is a
 *      [PublicSuffixList.isRecognizedTld] suffix, not merely "PSL resolved to something" --
 *      PSL's own fallback rule means it resolves *any* two-label string, which would defeat
 *      the filter entirely if used directly.
 *
 * Host labels are matched as Unicode letters/digits, not ASCII-only: a homograph host written
 * without a scheme (`sb` + Cyrillic-look-alike + `i.com`) must still be extracted, since
 * homograph detection happens downstream on the extracted host, not here. The Unicode
 * permissiveness does not cost precision, because the TLD gate above is what actually filters
 * ordinary prose -- a Devanagari sentence never ends its clause with a period-separated ASCII
 * string that happens to be a real suffix like "com" or "in".
 */
class UrlExtractor(private val publicSuffixList: PublicSuffixList) {

    fun extract(original: String): List<ExtractedUrl> {
        val results = mutableListOf<ExtractedUrl>()
        for (match in URL_CANDIDATE.findAll(original)) {
            val candidate = match.value
            val url = when {
                candidate.startsWith("http://", ignoreCase = true) ||
                    candidate.startsWith("https://", ignoreCase = true) ->
                    buildFrom(candidate, match.range.first, hasScheme = true)

                candidate.startsWith("www.", ignoreCase = true) ->
                    buildFrom(candidate, match.range.first, hasScheme = false)

                else -> buildBareDomainCandidate(candidate, match.range.first)
            }
            if (url != null) results += url
        }
        return results
    }

    private fun buildFrom(candidate: String, startOffset: Int, hasScheme: Boolean): ExtractedUrl? {
        val scheme = if (hasScheme) candidate.substringBefore("://") else null
        val afterScheme = if (hasScheme) candidate.substringAfter("://") else candidate
        val hostAndRest = stripUserinfo(afterScheme)
        val (host, path) = splitHostAndPath(hostAndRest) ?: return null

        return toExtractedUrl(candidate, scheme, host, path, startOffset, startOffset + candidate.length)
    }

    private fun buildBareDomainCandidate(candidate: String, startOffset: Int): ExtractedUrl? {
        val (host, path) = splitHostAndPath(candidate) ?: return null
        val lastLabel = host.substringAfterLast('.')
        if (!publicSuffixList.isRecognizedTld(lastLabel)) return null

        return toExtractedUrl(candidate, null, host, path, startOffset, startOffset + candidate.length)
    }

    private fun toExtractedUrl(
        raw: String,
        scheme: String?,
        host: String,
        path: String?,
        spanStart: Int,
        spanEnd: Int,
    ): ExtractedUrl {
        val isPunycode = host.split('.').any { it.startsWith("xn--", ignoreCase = true) }
        return ExtractedUrl(
            raw = raw,
            scheme = scheme,
            host = host,
            registrableDomain = publicSuffixList.registrableDomain(host),
            path = path,
            isPunycode = isPunycode,
            displayText = null, // design.md section 2.2: only populated for rich-text sources; this app ingests plain text only.
            spanStart = spanStart,
            spanEnd = spanEnd,
        )
    }

    /** Drops a `user:pass@` prefix, if present, so it is never mistaken for part of the host. */
    private fun stripUserinfo(afterScheme: String): String {
        val pathStart = afterScheme.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val authorityEnd = if (pathStart >= 0) pathStart else afterScheme.length
        val atIndex = afterScheme.indexOf('@')
        return if (atIndex in 0 until authorityEnd) afterScheme.substring(atIndex + 1) else afterScheme
    }

    /** Splits at the first path/query/fragment/port delimiter; null if nothing is left as host. */
    private fun splitHostAndPath(hostAndRest: String): Pair<String, String?>? {
        val endIndex = hostAndRest.indexOfFirst { it == '/' || it == '?' || it == '#' || it == ':' }
        val host = if (endIndex >= 0) hostAndRest.substring(0, endIndex) else hostAndRest
        if (host.isEmpty()) return null
        val path = if (endIndex >= 0) hostAndRest.substring(endIndex).ifEmpty { null } else null
        return host to path
    }

    companion object {
        // A DNS label: a Unicode letter or digit, optionally followed by more letters/digits/
        // hyphens, 1-63 characters. See the class doc for why this is Unicode, not ASCII-only.
        private const val LABEL = "[\\p{L}\\p{N}](?:[\\p{L}\\p{N}-]{0,61}[\\p{L}\\p{N}])?"
        private const val PATH = "(?:/[^\\s<>\"'\\u00A0]*)?"

        private const val SCHEME_URL = "https?://[^\\s<>\"'\\u00A0]+"
        private const val WWW_URL = "www\\.$LABEL(?:\\.$LABEL)+$PATH"
        private const val BARE_DOMAIN = "$LABEL(?:\\.$LABEL)+$PATH"

        // Never start a match in the middle of a larger token: not preceded by a word
        // character, "@" (mid-email-address), or "." (mid another domain's labels).
        private val URL_CANDIDATE = Regex(
            "(?<![\\w@.])(?:$SCHEME_URL|$WWW_URL|$BARE_DOMAIN)",
            RegexOption.IGNORE_CASE,
        )
    }
}
