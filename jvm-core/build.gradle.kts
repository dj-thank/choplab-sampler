import groovy.json.JsonSlurper
import java.security.MessageDigest

plugins {
    alias(libs.plugins.kotlin.jvm)
}

val verifyNewPipeDependencies = tasks.register("verifyNewPipeDependencies") {
    group = "verification"
    description = "Verify the fixed extractor runtime graph and its artifact bytes"
    val pins = rootProject.file("config/newpipe-dependencies.json")
    inputs.file(pins)
    inputs.files(configurations.runtimeClasspath)
    doLast {
        val document = JsonSlurper().parse(pins) as Map<*, *>
        val rows = (document["artifacts"] as List<*>).map { it as Map<*, *> }
            .filter { it["scope"] == "extractor-runtime" }.associateBy { it["coordinate"] as String }
        val resolved = configurations.runtimeClasspath.get().resolvedConfiguration
        val extractor = resolved.firstLevelModuleDependencies.single {
            it.moduleGroup == "com.github.TeamNewPipe" && it.moduleName == "NewPipeExtractor"
        }
        val graph = mutableSetOf<String>()
        fun visit(dependency: ResolvedDependency) {
            if (graph.add("${dependency.moduleGroup}:${dependency.moduleName}:${dependency.moduleVersion}"))
                dependency.children.forEach(::visit)
        }
        visit(extractor)
        check(graph == rows.keys) { "NewPipe runtime dependency graph changed; review provenance, licenses and pins" }
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
                "NewPipe artifact does not match its reviewed pin: $coordinate"
            }
        }
    }
}
tasks.named("compileKotlin") { dependsOn(verifyNewPipeDependencies) }
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
    implementation(libs.newpipe.extractor)
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
