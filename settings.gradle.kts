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

// :benchmark is introduced in Phase 4, where there is a model to measure and a `benchmark`
// build type for it to target. Including an empty com.android.test module before then only
// adds a variant that cannot be assembled. See DECISIONS.md D-007.
// include(":benchmark")
