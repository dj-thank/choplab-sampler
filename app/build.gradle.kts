import groovy.json.JsonSlurper
import java.security.MessageDigest
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.transform.TransformParameters
import org.gradle.api.attributes.Attribute

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

// Explicit candidate only until the same arm64 bytes pass target codec/runtime
// acceptance. This never changes release signing or the default upstream AAR.
val androidAudioRuntimeCandidate = providers.gradleProperty("choplabAndroidAudioRuntime").orNull?.let(rootProject::file)
val androidPythonRuntimeCandidate = providers.gradleProperty("choplabAndroidPythonRuntime").orNull?.let(rootProject::file)
check(androidPythonRuntimeCandidate == null || androidAudioRuntimeCandidate != null) {
    "The Python linkage candidate must be paired with the reviewed audio FFmpeg candidate"
}

// Transform the one fixed AAR, retaining its original module identity and
// transitive metadata. No replacement Maven coordinate or dependency downgrade.
abstract class AndroidPythonLinkageTransform : TransformAction<AndroidPythonLinkageTransform.Parameters> {
    interface Parameters : TransformParameters {
        @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
        val candidate: RegularFileProperty
        @get:Input val upstreamSha256: Property<String>
        @get:Input val derivedSha256: Property<String>
        @get:Input val derivedBytes: Property<Long>
    }
    @get:InputArtifact @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val inputArtifact: Provider<FileSystemLocation>
    override fun transform(outputs: TransformOutputs) {
        val original = inputArtifact.get().asFile
        if (original.name != "library-0.18.1.aar") { outputs.file(original); return }
        fun sha(file: File): String = MessageDigest.getInstance("SHA-256").let { digest ->
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            }
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        check(sha(original) == parameters.upstreamSha256.get()) { "Python runtime upstream identity changed" }
        val candidate = parameters.candidate.get().asFile
        check(candidate.length() == parameters.derivedBytes.get() && sha(candidate) == parameters.derivedSha256.get()) {
            "Python linkage candidate differs from the reviewed alias-only derivation"
        }
        candidate.copyTo(outputs.file("library-0.18.1.aar"), overwrite = true)
    }
}
if (androidPythonRuntimeCandidate != null) {
    val profile = JsonSlurper().parse(rootProject.file("config/android-ffmpeg-audio.json")) as Map<*, *>
    val linkage = profile["pythonLinkage"] as Map<*, *>
    val original = linkage["upstream"] as Map<*, *>
    val derived = linkage["aar"] as Map<*, *>
    val isolated = Attribute.of("com.choplab.android-python-linkage", Boolean::class.javaObjectType)
    val artifactType = Attribute.of("artifactType", String::class.java)
    dependencies {
        attributesSchema { attribute(isolated) }
        artifactTypes.maybeCreate("aar").attributes.attribute(isolated, false)
        registerTransform(AndroidPythonLinkageTransform::class) {
            from.attribute(isolated, false).attribute(artifactType, "aar")
            to.attribute(isolated, true).attribute(artifactType, "aar")
            parameters {
                candidate.set(androidPythonRuntimeCandidate)
                upstreamSha256.set(original["sha256"] as String)
                derivedSha256.set(derived["sha256"] as String)
                derivedBytes.set((derived["bytes"] as Number).toLong())
            }
        }
    }
    // A consumer requests the transformed AAR. Do not add the same custom
    // attribute to outgoing archives/SBOM variants: Gradle requires each
    // consumable variant to retain its distinct identity.
    configurations.configureEach { if (isCanBeResolved) attributes.attribute(isolated, true) }
}
if (androidAudioRuntimeCandidate != null) {
    val verifyAndroidAudioRuntime = tasks.register("verifyAndroidAudioRuntime") {
        val profile = rootProject.file("config/android-ffmpeg-audio.json")
        inputs.file(profile)
        inputs.file(androidAudioRuntimeCandidate)
        doLast {
            val document = JsonSlurper().parse(profile) as Map<*, *>
            val expected = (document["derived"] as Map<*, *>)["aar"] as Map<*, *>
            val digest = MessageDigest.getInstance("SHA-256")
            androidAudioRuntimeCandidate.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            check(androidAudioRuntimeCandidate.length() == (expected["bytes"] as Number).toLong() && actual == expected["sha256"]) {
                "Android audio runtime candidate differs from the reviewed source build"
            }
        }
    }
    tasks.named("preBuild") { dependsOn(verifyAndroidAudioRuntime) }
}

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

// Measure the actual NEXT Preview, without changing its identity, signer or the default distribution.
val nextSizeProbe = providers.gradleProperty("choplabNextSizeProbe").map(String::toBooleanStrict).orElse(false).get()

android {
    namespace = "com.choplab.sampler"
    compileSdk = 37
    // AGP rewrites the matching test APK using this app's R8 mapping. A Debug test APK is not compatible.
    if (nextSizeProbe) testBuildType = "preview"

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
            isMinifyEnabled = true
            isShrinkResources = true
            // The formal size gate is the arm64 phone build. Debug/Preview retain emulator ABIs.
            ndk.abiFilters += "arm64-v8a"
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
            ndk.abiFilters.clear()
            isMinifyEnabled = false
            isShrinkResources = false
            // Explicit local compatibility probe; this does not change the release size/signing gate.
            if (providers.gradleProperty("choplabNewPipeR8Probe").orNull == "true") {
                isMinifyEnabled = true
                proguardFile(rootProject.file("config/newpipe-r8-probe.pro"))
            }
            if (nextSizeProbe) {
                ndk.abiFilters += "arm64-v8a"
                isMinifyEnabled = true
                isShrinkResources = true
                proguardFile("proguard-next-runtime-probe.pro")
                testProguardFile("proguard-android-test.pro")
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
        if (nextSizeProbe) getByName("androidTest") {
            // Check the optimized app through Android's public launcher/runtime boundary.
            // The regular Compose tests access internals that R8 can legitimately inline/remove.
            java.setSrcDirs(emptyList<String>())
            kotlin.setSrcDirs(listOf("src/nextRuntimeTest/java", "src/androidTest/java/com/choplab/sampler/audio"))
        }
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
    onVariants(selector().withBuildType("preview")) { variant ->
        if (nextSizeProbe) {
            // Store the identical optimized DEX with lossless APK compression.
            // Scope this to the NEXT size candidate; preserve every R8/reflection rule.
            variant.packaging.dex.useLegacyPackaging.set(true)
        }
    }
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
    if (androidAudioRuntimeCandidate == null) implementation(libs.youtubedl.ffmpeg)
    else implementation(files(androidAudioRuntimeCandidate))
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
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.uiautomator)
    if (!nextSizeProbe) {
        androidTestImplementation(libs.androidx.test.espresso)
        androidTestImplementation(libs.androidx.compose.ui.test.junit4)
        androidTestImplementation(libs.androidx.compose.ui.test.junit4.accessibility)
    }
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
