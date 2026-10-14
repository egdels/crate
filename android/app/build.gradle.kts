// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing comes from CI secrets, never from local defaults: unset
// locally, a plain `assembleRelease` builds an unsigned APK instead of
// failing.
val releaseKeystorePath: String? = System.getenv("RELEASE_KEYSTORE_PATH")
val releaseKeyAlias: String? = System.getenv("SIGNING_KEY_ALIAS")
val releaseKeyPassword: String? = System.getenv("SIGNING_KEY_PASSWORD")
val releaseStorePassword: String? = System.getenv("SIGNING_STORE_PASSWORD")
val hasReleaseSigning = !releaseKeystorePath.isNullOrEmpty() && !releaseKeyAlias.isNullOrEmpty() &&
    !releaseKeyPassword.isNullOrEmpty() && !releaseStorePassword.isNullOrEmpty()

// versionCode derived from the version in gradle.properties instead of a
// second number maintained by hand: "0.1.0" -> 100, "1.2.3" -> 10203.
val appVersion = providers.gradleProperty("appVersion").get()
val versionParts = appVersion.split('.').map { it.toInt() } + listOf(0, 0, 0)
val derivedVersionCode = versionParts[0] * 10000 + versionParts[1] * 100 + versionParts[2]

android {
    namespace = "de.schliweb.crate"
    compileSdk = 37

    defaultConfig {
        applicationId = "de.schliweb.crate"
        minSdk = 29
        targetSdk = 37
        versionCode = derivedVersionCode
        versionName = appVersion
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
    }

    lint {
        // UseKtx: Uri.parse and friends are fine without core-ktx.
        // ObsoleteSdkInt: the adaptive icon has to live in mipmap-anydpi-v26;
        // the resource merger drops a plain mipmap-anydpi folder.
        disable += setOf("UseKtx", "ObsoleteSdkInt")
    }
}

// The privacy policy is maintained once, as PRIVACY.md in the repository
// root; this copies it into the app's assets at build time so that the
// menu's Privacy Policy dialog shows the same text.
abstract class CopyPrivacyPolicyTask : DefaultTask() {
    @get:InputFiles
    abstract val sources: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun copy() {
        val target = outputDirectory.get().asFile
        target.deleteRecursively()  // no stale files from an earlier source list
        target.mkdirs()
        sources.files.forEach { it.copyTo(target.resolve(it.name), overwrite = true) }
    }
}

androidComponents {
    onVariants { variant ->
        val task = project.tasks.register<CopyPrivacyPolicyTask>(
            "copyPrivacyPolicy${variant.name.replaceFirstChar { it.uppercase() }}",
        ) {
            sources.from(rootDir.resolve("../PRIVACY.md"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(task, CopyPrivacyPolicyTask::outputDirectory)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.junit)
    debugImplementation(libs.compose.ui.tooling)
}
