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

include(":benchmark")
