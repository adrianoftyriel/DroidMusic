// Intentionally empty of plugin declarations.
//
// The usual `plugins { alias(...) apply false }` block here forces every build,
// including one that only wants to run the pure-Kotlin tests, to resolve the
// Android Gradle Plugin before it can do anything. Declaring each plugin in the
// module that actually uses it keeps `./gradlew :core:music:test` working on a
// machine with no Android SDK installed at all, which is the point of having the
// core split out in the first place.

tasks.register("coreTests") {
    group = "verification"
    description = "Runs every pure-JVM core test. No Android SDK required."
    dependsOn(
        ":core:music:test",
        ":core:library:test",
        ":core:session:test",
        ":core:update:test",
    )
}
