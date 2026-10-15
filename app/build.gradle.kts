/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.aboutlibraries)
}
val libphonenumberMetadataDir = layout.buildDirectory.dir("generated/libphonenumber/assets")
val libphonenumberArtifact by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

// Detect if we're running in a CI environment (e.g., GitHub Actions).
val isEnvironmentGithubCI = providers.environmentVariable("GITHUB_ACTIONS").isPresent

// Detect if we want to bypass signing checks
val shouldSkipSigning = providers.environmentVariable("SKIP_SIGNING").orNull?.toBoolean() ?: false

abstract class ExtractMetadataTask : Sync() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty
}

val extractLibphonenumberMetadata = tasks.register<ExtractMetadataTask>("extractLibphonenumberMetadata") {
    val jarFiles = libphonenumberArtifact

    from(jarFiles.elements.map { files -> files.map { zipTree(it) } }) {
        include("com/google/i18n/phonenumbers/data/**")
        eachFile {
            relativePath = RelativePath(true, "phonenumber_data", name)
        }
        includeEmptyDirs = false
    }
    outputDir.set(libphonenumberMetadataDir)
    into(outputDir)
}

tasks.register("writeVersionForCi") {
    // Define the output file path (e.g., app/build/outputs/version.txt)
    val outputFile = layout.buildDirectory.file("outputs/version.txt")
    outputs.file(outputFile)

    doLast {
        val file = outputFile.get().asFile
        file.parentFile.mkdirs() // Ensure the outputs directory exists
        file.writeText(android.defaultConfig.versionName!!)
        logger.lifecycle("Successfully wrote versionName to ${file.absolutePath}")
    }
}

android {
    namespace = "com.kitsumed.shizucallrecorder"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kitsumed.shizucallrecorder"
        minSdk = 30
        targetSdk = 36
        // Keep theses two values hard-coded here and update them per version. (To keep F-Droid compatibility since their parser is very basic)
        versionCode = 19
        versionName = "1.3.3"
    }
    signingConfigs {
        // Signing config for CI environments.
        create("ci-release") {
            if (isEnvironmentGithubCI && !shouldSkipSigning) {
                storeFile = file(System.getenv("KEYSTORE_FILE") ?: throw GradleException("Keystore file not provided for release signing. env variable: KEYSTORE_FILE"))
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: throw GradleException("Keystore password not provided for release signing. env variable: KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS") ?: throw GradleException("Key alias not provided for release signing. env variable: KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD") ?:throw GradleException("Key password not provided for release signing. env variable: KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (isEnvironmentGithubCI) {
                logger.lifecycle("Configuring release build for CI environment. Official release signing keys will be used.")
                if (shouldSkipSigning) {
                    logger.warn("WARNING: SKIP_SIGNING is set. The build will NOT be signed, which may cause installation to fail on Android Devices. Do not set SKIP_SIGNING when BUILDING the apk! You can for other tasks.")
                } else
                {
                    signingConfig = signingConfigs.getByName("ci-release")
                }
            }
        }
    }
    compileOptions {
        sourceCompatibility =  JavaVersion.VERSION_17
        targetCompatibility =  JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }
    packaging {
        // Exclude the original metadata from libphonenumber to avoid conflicts with our extracted version. This ensures only our processed assets are included in the final APK.
        resources.excludes.add("com/google/i18n/phonenumbers/data/**")
    }
    androidResources {
        generateLocaleConfig = true
    }
    dependenciesInfo {
        // Disables dependency metadata when building APKs (for IzzyOnDroid/F-Droid)
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles (for Google Play)
        includeInBundle = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            extractLibphonenumberMetadata,
            ExtractMetadataTask::outputDir
        )
    }
}

aboutLibraries {
    // Gradle sync runs in the Task :app:prepareLibraryDefinitionsDebug and :app:prepareLibraryDefinitionsRelease.
    collect {
        // Define the path configuration files are located in. E.g. additional libraries, licenses to add to the target .json
        // Warning: Please do not use the parent folder of a module as path, as this can result in issues. More details: https://github.com/mikepenz/AboutLibraries/issues/936
        // The path provided is relative to the modules path (not project root)
        configPath = file("../aboutLibrariesConfig")

        // Enable fetching of "remote" licenses.  Uses the API of supported source hosts
        // See https://github.com/mikepenz/AboutLibraries#special-repository-support
        // A `gitHubApiToken` is required for this to work as it fetches information from GitHub's API.
        fetchRemoteLicense = false

        // Enables fetching of "remote" funding information. Uses the API of supported source hosts
        // See https://github.com/mikepenz/AboutLibraries#special-repository-support
        // A `gitHubApiToken` is required for this to work as it fetches information from GitHub's API.
        fetchRemoteFunding = false

    }
    library {
        // Enable the duplication mode, allows to merge, or link dependencies which relate
        duplicationMode = com.mikepenz.aboutlibraries.plugin.DuplicateMode.MERGE
        // Configure the duplication rule, to match "duplicates" with
        // We merge when groupId and license are equal
        duplicationRule = com.mikepenz.aboutlibraries.plugin.DuplicateRule.GROUP
    }
}

dependencies {
    // AndroidX Core & Lifecycle
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.appcompat)

    // Compose Core
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // Compose Tooling
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // AboutLibraries
    implementation(libs.aboutlibraries.core)
    implementation(libs.aboutlibraries.compose.m3)

    // Libphonenumber
    implementation(libs.libphonenumber)
    libphonenumberArtifact(libs.libphonenumber)

    // Shizuku
    implementation(libs.shizukuApi)
    implementation(libs.shizukuProvider)

    /*
     * Local Hidden Stubs for Android Framework (IContentProvider, etc)
     * This is a compile-time only dependency to satisfy the compiler for hidden Android framework types.
     * At runtime, the Android classloader prioritizes real framework classes of the OS, these stubs will not be part of the final APK.
     */
    compileOnly(project(":android-framework-hidden-stubs"))
}
