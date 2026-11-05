// Top-level build file. Plugins are declared here (not applied) so that every module shares
// a single classloader for the Android Gradle Plugin and the Kotlin Gradle Plugin.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

/**
 * Runs the unit tests of every pure-Kotlin engine module. These tests need no Android SDK,
 * device or emulator: `./gradlew coreTest`.
 */
tasks.register("coreTest") {
    group = "verification"
    description = "Runs the JVM unit and integration tests of the platform-independent engine modules."
    dependsOn(
        ":core:dsp:test",
        ":core:model:test",
        ":core:analysis:test",
        ":core:haptics:test",
        ":core:engine:test",
    )
}
