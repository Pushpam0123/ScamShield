package com.scamshield.core.analysis.ingest

import com.scamshield.core.analysis.brand.BrandClaimExtractor
import com.scamshield.core.analysis.language.LanguageDetector
import com.scamshield.core.analysis.url.UrlExtractor
import com.scamshield.core.model.BankEntry
import com.scamshield.core.model.NormalizedMessage
import com.scamshield.core.model.RawMessage

/**
 * Ties the four ingest steps together (architecture.md's data-flow diagram: "Ingest &
 * Normalize", upstream of the orchestrator fan-out): text normalization (design.md section
 * 2.1), URL extraction (2.2), language detection (2.3), and brand-claim extraction (4.1).
 *
 * Takes an immutable snapshot of [banks] at construction rather than a live rule-pack
 * reference. Rule packs ship bundled-only in v1 (architecture.md section 11); a
 * hot-reloadable rule pack is not a current requirement, and threading a mutable snapshot
 * through here would be speculative complexity against nothing this version needs to do.
 * Reconstructing this class is cheap if that ever changes.
 */
class MessageNormalizer(
    private val urlExtractor: UrlExtractor,
    private val banks: List<BankEntry>,
) {
    fun normalize(raw: RawMessage): NormalizedMessage {
        val normalizedText = TextNormalizer.normalize(raw.text)
        return NormalizedMessage(
            id = raw.id,
            original = raw.text,
            normalized = normalizedText,
            urls = urlExtractor.extract(raw.text),
            senderHint = raw.senderHint,
            detectedLanguage = LanguageDetector.detect(normalizedText),
            brandClaims = BrandClaimExtractor.extract(raw.text, banks),
        )
    }
}
