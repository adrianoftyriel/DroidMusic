plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Deliberately no Android dependency. Everything in here is text in, text out,
// which is what lets the whole music-theory suite run on a plain JVM in CI as a
// gate before any device toolchain is even installed.
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
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach { useJUnit() }
