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

// D-007 planned a `:benchmark` Macrobenchmark module for Phase 4. Deferred: macrobenchmark numbers
// are only trustworthy on a real device (not the CI emulator), and the method-level costs we care
// about are measured in-process by :app's ClassifierMeasurementsTest. Re-add with a real device.
// include(":benchmark")
