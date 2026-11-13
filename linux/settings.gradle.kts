// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

// The Linux desktop app. Its own build, not part of the Android one, but
// with the same UI-free library: :core is android/core, compiled here
// from the same sources with the same version catalog.
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        google()
    }
    versionCatalogs {
        create("libs") {
            from(files("../android/gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "CrateLinux"
include(":core")
project(":core").projectDir = file("../android/core")
include(":app")
