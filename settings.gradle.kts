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

rootProject.name = "DroidMusic"

// The three core modules are plain Kotlin/JVM on purpose - see docs/DESIGN.md.
// They hold everything that can be reasoned about without a device, which means
// their tests run in seconds on any machine and gate the Android build in CI.
include(":core:music")
include(":core:library")
include(":core:session")
include(":app")
