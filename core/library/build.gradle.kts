plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Java 17 bytecode, but without pinning a toolchain. Pinning would demand a JDK
// 17 be installed even when a newer one is present and perfectly capable, which
// would defeat the "runs anywhere" property these modules exist to have.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    api(project(":core:music"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach { useJUnit() }

/**
 * Converts a saved chart page and prints what came out of it:
 *
 *     ./gradlew -PcoreOnly :core:library:convertPage -Ppage=learn-to-fly.html
 *
 * A shortcut for working on the importer, not part of the build. The pages it
 * reads are deliberately not in the repository - they are other people's
 * transcriptions of other people's songs - so this takes one saved with `curl`
 * and turns "does that chart import?" into one command with no APK to build.
 */
tasks.register<JavaExec>("convertPage") {
    group = "verification"
    description = "Converts a saved chart page to ChordPro and reports on it."
    mainClass.set("org.droidmusic.library.ConvertPageKt")
    classpath = sourceSets["test"].runtimeClasspath
    argumentProviders.add {
        listOfNotNull(
            providers.gradleProperty("page").orNull,
            providers.gradleProperty("url").orNull,
        )
    }
}
