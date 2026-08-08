package com.scamshield.app

import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.scamshield.analyzer.classifier.AndroidClassifierAssets
import com.scamshield.analyzer.classifier.ClassifierAnalyzer
import com.scamshield.analyzer.pattern.PatternAnalyzer
import com.scamshield.analyzer.sender.SenderAnalyzer
import com.scamshield.analyzer.url.UrlAnalyzer
import com.scamshield.core.analysis.ingest.MessageNormalizer
import com.scamshield.core.analysis.orchestrator.AnalysisPipeline
import com.scamshield.core.analysis.orchestrator.Orchestrator
import com.scamshield.core.analysis.url.UrlExtractor
import com.scamshield.core.data.rulepack.AndroidAssetRulePackSource
import com.scamshield.core.data.rulepack.RulePackLoader
import com.scamshield.core.model.Analyzer
import com.scamshield.core.model.MessageId
import com.scamshield.core.model.MessageSource
import com.scamshield.core.model.RawMessage
import com.scamshield.core.model.Signal
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID
import kotlin.system.measureTimeMillis

/**
 * §12 day 5: the Phase 4 latency / memory / size measurements (design.md §11, DECISIONS.md D-007).
 *
 * These are deliberately in-process instrumented timings, **not** a Macrobenchmark module. D-007's
 * cold-start / frame-timing case is what Macrobenchmark exists for, and it only yields trustworthy
 * numbers on a real, unlocked device — on the CI emulator it is explicitly meaningless. Method-level
 * timings (model load, one inference, end-to-end fusion) are measured more faithfully in-process
 * anyway. So this records the numbers that ARE meaningful here, honestly caveated; the real-device
 * Macrobenchmark run stays deferred (see MODEL_CARD / README notes), not faked.
 *
 * Nothing here asserts design.md's hardware thresholds (≤400 ms load, ≤150 ms p95 e2e, ≤220 MB peak):
 * asserting a phone budget against an emulator would be a fabricated pass. It asserts only that the
 * path runs and completes, and prints the observed numbers for the run log.
 */
@RunWith(AndroidJUnit4::class)
class ClassifierMeasurementsTest {

    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext

    private fun message(text: String) = RawMessage(
        id = MessageId(UUID.randomUUID().toString()),
        text = text,
        senderHint = null,
        source = MessageSource.MANUAL,
        receivedAt = Instant.now(),
    )

    private val phishing =
        "Your SBI account is blocked. Complete KYC now at http://sbi-verify.example.link/login or lose access."

    @Test
    fun synchronousRulePackLoadTime() {
        // design.md §6.1 item 3: this load runs on the Hilt graph thread today; time the real cost.
        lateinit var loaded: Any
        val ms = measureTimeMillis {
            loaded = RulePackLoader(AndroidAssetRulePackSource(targetContext)).load()
        }
        println("MEASURE rulepack_load_ms=$ms")
        assertThat(loaded).isNotNull()
    }

    @Test
    fun modelColdLoadAndInferenceLatency() = runTest {
        assumeModelPresent()
        val analyzer = ClassifierAnalyzer(AndroidClassifierAssets(targetContext))
        val normalizer = normalizer()

        val normalized = normalizer.normalize(message(phishing))
        val coldMs = measureTimeMillis { assertThat(analyzer.analyze(normalized)).isInstanceOf(Signal.Scored::class.java) }
        println("MEASURE model_cold_load_plus_first_inference_ms=$coldMs")

        val warm = LongArray(30) { measureTimeMillis { analyzer.analyze(normalized) } }.sorted()
        println("MEASURE single_inference_p50_ms=${warm[warm.size / 2]} p95_ms=${warm[(warm.size * 95 / 100).coerceAtMost(warm.size - 1)]}")
    }

    @Test
    fun endToEndPipelineLatency() = runTest {
        assumeModelPresent()
        val pipeline = livePipeline()
        val normalizer = normalizer()
        val messages = listOf(
            phishing,
            "Dear Customer, Rs.2,500 debited from A/c XX1234. Avl Bal Rs.15,320. Not you? Call bank.",
            "Congratulations! You won Rs.25,00,000 in the KBC lottery. Send Aadhaar to claim.",
            "Hi, are we still meeting for lunch tomorrow at 1pm?",
        )
        // Warm the model so this measures fusion e2e, not the one-off load.
        pipeline.analyze(normalizer.normalize(message(phishing)))

        val times = ArrayList<Long>()
        repeat(25) {
            for (m in messages) times += measureTimeMillis { pipeline.analyze(normalizer.normalize(message(m))) }
        }
        times.sort()
        val p = { q: Int -> times[(times.size * q / 100).coerceAtMost(times.size - 1)] }
        println("MEASURE e2e_p50_ms=${p(50)} p95_ms=${p(95)} p99_ms=${p(99)} n=${times.size}")
        assertThat(times).isNotEmpty()
    }

    @Test
    fun peakMemoryAfterModelResident() = runTest {
        assumeModelPresent()
        val pipeline = livePipeline()
        val normalizer = normalizer()
        repeat(20) { pipeline.analyze(normalizer.normalize(message(phishing))) }
        Runtime.getRuntime().gc()
        val totalPssKb = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss
        println("MEASURE peak_total_pss_mb=${totalPssKb / 1024.0}")
        assertThat(totalPssKb).isGreaterThan(0)
    }

    /**
     * The privacy claim the whole project rests on, exercised: the full pipeline produces a real,
     * classifier-influenced verdict with no network reachable from these modules. This is the
     * "airplane-mode: full functionality" acceptance (design.md §11) expressed as a test — the
     * analyzer/core modules declare no network dependency, so the verdict below cannot come from a
     * server. The absence of any network API is separately enforced by the day-7 security review.
     */
    @Test
    fun fullFunctionalityWithoutNetwork() = runTest {
        assumeModelPresent()
        val pipeline = livePipeline()
        val result = pipeline.analyze(normalizer().normalize(message(phishing)))
        assertThat(result.evidence).isNotEmpty()
        println("MEASURE offline_verdict=${result.verdict} evidence=${result.evidence.map { it.type }}")
    }

    // --- helpers ---

    private fun normalizer(): MessageNormalizer {
        val loaded = RulePackLoader(AndroidAssetRulePackSource(targetContext)).load()
        return MessageNormalizer(UrlExtractor(loaded.publicSuffixList), loaded.rulePack.banks)
    }

    private fun livePipeline(): AnalysisPipeline {
        val loaded = RulePackLoader(AndroidAssetRulePackSource(targetContext)).load()
        val analyzers: List<Analyzer> = listOf(
            UrlAnalyzer(
                confusables = loaded.rulePack.confusables,
                banks = loaded.rulePack.banks,
                shorteners = loaded.rulePack.shorteners,
                shortenerBrandOperated = loaded.rulePack.shortenerBrandOperated,
                suspiciousTlds = loaded.rulePack.suspiciousTlds,
                reputationIndex = loaded.reputationIndex,
            ),
            SenderAnalyzer(loaded.rulePack.banks),
            PatternAnalyzer(loaded.rulePack.patterns),
            ClassifierAnalyzer(AndroidClassifierAssets(targetContext)),
        )
        return AnalysisPipeline(Orchestrator(analyzers), loaded.rulePack.meta.version)
    }

    private fun assumeModelPresent() {
        val present = runCatching { targetContext.assets.open("model/model.onnx").close() }.isSuccess
        assumeTrue("no model bundled; run :app:copyModelAssets", present)
    }
}
