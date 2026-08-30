import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// The version comes from gradle.properties, which is also what the release
// workflow reads. Nothing here invents a version of its own.
val versionNameValue: String = providers.gradleProperty("droidmusic.versionName").get()
val versionCodeValue: Int = providers.gradleProperty("droidmusic.versionCode").get().toInt()

/**
 * The release tag this APK belongs to, stamped in by CI.
 *
 * Deliberately *not* the version name, and that distinction is the whole reason
 * it exists. Every pre-release built from `dev` carries versionName 0.1.0 -
 * only the tag carries the run number - so a build that knew nothing but its
 * version name could never tell one pre-release from another, and the in-app
 * updater would report "up to date" to everybody forever.
 *
 * A local build has no tag, and says so. Inventing `v0.1.0` for it would make
 * every laptop build claim to be the release of that name.
 */
val releaseTagValue: String = providers.gradleProperty("droidmusic.releaseTag")
    .orNull.orEmpty().trim()

/**
 * Where the updater looks for releases. A fork that publishes its own builds
 * sets this and its APKs update from its own repository rather than from here.
 */
val updateRepositoryValue: String = providers.gradleProperty("droidmusic.updateRepository")
    .orNull.orEmpty().trim().ifEmpty { "adrianoftyriel/DroidMusic" }

/**
 * Release signing, if this build has a keystore to sign with.
 *
 * CI writes `keystore.properties` from repository secrets when they are set. A
 * fork, or a clone with no secrets configured, has none - and the right
 * behaviour there is a release build that still completes, signed with the debug
 * key and clearly labelled as such, rather than a build that fails. An unsigned
 * release APK nobody can install is not more secure than a debug-signed one, it
 * is just less useful.
 */
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        FileInputStream(keystorePropertiesFile).use { load(it) }
    }
}
val hasReleaseKeystore = keystoreProperties.containsKey("storeFile")

android {
    namespace = "org.droidmusic.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.droidmusic.app"
        // 26 is where the platform PDF renderer, the Bluetooth stack and the
        // storage framework all behave consistently enough not to need parallel
        // code paths. Below that the app would be mostly workarounds.
        minSdk = 26
        targetSdk = 35
        versionCode = versionCodeValue
        versionName = versionNameValue
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resourceConfigurations += listOf("en")

        buildConfigField("String", "RELEASE_TAG", "\"$releaseTagValue\"")
        buildConfigField("String", "UPDATE_REPOSITORY", "\"$updateRepositoryValue\"")
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
        )
    }

    lint {
        warningsAsErrors = false

        // Lint errors do fail the build. It has already caught a backup rule
        // that named a domain that does not exist and an intent filter that
        // claimed URLs the app cannot open - both invisible until a user hit
        // them - so it is earning the interruption.
        abortOnError = true

        // A machine-readable report, so CI can print the errors rather than the
        // several hundred lines of explanatory prose lint writes to stdout.
        textReport = true
        textOutput = file("build/reports/lint-results-release.txt")

        disable += setOf(
            "MissingTranslation",
            "UnusedResources",
            // "A newer version of X is available". True of every pinned
            // dependency the moment it is pinned, and not something to be told
            // on every build; version bumps are a deliberate act here.
            "GradleDependency",
            "AndroidGradlePluginVersion",
            "OldTargetApi",
        )
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:music"))
    implementation(project(":core:library"))
    implementation(project(":core:session"))
    implementation(project(":core:update"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
