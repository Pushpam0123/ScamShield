package com.scamshield.app.di

import android.content.Context
import com.scamshield.analyzer.pattern.PatternAnalyzer
import com.scamshield.analyzer.sender.SenderAnalyzer
import com.scamshield.analyzer.url.UrlAnalyzer
import com.scamshield.core.analysis.orchestrator.AnalysisPipeline
import com.scamshield.core.analysis.orchestrator.Orchestrator
import com.scamshield.core.data.rulepack.LoadedRulePack
import com.scamshield.core.explain.ExplanationBuilder
import com.scamshield.core.model.Analyzer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * architecture.md section 5: "`:app` is the only module that may see analyzer
 * *implementations*." This is the one place `UrlAnalyzer`/`SenderAnalyzer`/`PatternAnalyzer`
 * (concrete classes, each in its own `:analyzer:*` module) are constructed and bound to the
 * `Analyzer` interface `:core:analysis`'s `Orchestrator` depends on.
 *
 * The classifier analyzer has no entry here: Phase 1 ships with it permanently absent
 * (architecture.md C6 / `implementation.md` Phase 1's own scope -- "with the classifier signal
 * permanently `Unavailable`"). `FusionPolicy` already treats a classifier id missing from the
 * signal list the same as an explicit `Signal.Unavailable`, so simply not including it here is
 * sufficient; Phase 4 adds a fourth `@Provides` function and one more entry in the list below.
 */
@Module
@InstallIn(SingletonComponent::class)
object AnalysisModule {

    @Provides
    @Singleton
    fun provideUrlAnalyzer(loadedRulePack: LoadedRulePack): UrlAnalyzer {
        val rulePack = loadedRulePack.rulePack
        return UrlAnalyzer(
            confusables = rulePack.confusables,
            banks = rulePack.banks,
            shorteners = rulePack.shorteners,
            shortenerBrandOperated = rulePack.shortenerBrandOperated,
            suspiciousTlds = rulePack.suspiciousTlds,
            reputationIndex = loadedRulePack.reputationIndex,
        )
    }

    @Provides
    @Singleton
    fun provideSenderAnalyzer(loadedRulePack: LoadedRulePack): SenderAnalyzer =
        SenderAnalyzer(loadedRulePack.rulePack.banks)

    @Provides
    @Singleton
    fun providePatternAnalyzer(loadedRulePack: LoadedRulePack): PatternAnalyzer =
        PatternAnalyzer(loadedRulePack.rulePack.patterns)

    // `List<Analyzer>` needs @JvmSuppressWildcards at both ends of this binding: Kotlin's
    // `List<out E>` declaration-site variance otherwise makes javac-generated Dagger code see
    // `List<? extends Analyzer>` from one of these two signatures and `List<Analyzer>` from the
    // other, which Dagger treats as two different binding keys ("MissingBinding").
    @Provides
    @Singleton
    fun provideAnalyzers(
        urlAnalyzer: UrlAnalyzer,
        senderAnalyzer: SenderAnalyzer,
        patternAnalyzer: PatternAnalyzer,
    ): List<@JvmSuppressWildcards Analyzer> = listOf(urlAnalyzer, senderAnalyzer, patternAnalyzer)

    @Provides
    @Singleton
    fun provideOrchestrator(analyzers: List<@JvmSuppressWildcards Analyzer>): Orchestrator = Orchestrator(analyzers)

    @Provides
    @Singleton
    fun provideAnalysisPipeline(orchestrator: Orchestrator, loadedRulePack: LoadedRulePack): AnalysisPipeline =
        AnalysisPipeline(
            orchestrator = orchestrator,
            rulepackVersion = loadedRulePack.rulePack.meta.version,
            modelVersion = null, // Phase 4 wires the classifier's model version in here.
        )

    @Provides
    @Singleton
    fun provideExplanationBuilder(@ApplicationContext context: Context): ExplanationBuilder =
        ExplanationBuilder(context)
}
