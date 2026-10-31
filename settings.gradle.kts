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
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "LiveHub"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

// UI shell
include(":app")

// Shared, platform agnostic building blocks (plain JVM: fast to unit test)
include(":core:common")
include(":core:model")
include(":core:network")
include(":core:data")
include(":core:sync")

// Android only building blocks
include(":core:designsystem")

// Streaming platform integrations
include(":platform-api")
include(":platform:douyu")
include(":platform:huya")
include(":platform:douyin")
include(":platform:bilibili")
