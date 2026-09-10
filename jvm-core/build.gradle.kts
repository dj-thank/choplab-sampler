plugins {
    id("org.jetbrains.kotlin.jvm")
}
kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":shared"))
    testImplementation("junit:junit:4.13.2")
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
