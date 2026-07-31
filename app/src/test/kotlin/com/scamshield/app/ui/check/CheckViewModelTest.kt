package com.scamshield.app.ui.check

import com.google.common.truth.Truth.assertThat
import com.scamshield.app.R
import com.scamshield.core.analysis.ingest.MessageNormalizer
import com.scamshield.core.analysis.orchestrator.AnalysisPipeline
import com.scamshield.core.analysis.orchestrator.Orchestrator
import com.scamshield.core.analysis.url.PublicSuffixListParser
import com.scamshield.core.analysis.url.UrlExtractor
import com.scamshield.core.explain.ExplanationBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CheckViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private fun viewModel() = CheckViewModel(
        messageNormalizer = MessageNormalizer(UrlExtractor(PublicSuffixListParser.parse(emptyList())), emptyList()),
        analysisPipeline = AnalysisPipeline(Orchestrator(emptyList()), rulepackVersion = "test"),
        explanationBuilder = ExplanationBuilder(RuntimeEnvironment.getApplication()),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is empty`() {
        assertThat(viewModel().uiState.value.messageText).isEmpty()
    }

    @Test
    fun `onTextChanged updates the message text and clears any error`() {
        val vm = viewModel()
        vm.onCheck() // blank -> sets an error
        vm.onTextChanged("hello")
        val state = vm.uiState.value
        assertThat(state.messageText).isEqualTo("hello")
        assertThat(state.errorMessageRes).isNull()
    }

    @Test
    fun `checking a blank message sets the empty-message error and runs no analysis`() {
        val vm = viewModel()
        vm.onCheck()
        val state = vm.uiState.value
        assertThat(state.errorMessageRes).isEqualTo(R.string.error_empty_message)
        assertThat(state.result).isNull()
    }

    @Test
    fun `checking real text runs the pipeline end to end and produces a result`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onTextChanged("Your OTP is 4821")
        vm.onCheck()
        dispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertThat(state.isAnalyzing).isFalse()
        assertThat(state.result).isNotNull()
        assertThat(state.explanation).isNotNull()
        assertThat(state.errorMessageRes).isNull()
    }

    @Test
    fun `onPrefill sets the text without running analysis`() {
        val vm = viewModel()
        vm.onPrefill("shared message text")
        val state = vm.uiState.value
        assertThat(state.messageText).isEqualTo("shared message text")
        assertThat(state.result).isNull()
    }

    @Test
    fun `onCheckAnother resets back to an empty initial state`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onTextChanged("some message")
        vm.onCheck()
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(vm.uiState.value.result).isNotNull()

        vm.onCheckAnother()
        val state = vm.uiState.value
        assertThat(state.result).isNull()
        assertThat(state.explanation).isNull()
        assertThat(state.messageText).isEmpty()
    }

    @Test
    fun `onClear resets the whole state`() {
        val vm = viewModel()
        vm.onTextChanged("something")
        vm.onClear()
        assertThat(vm.uiState.value.messageText).isEmpty()
    }
}
