package com.scamshield.app.di

import android.content.Context
import com.scamshield.analyzer.classifier.AndroidClassifierAssets
import com.scamshield.analyzer.classifier.ClassifierAnalyzer
import com.scamshield.analyzer.classifier.ClassifierAssets
import com.scamshield.analyzer.classifier.parseClassifierMeta
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
 * Phase 4 adds the classifier as the fourth analyzer (see [provideClassifierAnalyzer]). It is
 * bound unconditionally: when no model asset is bundled it self-degrades to `Signal.Unavailable`,
 * and `FusionPolicy` renormalizes the remaining weights exactly as it did when the binding was
 * absent (architecture.md C6). So "no model trained yet" and "model present" run the identical
 * graph — only the runtime signal differs.
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

    @Provides
    @Singleton
    fun provideClassifierAssets(@ApplicationContext context: Context): ClassifierAssets =
        AndroidClassifierAssets(context)

    @Provides
    @Singleton
    fun provideClassifierAnalyzer(assets: ClassifierAssets): ClassifierAnalyzer = ClassifierAnalyzer(assets)

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
        classifierAnalyzer: ClassifierAnalyzer,
    ): List<@JvmSuppressWildcards Analyzer> =
        listOf(urlAnalyzer, senderAnalyzer, patternAnalyzer, classifierAnalyzer)

    @Provides
    @Singleton
    fun provideOrchestrator(analyzers: List<@JvmSuppressWildcards Analyzer>): Orchestrator = Orchestrator(analyzers)

    @Provides
    @Singleton
    fun provideAnalysisPipeline(
        orchestrator: Orchestrator,
        loadedRulePack: LoadedRulePack,
        classifierAssets: ClassifierAssets,
    ): AnalysisPipeline =
        AnalysisPipeline(
            orchestrator = orchestrator,
            rulepackVersion = loadedRulePack.rulePack.meta.version,
            // Provenance only; null when no model/version is bundled (the toy meta carries none).
            modelVersion = runCatching { parseClassifierMeta(classifierAssets.readMetaJson()).modelVersion }.getOrNull(),
        )

    @Provides
    @Singleton
    fun provideExplanationBuilder(@ApplicationContext context: Context): ExplanationBuilder =
        ExplanationBuilder(context)
}
