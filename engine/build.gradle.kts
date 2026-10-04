import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvmToolchain(21)
    jvm("desktop") {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }
    // Native compilation is a separate preview; keep the default Android/JVM build graph intact.
    if (providers.gradleProperty("choplabIosPreview").map(String::toBooleanStrict).orElse(false).get()) {
        iosArm64()
        iosSimulatorArm64()
    }
    sourceSets {
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
