pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "ScamShield"

// architecture.md §5 — component structure. Dependencies point inward toward :core:model.
include(":app")

include(":core:model")
include(":core:analysis")
include(":core:explain")
include(":core:data")

include(":analyzer:url")
include(":analyzer:sender")
include(":analyzer:pattern")
include(":analyzer:classifier")

// D-007 planned a `:benchmark` Macrobenchmark module for Phase 4. Phase 4 revisited that: the only
// device available here is the CI emulator, where Macrobenchmark's numbers (cold start, frame/jank,
// peak memory) are explicitly untrustworthy, and the method-level costs we actually care about —
// model load, one inference, end-to-end fusion — are measured more faithfully in-process. So those
// live in :app's ClassifierMeasurementsTest (instrumented, honestly caveated) and the real-device
// Macrobenchmark run is deferred rather than faked on an emulator. Re-add this module when a real
// device (or Firebase Test Lab) is in the loop.
// include(":benchmark")
