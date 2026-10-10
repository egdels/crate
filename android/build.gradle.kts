// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

// AGP 9 brings its own Kotlin support; the Kotlin plugins are listed here
// so the whole build (including :app's built-in Kotlin) compiles with one
// Kotlin version, the one the Compose compiler plugin below matches.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

subprojects {
    // CI only has the console log at hand, so a failing test must print its
    // full stack trace there rather than Gradle's default one-liner.
    tasks.withType<Test>().configureEach {
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}
