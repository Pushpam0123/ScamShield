package com.scamshield.core.explain

import android.content.Context
import com.scamshield.core.model.AnalysisResult
import com.scamshield.core.model.Evidence
import com.scamshield.core.model.EvidenceType
import com.scamshield.core.model.ScamCategory
import com.scamshield.core.model.Verdict

/**
 * What a Result screen composes from an [AnalysisResult] (design.md section 8):
 * a headline is verdict text the screen already owns via `:app`'s own string resources
 * (design.md's "This looks like a fake KYC message" is illustrative, not a mandate for a
 * distinct string per verdict-category pair -- the existing per-verdict headline strings in
 * `:app` cover this), so what belongs here is the evidence list and the action block.
 */
data class ResultExplanation(
    val topEvidence: List<String>,
    val remainingEvidenceCount: Int,
    val actionItems: List<String>,
)

/**
 * Renders [Evidence] and [AnalysisResult] into English strings (Phase 5 adds the other six
 * languages -- design.md section 10.4). Lives in `:core:explain`, an Android library module,
 * specifically so this can resolve `strings.xml` through a real `Context` rather than
 * hand-rolling its own template engine (constraint C5 puts every user-visible string there).
 *
 * [evidenceText] is a `when` over [EvidenceType] with no `else` branch on purpose: Evidence.kt's
 * own doc comment promises "adding a constant without adding its template is a build failure,
 * not a runtime blank," and an exhaustive `when` is what makes the compiler enforce that rather
 * than a map lookup that would only fail at runtime.
 */
class ExplanationBuilder(private val context: Context) {

    fun explain(result: AnalysisResult): ResultExplanation {
        val rendered = result.evidence.map(::evidenceText)
        return ResultExplanation(
            topEvidence = rendered.take(TOP_EVIDENCE_COUNT),
            remainingEvidenceCount = (rendered.size - TOP_EVIDENCE_COUNT).coerceAtLeast(0),
            actionItems = actionItems(result.verdict, result.category),
        )
    }

    fun evidenceText(evidence: Evidence): String {
        val slots = evidence.slots
        return when (evidence.type) {
            EvidenceType.DOMAIN_VERY_NEW -> {
                val ageDays = slots["age_days"]
                if (ageDays != null) {
                    context.getString(R.string.ev_domain_very_new_with_age, ageDays)
                } else {
                    context.getString(R.string.ev_domain_very_new_unknown_age)
                }
            }
            EvidenceType.TYPOSQUAT_OF_KNOWN_BRAND -> context.getString(
                R.string.ev_typosquat,
                slots["actual"].orEmpty(),
                slots["brand"].orEmpty(),
                slots["real_domain"].orEmpty(),
            )
            EvidenceType.HOMOGRAPH_CHARACTERS -> context.getString(R.string.ev_homograph, slots["host"].orEmpty())
            EvidenceType.URL_SHORTENER -> context.getString(R.string.ev_url_shortener, slots["host"].orEmpty())
            EvidenceType.LINK_TEXT_MISMATCH -> context.getString(
                R.string.ev_link_text_mismatch,
                slots["displayed"].orEmpty(),
                slots["actual"].orEmpty(),
            )
            EvidenceType.IP_ADDRESS_HOST -> context.getString(R.string.ev_ip_address_host, slots["host"].orEmpty())
            EvidenceType.SUSPICIOUS_TLD -> context.getString(R.string.ev_suspicious_tld, slots["tld"].orEmpty())
            EvidenceType.NON_HTTPS_CREDENTIAL_PAGE -> context.getString(R.string.ev_non_https_credential_page)
            EvidenceType.BRAND_CLAIM_WITHOUT_DLT_HEADER -> context.getString(R.string.ev_brand_claim_without_dlt)
            EvidenceType.UNREGISTERED_NUMERIC_SENDER -> context.getString(R.string.ev_unregistered_numeric_sender)
            EvidenceType.DLT_HEADER_MISMATCH -> context.getString(
                R.string.ev_dlt_header_mismatch,
                slots["claimed_brand"].orEmpty(),
                slots["header_brands"].orEmpty(),
            )
            EvidenceType.OTP_SOLICITATION -> context.getString(R.string.ev_otp_solicitation)
            EvidenceType.UPI_COLLECT_FRAMING -> context.getString(R.string.ev_upi_collect_framing)
            EvidenceType.URGENCY_DEADLINE -> context.getString(R.string.ev_urgency_deadline)
            EvidenceType.THREAT_OF_LOSS -> context.getString(R.string.ev_threat_of_loss)
            EvidenceType.UNREALISTIC_REWARD -> context.getString(R.string.ev_unrealistic_reward)
            EvidenceType.PREMIUM_RATE_NUMBER -> context.getString(R.string.ev_premium_rate_number)
            EvidenceType.CRYPTO_WALLET_ADDRESS -> context.getString(R.string.ev_crypto_wallet_address)
            EvidenceType.ADVANCE_FEE_REQUEST -> context.getString(R.string.ev_advance_fee_request)
            EvidenceType.MODEL_HIGH_SCAM_SCORE -> context.getString(R.string.ev_model_high_scam_score)
            EvidenceType.MODEL_LOW_SCAM_SCORE -> context.getString(R.string.ev_model_low_scam_score)
        }
    }

    /**
     * design.md section 8: a category-specific lead-in, then four items always shown for a
     * SCAM or SUSPICIOUS verdict. A SAFE verdict gets no action block at all -- the copy rules
     * forbid alarming a user who was just told nothing looks wrong.
     */
    fun actionItems(verdict: Verdict, category: ScamCategory): List<String> {
        if (verdict == Verdict.SAFE) return emptyList()
        val leadIn = categoryLeadIn(category)?.let { context.getString(it) }
        val universal = UNIVERSAL_ACTIONS.map { context.getString(it) }
        return listOfNotNull(leadIn) + universal
    }

    private fun categoryLeadIn(category: ScamCategory): Int? = when (category) {
        ScamCategory.KYC_PHISHING -> R.string.action_kyc_phishing
        ScamCategory.FAKE_JOB -> R.string.action_fake_job
        ScamCategory.LOTTERY_PRIZE -> R.string.action_lottery_prize
        ScamCategory.DIGITAL_ARREST -> R.string.action_digital_arrest
        ScamCategory.COURIER_CUSTOMS -> R.string.action_courier_customs
        ScamCategory.LOAN_APP -> R.string.action_loan_app
        ScamCategory.UPI_COLLECT -> R.string.action_upi_collect
        ScamCategory.INVESTMENT_TRADING -> R.string.action_investment_trading
        ScamCategory.TECH_SUPPORT -> R.string.action_tech_support
        ScamCategory.SEXTORTION -> R.string.action_sextortion
        ScamCategory.ELECTRICITY_DISCONNECTION -> R.string.action_electricity_disconnection
        ScamCategory.OTHER_SCAM -> R.string.action_other_scam
        ScamCategory.NOT_SCAM -> null
    }

    companion object {
        private const val TOP_EVIDENCE_COUNT = 3

        private val UNIVERSAL_ACTIONS = listOf(
            R.string.action_do_not_click,
            R.string.action_do_not_share_otp,
            R.string.action_verify_by_calling,
            R.string.action_report_cybercrime,
        )
    }
}
