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

rootProject.name = "HapticSync"

// Platform-independent engine (pure Kotlin/JVM, unit-testable without Android).
include(":core:dsp")       // FFT, filters, PCM conversion, signal synthesis
include(":core:model")     // Settings, presets, calibration profiles (the "settings module")
include(":core:analysis")  // Feature extraction, onset detection, sound classification
include(":core:haptics")   // Audio-to-haptic mapping, rendering strategies, timestamp scheduler
include(":core:engine")    // Real-time orchestration: PCM ingest, threads, visualization taps

// Android platform adapters.
include(":platform:playback")  // Media3 ExoPlayer + PTS-accurate PCM tap
include(":platform:vibration") // Vibrator / VibratorManager backend + capability probe

// Application (Jetpack Compose UI, MVVM).
include(":app")
