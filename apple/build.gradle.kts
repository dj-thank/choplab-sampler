plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

// The iPadOS host: Apple audio, files and lifecycle behind the shared ui/core/engine ports.
// settings.gradle.kts includes this project only with -PchoplabIosPreview=true, so the default
// Android/JVM/Windows/Mac graph, tasks and distributions are unchanged.
kotlin {
    jvmToolchain(21)
    jvm("desktop")
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "ChopLabApp"
            isStatic = true
        }
    }
    compilerOptions {
        optIn.add("kotlinx.cinterop.ExperimentalForeignApi")
        optIn.add("kotlinx.cinterop.BetaInteropApi")
        optIn.add("kotlin.concurrent.atomics.ExperimentalAtomicApi")
    }
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
            implementation(libs.compose.resources)
            implementation(libs.compose.runtime)
        }
        commonTest.dependencies { implementation(libs.kotlin.test) }
        iosMain.dependencies {
            implementation(project(":ui"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.material3)
            implementation(libs.compose.resources)
            implementation(libs.kotlinx.coroutines.core)
        }
        iosTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

compose.resources {
    packageOfResClass = "com.choplab.apple.resources"
    generateResClass = always
}
