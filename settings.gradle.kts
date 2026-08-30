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
include(":core:update")

// `-PcoreOnly` leaves the Android module out of the build entirely, so the core
// tests can be run on a machine with no Android SDK - which is most machines,
// and every reviewer's first five minutes with the repository.
//
// Explicitly opt-in rather than auto-detected: a build that quietly skips the
// app because it could not find an SDK would let a broken app module through CI
// unnoticed, which is a far worse failure than an error message telling someone
// to install the SDK.
val coreOnly = providers.gradleProperty("coreOnly").isPresent
if (!coreOnly) {
    include(":app")
}
