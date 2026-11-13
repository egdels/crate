// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    id("org.jetbrains.compose")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.core)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:${libs.versions.coroutines.get()}")
    // GStreamer through JNA: the system's libgstreamer plays, decodes and
    // reads what the own tag parsers do not.
    implementation("org.freedesktop.gstreamer:gst1-java-core:1.4.0")
}

// One version for every platform: the Android build's appVersion, which
// the release workflow checks against the tag.
val appVersion: String = rootDir.resolve("../android/gradle.properties").readLines()
    .first { it.startsWith("appVersion=") }.substringAfter('=').trim()

compose.desktop {
    application {
        mainClass = "de.schliweb.crate.linux.MainKt"
        // Main names the window class after the menu entry (see windowClass there).
        jvmArgs += "--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED"
        nativeDistributions {
            // Gradle makes the plain folder (createDistributable) and the
            // single jar (packageUberJarForCurrentOS); every package is
            // made from the folder by scripts/linux-packages.sh, which
            // calls jpackage itself for the .deb and the .rpm: this DSL
            // has no field for a package's dependencies, and they need
            // GStreamer. The format here only switches packaging on.
            targetFormats(TargetFormat.Deb)
            // "Crate" is what the menu shows and the launcher is called.
            packageName = "Crate"
            packageVersion = appVersion
            // Swing/Compose, JNA (GStreamer) and the logging gst1-java-core uses.
            modules("java.desktop", "java.logging", "jdk.unsupported")
            linux {
                iconFile.set(rootDir.resolve("../Packaging/AppIcon-256.png"))
            }
        }
    }
}
