// android/quiet-spike/settings.gradle.kts
//
// Single-module Gradle build for the SPIKE-01 Android target.
// Module name 'app' resolves to project path :app; the CI command
// `./gradlew :app:assembleDebug` lives in .github/workflows/ci.yml.

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

rootProject.name = "quiet-spike"
include(":app")
