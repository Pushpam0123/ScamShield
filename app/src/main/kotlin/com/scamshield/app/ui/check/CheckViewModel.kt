package com.scamshield.app.ui.check

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.scamshield.app.R
import com.scamshield.core.analysis.ingest.MessageNormalizer
import com.scamshield.core.analysis.orchestrator.AnalysisPipeline
import com.scamshield.core.explain.ExplanationBuilder
import com.scamshield.core.explain.ResultExplanation
import com.scamshield.core.model.AnalysisResult
import com.scamshield.core.model.MessageId
import com.scamshield.core.model.MessageSource
import com.scamshield.core.model.RawMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CheckUiState(
    val messageText: String = "",
    val isAnalyzing: Boolean = false,
    val result: AnalysisResult? = null,
    val explanation: ResultExplanation? = null,
    @StringRes val errorMessageRes: Int? = null,
)

/**
 * Drives both the Check and Result screens: `design.md` section 10.1 treats them as one flow
 * (paste -> check -> see the result -> check another), so one `ViewModel` scoped to that flow
 * is simpler than splitting state across two and re-synchronizing it.
 *
 * Runs the real ingest-plus-analysis pipeline end to end -- `MessageNormalizer` (section 2),
 * then `AnalysisPipeline` (the orchestrator fan-out and fusion policy, section 7), then
 * `ExplanationBuilder` (section 8) -- with no logic of its own beyond wiring those three
 * together and holding the result as UI state.
 */
@HiltViewModel
class CheckViewModel @Inject constructor(
    private val messageNormalizer: MessageNormalizer,
    private val analysisPipeline: AnalysisPipeline,
    private val explanationBuilder: ExplanationBuilder,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CheckUiState())
    val uiState: StateFlow<CheckUiState> = _uiState.asStateFlow()

    fun onTextChanged(text: String) {
        _uiState.update { it.copy(messageText = text, errorMessageRes = null) }
    }

    /** Populates the box from a share-sheet/process-text intent without triggering a check. */
    fun onPrefill(text: String) {
        _uiState.update { it.copy(messageText = text, errorMessageRes = null) }
    }

    fun onClear() {
        _uiState.update { CheckUiState() }
    }

    /** Back to the Check screen with the box empty, ready for the next message. */
    fun onCheckAnother() {
        _uiState.update { CheckUiState() }
    }

    fun onCheck() {
        val text = _uiState.value.messageText
        if (text.isBlank()) {
            _uiState.update { it.copy(errorMessageRes = R.string.error_empty_message) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isAnalyzing = true, errorMessageRes = null) }
            try {
                val raw = RawMessage(
                    id = MessageId(UUID.randomUUID().toString()),
                    text = text,
                    senderHint = null, // design.md section 1: only a share-sheet source ever supplies one; not collected by this UI.
                    source = MessageSource.MANUAL,
                    receivedAt = Instant.now(),
                )
                val normalized = messageNormalizer.normalize(raw)
                val result = analysisPipeline.analyze(normalized)
                val explanation = explanationBuilder.explain(result)
                _uiState.update { it.copy(isAnalyzing = false, result = result, explanation = explanation) }
            } catch (e: Exception) {
                // architecture.md C6: a failure here must degrade, not crash -- every analyzer
                // already degrades internally, so reaching this branch means something outside
                // that contract broke (e.g. normalization itself), not an expected outcome.
                _uiState.update { it.copy(isAnalyzing = false, errorMessageRes = R.string.error_analysis_failed) }
            }
        }
    }
}
