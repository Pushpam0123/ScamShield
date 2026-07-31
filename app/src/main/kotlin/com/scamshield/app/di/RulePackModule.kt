package com.scamshield.app.di

import android.content.Context
import com.scamshield.core.analysis.ingest.MessageNormalizer
import com.scamshield.core.analysis.url.UrlExtractor
import com.scamshield.core.data.rulepack.AndroidAssetRulePackSource
import com.scamshield.core.data.rulepack.LoadedRulePack
import com.scamshield.core.data.rulepack.RulePackLoader
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * `implementation.md` Phase 1.10 / architecture.md section 11: loads the bundled rule pack
 * once, at first use, and shares it as a singleton -- every analyzer needs a view onto the
 * same pack. The files involved (roughly 50 KB of JSON plus a ~1.2 MB `reputation.bin`, per
 * the counts `build_rulepack.py` reports) are small enough that loading them synchronously on
 * whichever thread first requests this graph costs low-single-digit milliseconds in practice,
 * not the kind of blocking-I/O concern that would justify an async wrapper here.
 */
@Module
@InstallIn(SingletonComponent::class)
object RulePackModule {

    @Provides
    @Singleton
    fun provideLoadedRulePack(@ApplicationContext context: Context): LoadedRulePack =
        RulePackLoader(AndroidAssetRulePackSource(context)).load()

    @Provides
    @Singleton
    fun provideUrlExtractor(loadedRulePack: LoadedRulePack): UrlExtractor =
        UrlExtractor(loadedRulePack.publicSuffixList)

    @Provides
    @Singleton
    fun provideMessageNormalizer(urlExtractor: UrlExtractor, loadedRulePack: LoadedRulePack): MessageNormalizer =
        MessageNormalizer(urlExtractor, loadedRulePack.rulePack.banks)
}
