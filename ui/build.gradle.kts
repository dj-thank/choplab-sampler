plugins {
    alias(libs.plugins.android.kotlin.multiplatform)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvmToolchain(21)
    android {
        namespace = "com.choplab.ui"
        compileSdk = 37
        minSdk = 29
        androidResources.enable = true
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
        withHostTest {}
    }
    jvm("desktop") {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.resources)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies { implementation(libs.kotlin.test) }
        getByName("desktopTest") {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "com.choplab.ui.resources"
    generateResClass = always
}

tasks.withType<Test>().configureEach {
    maxParallelForks = 1
    systemProperty("java.awt.headless", "true")
    systemProperty("skiko.renderApi", "SOFTWARE")
    systemProperty("choplab.ui.evidenceDir", providers.gradleProperty("uiEvidenceDir")
        .orElse(layout.buildDirectory.dir("reports/ui-evidence").map { it.asFile.absolutePath }).get())
    testLogging { events("passed", "failed", "skipped") }
}
