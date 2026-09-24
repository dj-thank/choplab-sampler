plugins {
    alias(libs.plugins.kotlin.jvm)
}
kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    // Same ai.onnxruntime API: Windows supplies the desktop JAR, Android the AAR.
    compileOnly(libs.onnxruntime.desktop)
    testImplementation(libs.onnxruntime.desktop)
    testImplementation(libs.junit)
}

// Opt-in, synthetic fixture only. No timing threshold is used by the test gate.
tasks.register<JavaExec>("benchmarkAutosaveRecovery") {
    group = "verification"
    description = "Measure autosave recovery against a separately seeded synthetic fixture"
    dependsOn("testClasses")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.choplab.sampler.persistence.AutosaveRecoveryBenchmarkKt")
    minHeapSize = "256m"
    maxHeapSize = "512m"
}

// Separate opt-in entry point for fixed-buffer PCM timing and per-thread allocation.
tasks.register<JavaExec>("benchmarkPcmRestore") {
    group = "verification"
    description = "Measure synthetic WAV decode or recovery; not physical app startup"
    dependsOn("testClasses")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.choplab.sampler.persistence.PcmRestoreBenchmark")
    minHeapSize = "256m"
    maxHeapSize = "512m"
}
