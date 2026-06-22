/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.aboutlibraries)
}

val scrcpyVersion = "4.0"
val scrcpyServerUrl = "https://github.com/Genymobile/scrcpy/releases/download/v$scrcpyVersion/scrcpy-server-v$scrcpyVersion"
val scrcpyServerSha256 = "84924bd564a1eb6089c872c7521f968058977f91f5ff02514a8c74aff3210f3a"
val scrcpyServerAssetName = "scrcpy-server"
val scrcpyDownloadDir = layout.buildDirectory.dir("generated/scrcpy/assets")
val scrcpyServerAssetFile = scrcpyDownloadDir.map { it.file(scrcpyServerAssetName) }
val libphonenumberMetadataDir = layout.buildDirectory.dir("generated/libphonenumber/assets")

// Detect if we're running in a CI environment (e.g., GitHub Actions).
val isEnvironmentGithubCI = providers.environmentVariable("GITHUB_ACTIONS").isPresent

// Detect if we want to bypass signing checks
val shouldSkipSigning = providers.environmentVariable("SKIP_SIGNING").orNull?.toBoolean() ?: false

abstract class DownloadAssetTask : DefaultTask() {
    @get:Input
    abstract val url: Property<String>

    @get:Input
    abstract val sha256: Property<String>

    @get:Input
    abstract val assetName: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun download() {
        val targetFile = outputDir.get().file(assetName.get()).asFile

        // Internal check to skip if already correct
        if (targetFile.exists() && calculateSha256(targetFile).equals(sha256.get(), ignoreCase = true)) {
            logger.info("${assetName.get()} is already up-to-date.")
            return
        }

        targetFile.parentFile.mkdirs()
        logger.lifecycle("Downloading ${assetName.get()}...")

        URI(url.get()).toURL().openStream().use { input ->
            targetFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        val actualHash = calculateSha256(targetFile)
        if (!actualHash.equals(sha256.get(), ignoreCase = true)) {
            targetFile.delete()
            throw GradleException("SHA256 mismatch! Expected ${sha256.get()} but got $actualHash")
        }
    }

    private fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead = input.read(buffer)
            while (bytesRead != -1) {
                digest.update(buffer, 0, bytesRead)
                bytesRead = input.read(buffer)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
abstract class ExtractMetadataTask : Sync() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty
}

val downloadScrcpyServer = tasks.register<DownloadAssetTask>("downloadScrcpyServer") {
    url.set(scrcpyServerUrl)
    sha256.set(scrcpyServerSha256)
    assetName.set(scrcpyServerAssetName)
    outputDir.set(scrcpyDownloadDir)
}

val extractLibphonenumberMetadata = tasks.register<ExtractMetadataTask>("extractLibphonenumberMetadata") {
    val lib = libs.libphonenumber.get()
    val jarFile = project.configurations
        .detachedConfiguration(project.dependencies.create(lib))
        .singleFile

    from(zipTree(jarFile)) {
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

val ciBuildNumber = providers.gradleProperty("ciBuildNumber").getOrNull() ?: run {
    logger.lifecycle("[INFO] 'ciBuildNumber' not defined. Defaulting to 'Local'")
    "Local"
}

android {
    namespace = "com.kitsumed.shizucallrecorder"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kitsumed.shizucallrecorder"
        minSdk = 30
        targetSdk = 36
        // Keep theses two values hard-coded here and update them per version. (To keep F-Droid compatibility since their parser is very basic)
        versionCode = 13
        versionName = "1.1.0"

        buildConfigField("String", "CI_BUILD_NUMBER", "\"${ciBuildNumber}\"")

        buildConfigField("String", "SCRCPY_VERSION", "\"$scrcpyVersion\"")
        buildConfigField("String", "SCRCPY_SERVER_SHA256", "\"$scrcpyServerSha256\"")
        buildConfigField("String", "SCRCPY_SERVER_ASSET_NAME", "\"$scrcpyServerAssetName\"")
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
            downloadScrcpyServer,
            DownloadAssetTask::outputDir
        )

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

    // Shizuku
    implementation(libs.shizukuApi)
    implementation(libs.shizukuProvider)
}
