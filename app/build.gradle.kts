import groovy.json.JsonSlurper
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

val verifyNewPipeDesugaring = tasks.register("verifyNewPipeDesugaring") {
    group = "verification"
    description = "Verify the reviewed API 29 NIO desugaring graph before APK preparation"
    val pins = rootProject.file("config/newpipe-dependencies.json")
    inputs.file(pins)
    inputs.files(configurations.named("coreLibraryDesugaring"))
    doLast {
        val document = JsonSlurper().parse(pins) as Map<*, *>
        val rows = (document["artifacts"] as List<*>).map { it as Map<*, *> }
            .filter { it["scope"] == "android-desugar" }.associateBy { it["coordinate"] as String }
        val resolved = configurations.getByName("coreLibraryDesugaring").resolvedConfiguration
        val graph = mutableSetOf<String>()
        fun visit(dependency: ResolvedDependency) {
            if (graph.add("${dependency.moduleGroup}:${dependency.moduleName}:${dependency.moduleVersion}"))
                dependency.children.forEach(::visit)
        }
        resolved.firstLevelModuleDependencies.forEach(::visit)
        check(graph == rows.keys) { "NIO desugaring dependency graph changed; review provenance, licenses and pins" }
        graph.forEach { coordinate ->
            val artifact = resolved.resolvedArtifacts.single { it.moduleVersion.id.toString() == coordinate && it.extension == "jar" }
            val expected = rows.getValue(coordinate)["binary"] as Map<*, *>
            val digest = MessageDigest.getInstance("SHA-256")
            artifact.file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            check(artifact.file.length() == (expected["bytes"] as Number).toLong() && actual == expected["sha256"]) {
                "NIO desugaring artifact changed; review provenance, license and pins: $coordinate"
            }
        }
    }
}
tasks.named("preBuild") { dependsOn(verifyNewPipeDesugaring) }

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

val choplabVersion = providers.gradleProperty("choplabVersion").orElse("0.0.0-dev")
val choplabBuildNumber = providers.gradleProperty("choplabBuildNumber")
    .map { value -> value.toInt() }
    .orElse(1)

val releaseStorePath = providers.environmentVariable("CHOPLAB_ANDROID_KEYSTORE").orNull
val releaseStorePassword = providers.environmentVariable("CHOPLAB_ANDROID_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("CHOPLAB_ANDROID_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("CHOPLAB_ANDROID_KEY_PASSWORD").orNull
val releaseSigningAvailable = listOf(
    releaseStorePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

val previewStorePath = providers.environmentVariable("CHOPLAB_PREVIEW_KEYSTORE").orNull
val previewStorePassword = providers.environmentVariable("CHOPLAB_PREVIEW_STORE_PASSWORD").orNull
val previewKeyAlias = providers.environmentVariable("CHOPLAB_PREVIEW_KEY_ALIAS").orNull
val previewKeyPassword = providers.environmentVariable("CHOPLAB_PREVIEW_KEY_PASSWORD").orNull
val previewSigningAvailable = listOf(previewStorePath, previewStorePassword, previewKeyAlias, previewKeyPassword)
    .all { !it.isNullOrBlank() }

android {
    namespace = "com.choplab.sampler"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.choplab.sampler"
        manifestPlaceholders["spotifyScheme"] = "choplab"
        minSdk = 29
        targetSdk = 36
        versionCode = choplabBuildNumber.get()
        versionName = choplabVersion.get()
        val spotifyClient = providers.environmentVariable("CHOPLAB_SPOTIFY_CLIENT_ID").orElse("").get()
        require(spotifyClient.isEmpty() || spotifyClient.matches(Regex("[A-Za-z0-9]{16,128}")))
        buildConfigField("String", "SPOTIFY_CLIENT_ID", "\"$spotifyClient\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (previewSigningAvailable) {
            create("preview") {
                storeFile = file(requireNotNull(previewStorePath))
                storePassword = requireNotNull(previewStorePassword)
                keyAlias = requireNotNull(previewKeyAlias)
                keyPassword = requireNotNull(previewKeyPassword)
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
        if (releaseSigningAvailable) {
            create("release") {
                storeFile = file(requireNotNull(releaseStorePath))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = false
            if (releaseSigningAvailable) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        create("preview") {
            initWith(getByName("release"))
            // Explicit local compatibility probe; this does not change the release size/signing gate.
            if (providers.gradleProperty("choplabNewPipeR8Probe").orNull == "true") {
                isMinifyEnabled = true
                proguardFile(rootProject.file("config/newpipe-r8-probe.pro"))
            }
            applicationIdSuffix = ".preview"
            versionNameSuffix = "-preview"
            isDebuggable = false
            matchingFallbacks += listOf("release")
            manifestPlaceholders["spotifyScheme"] = "choplab-preview"
            // Never inherit the production signer when preview credentials are absent.
            signingConfig = if (previewSigningAvailable) signingConfigs.getByName("preview") else null
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // おとひろい NEXT: the linked editor on the new core, as a second launcher entry in debug and
    // Preview builds only. The release APK keeps the existing app alone.
    sourceSets {
        for (buildType in listOf("debug", "preview")) getByName(buildType) {
            kotlin.srcDir("src/next/java")
            res.srcDir("src/next/res")
            manifest.srcFile("src/next/AndroidManifest.xml")
        }
        for (tests in listOf("testDebug", "testPreview")) getByName(tests) { kotlin.srcDir("src/testNext/java") }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs.useLegacyPackaging = true
        // Preserve the exact official AAR bytes audited by android_runtime_pins.json.
        // NDK strip availability must not change the distributed runtime identity.
        jniLibs.keepDebugSymbols += setOf(
            "**/libpython.so", "**/libpython.zip.so", "**/libqjs.so",
            "**/libffmpeg.so", "**/libffmpeg.zip.so", "**/libffprobe.so",
            "**/libonnxruntime.so", "**/libonnxruntime4j_jni.so",
        )
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

androidComponents {
    beforeVariants(selector().withBuildType("preview")) { variantBuilder ->
        // Test the release-like Preview without making its APK debuggable.
        variantBuilder.hostTests.getValue(com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE).enable = true
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.nio)
    for (configuration in listOf("debugImplementation", "previewImplementation")) {
        add(configuration, project(":jvm"))
        add(configuration, project(":ui"))
    }
    implementation(project(":shared"))
    implementation(project(":jvm-core"))
    implementation(libs.youtubedl.library)
    implementation(libs.youtubedl.ffmpeg)
    implementation(libs.onnxruntime.android)
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.core.ktx)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4.accessibility)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
