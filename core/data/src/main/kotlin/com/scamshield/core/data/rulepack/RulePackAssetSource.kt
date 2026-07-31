package com.scamshield.core.data.rulepack

import android.content.Context
import java.io.IOException

/**
 * The raw bytes/text of one rule-pack file, abstracted away from `Context.assets` so
 * [RulePackLoader] is unit-testable with an in-memory fake and never needs Robolectric.
 *
 * Public: `:app` constructs [AndroidAssetRulePackSource] itself (it owns the `Context` at
 * Hilt-wiring time) and hands it to [RulePackLoader].
 */
interface RulePackAssetSource {
    /** @throws IOException if [fileName] cannot be read. */
    fun readText(fileName: String): String

    /** @throws IOException if [fileName] cannot be read. */
    fun readBytes(fileName: String): ByteArray
}

/**
 * Reads from `assets/rulepack/v1/`, the directory `build_rulepack.py` emits to inside `:app`
 * (`architecture.md` section 11). `:core:data` has no assets of its own -- this reads through
 * whatever `Context` the caller supplies, which in the real app is `:app`'s own merged assets.
 */
class AndroidAssetRulePackSource(
    private val context: Context,
    private val packDir: String = "rulepack/v1",
) : RulePackAssetSource {

    override fun readText(fileName: String): String =
        context.assets.open("$packDir/$fileName").use { it.readBytes().toString(Charsets.UTF_8) }

    override fun readBytes(fileName: String): ByteArray =
        context.assets.open("$packDir/$fileName").use { it.readBytes() }
}
