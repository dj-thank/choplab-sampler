plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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
    add("previewImplementation", project(":jvm"))
    add("previewImplementation", project(":ui"))
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
