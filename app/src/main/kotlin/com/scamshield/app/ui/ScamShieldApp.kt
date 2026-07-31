package com.scamshield.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.scamshield.app.ui.check.CheckScreen
import com.scamshield.app.ui.check.CheckViewModel
import com.scamshield.app.ui.result.ResultScreen

/**
 * Root composable: the Check -> Result flow of `design.md` section 10.1.
 *
 * Not a `NavHost` -- Phase 1 is exactly two screens in a strictly linear relationship (History,
 * Learn, and Settings are Phase 5), and the Result screen needs the full `AnalysisResult` /
 * `ResultExplanation` objects, which a nav-graph route argument cannot carry without extra
 * serialization machinery this phase does not need. One `ViewModel`-backed state switch is
 * simpler and is exactly what `implementation.md`'s "do not build speculative complexity"
 * working rule calls for.
 */
@Composable
fun ScamShieldApp(
    sharedText: String?,
    onSharedTextConsumed: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CheckViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(sharedText) {
        if (sharedText != null) {
            viewModel.onPrefill(sharedText)
            onSharedTextConsumed()
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        val result = uiState.result
        val explanation = uiState.explanation
        if (result != null && explanation != null) {
            BackHandler(onBack = viewModel::onCheckAnother)
            ResultScreen(
                originalText = uiState.messageText,
                result = result,
                explanation = explanation,
                onCheckAnother = viewModel::onCheckAnother,
            )
        } else {
            CheckScreen(
                messageText = uiState.messageText,
                isAnalyzing = uiState.isAnalyzing,
                errorMessageRes = uiState.errorMessageRes,
                onTextChanged = viewModel::onTextChanged,
                onCheck = viewModel::onCheck,
                onClear = viewModel::onClear,
            )
        }
    }
}
