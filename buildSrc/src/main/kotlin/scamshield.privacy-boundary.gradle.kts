import org.gradle.api.artifacts.result.ResolvedComponentResult

/**
 * Enforces `architecture.md` §10.1 at build time:
 *
 * > ":core:model and all analyzer modules have no network dependency declared. Only
 * > :core:data may declare one. A build-time dependency check enforces this."
 *
 * The check walks the module's fully *resolved* runtime classpath rather than its declared
 * dependencies, so a network library pulled in three levels down by something innocuous still
 * fails the build. That matters: the privacy promise is the one claim the whole project rests
 * on (`implementation.md` Phase 6), and a promise enforced by code review is not enforced.
 *
 * Apply to any module that must stay off the network and off Android:
 * ```
 * plugins {
 *     alias(libs.plugins.kotlin.jvm)
 *     id("scamshield.privacy-boundary")
 * }
 * ```
 */

/** Group prefixes that indicate an HTTP client, a socket library, or a telemetry SDK. */
val forbiddenNetworkGroups = listOf(
    "com.squareup.okhttp3",
    "com.squareup.retrofit2",
    "io.ktor",
    "org.apache.httpcomponents",
    "com.google.firebase",
    "com.google.android.gms",
    "io.grpc",
    "com.google.api",
)

/**
 * Android artifacts. A rule analyzer that reaches for `android.*` is also a rule analyzer that
 * can no longer be exercised by the eval harness on a plain JVM, which is why this is checked
 * in the same place.
 */
val forbiddenAndroidGroups = listOf(
    "androidx.",
    "com.android.",
    "com.google.android.material",
)

val checkPrivacyBoundary = tasks.register("checkPrivacyBoundary") {
    group = "verification"
    description = "Fails if this module resolves any Android or network dependency (architecture.md §10.1)"

    val modulePath = project.path
    val rootComponent = configurations.named("runtimeClasspath")
        .flatMap { it.incoming.resolutionResult.rootComponent }
    val networkGroups = forbiddenNetworkGroups
    val androidGroups = forbiddenAndroidGroups

    // Captured as a Provider so resolution happens at execution time and the task stays
    // compatible with the configuration cache.
    inputs.property("modulePath", modulePath)

    doLast {
        val violations = linkedMapOf<String, String>()
        val visited = mutableSetOf<String>()

        fun walk(component: ResolvedComponentResult) {
            val id = component.id.displayName
            if (!visited.add(id)) return

            networkGroups.firstOrNull { id.startsWith(it) }?.let {
                violations[id] = "network library"
            }
            androidGroups.firstOrNull { id.startsWith(it) }?.let {
                violations[id] = "Android dependency"
            }

            component.dependencies
                .filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>()
                .forEach { walk(it.selected) }
        }

        walk(rootComponent.get())

        if (violations.isNotEmpty()) {
            val detail = violations.entries.joinToString("\n") { (id, kind) -> "  - $id  ($kind)" }
            throw GradleException(
                """
                |$modulePath violates the privacy boundary of architecture.md §10.1.
                |
                |The following resolved onto its runtime classpath:
                |$detail
                |
                |Only :core:data may declare a network dependency. If this module genuinely
                |needs data from the network or from Android storage, it must receive it
                |through an interface defined in :core:model and injected at the :app layer —
                |it must not fetch it itself.
                """.trimMargin(),
            )
        }
    }
}

tasks.named("check") {
    dependsOn(checkPrivacyBoundary)
}
