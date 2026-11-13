// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    id("org.jetbrains.compose") version "1.12.2" apply false
}

// :core's sources live in android/core; its build output stays here, so
// the two builds never share a build directory.
project(":core") {
    layout.buildDirectory = rootDir.resolve("build/core")
}
