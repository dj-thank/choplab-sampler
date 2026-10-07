import groovy.json.JsonSlurper
import java.io.File
import java.security.MessageDigest
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.work.DisableCachingByDefault

plugins {
    alias(libs.plugins.kotlin.jvm)
}

@DisableCachingByDefault(because = "Recheck the reviewed dependency bytes on every requested build")
abstract class VerifyNewPipeDependencies : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val pins: RegularFileProperty

    @get:Input
    abstract val runtimeGraph: ListProperty<String>

    @get:Input
    abstract val artifactPaths: MapProperty<String, String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    val artifactInputs: List<File>
        get() = artifactPaths.get().values.map(::File)

    @TaskAction
    fun verify() {
        val document = JsonSlurper().parse(pins.get().asFile) as Map<*, *>
        val rows = (document["artifacts"] as List<*>).map { it as Map<*, *> }
            .filter { it["scope"] == "extractor-runtime" }.associateBy { it["coordinate"] as String }
        val graph = runtimeGraph.get().toSet()
        check(graph == rows.keys) { "NewPipe runtime dependency graph changed; review provenance, licenses and pins" }
        graph.forEach { coordinate ->
            val artifact = File(artifactPaths.get().getValue(coordinate))
            val expected = rows.getValue(coordinate)["binary"] as Map<*, *>
            val digest = MessageDigest.getInstance("SHA-256")
            artifact.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            check(artifact.length() == (expected["bytes"] as Number).toLong() && actual == expected["sha256"]) {
                "NewPipe artifact does not match its reviewed pin: $coordinate"
            }
        }
    }
}

val verifyNewPipeDependencies = tasks.register<VerifyNewPipeDependencies>("verifyNewPipeDependencies") {
    group = "verification"
    description = "Verify the fixed extractor runtime graph and its artifact bytes"
    pins.set(rootProject.layout.projectDirectory.file("config/newpipe-dependencies.json"))
    // Only plain values and file inputs cross the configuration-cache boundary.
    // The verification action retains no Project, Configuration or script reference.
    val runtime = configurations.runtimeClasspath.get()
    runtimeGraph.set(runtime.incoming.resolutionResult.rootComponent.map { root ->
        val extractor = root.dependencies.filterIsInstance<ResolvedDependencyResult>().single {
            it.selected.moduleVersion?.group == "com.github.TeamNewPipe" &&
                it.selected.moduleVersion?.name == "NewPipeExtractor"
        }
        val graph = mutableSetOf<String>()
        fun visit(component: ResolvedComponentResult) {
            val coordinate = requireNotNull(component.moduleVersion).toString()
            if (graph.add(coordinate)) component.dependencies.forEach { dependency ->
                check(dependency is ResolvedDependencyResult) { "NewPipe runtime dependency did not resolve" }
                visit(dependency.selected)
            }
        }
        visit(extractor.selected)
        graph.sorted()
    })
    artifactPaths.set(runtime.incoming.artifacts.resolvedArtifacts.zip(runtimeGraph) { artifacts, graph ->
        graph.associateWith { coordinate ->
            artifacts.single {
                val module = it.id.componentIdentifier as? ModuleComponentIdentifier
                module != null && "${module.group}:${module.module}:${module.version}" == coordinate &&
                    it.file.extension == "jar"
            }.file.absolutePath
        }
    })
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
