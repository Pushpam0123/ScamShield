package com.scamshield.app.ui.result

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dangerous
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.scamshield.app.R
import com.scamshield.app.ui.theme.VerdictColors
import com.scamshield.core.explain.ResultExplanation
import com.scamshield.core.model.AnalysisResult
import com.scamshield.core.model.Confidence
import com.scamshield.core.model.Verdict

/**
 * `design.md` section 10.1/10.2's Result screen: a verdict banner (color, icon, and text --
 * never color alone), the checked message, the evidence list with a show-all expander, and the
 * action block. Section 10.3 requires >= 48 dp touch targets and a TalkBack description on the
 * banner that reads as one coherent sentence rather than fragments.
 */
@Composable
fun ResultScreen(
    originalText: String,
    result: AnalysisResult,
    explanation: ResultExplanation,
    onCheckAnother: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showAllEvidence by remember(result) { mutableStateOf(false) }
    val presentation = verdictPresentation(result.verdict)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        VerdictBanner(presentation = presentation, confidence = result.confidence)

        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.result_message_heading), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(originalText, style = MaterialTheme.typography.bodyLarge)

        if (explanation.allEvidence.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.result_why_heading), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            val shown = if (showAllEvidence) explanation.allEvidence else explanation.topEvidence
            shown.forEach { line ->
                Text("•  $line", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 8.dp))
            }
            if (explanation.remainingEvidenceCount > 0 || showAllEvidence) {
                TextButton(onClick = { showAllEvidence = !showAllEvidence }) {
                    Text(
                        stringResource(
                            if (showAllEvidence) R.string.result_show_fewer_evidence else R.string.result_show_all_evidence,
                        ),
                    )
                }
            }
        }

        if (explanation.actionItems.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.result_what_to_do_heading), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            explanation.actionItems.forEach { line ->
                Text("•  $line", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 8.dp))
            }
        }

        Spacer(Modifier.height(32.dp))
        Button(
            onClick = onCheckAnother,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Text(stringResource(R.string.result_check_another))
        }
    }
}

private data class VerdictPresentation(
    val headlineRes: Int,
    val contentDescriptionRes: Int,
    val icon: ImageVector,
    val containerColor: Color,
    val onContainerColor: Color,
)

private fun verdictPresentation(verdict: Verdict): VerdictPresentation = when (verdict) {
    Verdict.SCAM -> VerdictPresentation(
        headlineRes = R.string.verdict_scam_headline,
        contentDescriptionRes = R.string.cd_verdict_scam,
        icon = Icons.Filled.Dangerous,
        containerColor = VerdictColors.ScamContainer,
        onContainerColor = VerdictColors.OnScamContainer,
    )
    Verdict.SUSPICIOUS -> VerdictPresentation(
        headlineRes = R.string.verdict_suspicious_headline,
        contentDescriptionRes = R.string.cd_verdict_suspicious,
        icon = Icons.Outlined.Warning,
        containerColor = VerdictColors.SuspiciousContainer,
        onContainerColor = VerdictColors.OnSuspiciousContainer,
    )
    Verdict.SAFE -> VerdictPresentation(
        headlineRes = R.string.verdict_safe_headline,
        contentDescriptionRes = R.string.cd_verdict_safe,
        icon = Icons.Outlined.CheckCircle,
        containerColor = VerdictColors.SafeContainer,
        onContainerColor = VerdictColors.OnSafeContainer,
    )
}

private fun confidenceTextRes(confidence: Confidence): Int = when (confidence) {
    Confidence.HIGH -> R.string.confidence_high
    Confidence.MEDIUM -> R.string.confidence_medium
    Confidence.LOW -> R.string.confidence_low
}

@Composable
private fun VerdictBanner(presentation: VerdictPresentation, confidence: Confidence) {
    val description = stringResource(presentation.contentDescriptionRes)
    Surface(
        color = presentation.containerColor,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = description },
    ) {
        Column(Modifier.padding(20.dp)) {
            Row {
                Icon(
                    imageVector = presentation.icon,
                    contentDescription = null, // the Surface's own semantics already describe the banner as one sentence
                    tint = presentation.onContainerColor,
                    modifier = Modifier.size(32.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(presentation.headlineRes),
                    style = MaterialTheme.typography.headlineSmall,
                    color = presentation.onContainerColor,
                )
            }
            if (presentation.headlineRes == R.string.verdict_safe_headline) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.verdict_safe_subtitle),
                    style = MaterialTheme.typography.bodyLarge,
                    color = presentation.onContainerColor,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(confidenceTextRes(confidence)),
                style = MaterialTheme.typography.bodyMedium,
                color = presentation.onContainerColor,
            )
        }
    }
}
