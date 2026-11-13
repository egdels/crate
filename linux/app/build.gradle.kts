// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

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

compose.desktop {
    application {
        mainClass = "de.schliweb.crate.linux.MainKt"
    }
}
