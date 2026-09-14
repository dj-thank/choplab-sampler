import org.gradle.api.tasks.Exec

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.choplab.desktop.DesktopAppKt")
}

val choplabVersion = providers.gradleProperty("choplabVersion").orElse("0.0.0")

dependencies {
    implementation(project(":shared"))
    implementation(project(":jvm-core"))
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material3:material3:1.9.0")
    implementation("net.java.dev.jna:jna:5.19.1")
    implementation("net.java.dev.jna:jna-platform:5.19.1")
    implementation("com.microsoft.onnxruntime:onnxruntime:1.29.0")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // The offscreen input fixture has its own bounded, explicitly headless target.
    exclude("**/ui/DesktopLongPressUiTest*")
}

tasks.register<Test>("desktopLongPressUiTest") {
    group = "verification"
    description = "Exercise the real shared deck with offscreen Desktop mouse input and silent audio ports"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    filter {
        includeTestsMatching("com.choplab.desktop.ui.DesktopLongPressUiTest")
        includeTestsMatching("com.choplab.desktop.DesktopSamplerControllerTest.h13*")
    }
    maxParallelForks = 1
    // An explicit input-evidence invocation must execute, not reuse a prior XML receipt.
    outputs.upToDateWhen { false }
    systemProperty("java.awt.headless", "true")
    systemProperty("skiko.renderApi", "SOFTWARE")
    val evidenceDirectory = layout.buildDirectory.dir("reports/tests/desktopLongPressUiTest/evidence")
    val temporaryDirectory = layout.buildDirectory.dir("tmp/desktopLongPressUiTest")
    systemProperty("h13.evidenceDir", evidenceDirectory.get().asFile.absolutePath)
    systemProperty("java.io.tmpdir", temporaryDirectory.get().asFile.absolutePath)
    systemProperty("user.home", temporaryDirectory.get().dir("home").asFile.absolutePath)
    systemProperty("h13.negativeShortPress", providers.gradleProperty("h13NegativeShortPress").orElse("false").get())
    doFirst {
        evidenceDirectory.get().asFile.mkdirs()
        temporaryDirectory.get().dir("home").asFile.mkdirs()
    }
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
}

tasks.register<JavaExec>("runWasapiProbe") {
    group = "verification"
    description = "Probe current Windows default render/capture endpoints through WASAPI"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.choplab.desktop.audio.wasapi.WasapiProbeMainKt")
}

val prepareMediaTools by tasks.registering(Exec::class) {
    onlyIf { System.getProperty("os.name").contains("Windows",ignoreCase=true) }
    workingDir(rootProject.projectDir)
    commandLine("python", "scripts/prepare_media_tools.py", "--out", "work/media-tools")
}

val prepareSeparatorModel by tasks.registering(Exec::class) {
    onlyIf { System.getProperty("os.name").contains("Windows",ignoreCase=true) }
    workingDir(rootProject.projectDir)
    commandLine("python", "scripts/prepare_separator_model.py", "--out", "work/separator-models")
}

val windowsPackageDirectory=providers.gradleProperty("windowsPackageDirectory").orElse("windows-app-image")
require(windowsPackageDirectory.get().matches(Regex("[A-Za-z0-9_-]+"))) { "Use a directory name inside desktop/build" }

tasks.register<Exec>("packageWindows") {
    dependsOn(tasks.installDist, prepareMediaTools, prepareSeparatorModel)
    onlyIf { System.getProperty("os.name").contains("Windows", ignoreCase = true) }

    val inputDir = tasks.installDist.get().destinationDir.resolve("lib")
    val destinationDir = layout.buildDirectory.dir(windowsPackageDirectory).get().asFile
    doFirst {
        val executable=destinationDir.resolve("ChopLab/ChopLab.exe").canonicalFile.path
        val running=ProcessHandle.allProcesses().use { handles ->
            handles.anyMatch { it.info().command().orElse("").equals(executable,ignoreCase=true) }
        }
        check(!running) { "Windows app image is running. Choose a fresh -PwindowsPackageDirectory name." }
        check(destinationDir.deleteRecursively()) { "Windows app image is in use. Choose a fresh -PwindowsPackageDirectory name." }
        destinationDir.mkdirs()
    }
    commandLine(
        "jpackage",
        "--type", "app-image",
        "--name", "ChopLab",
        "--input", inputDir.absolutePath,
        "--main-jar", tasks.jar.get().archiveFileName.get(),
        "--main-class", application.mainClass.get(),
        "--dest", destinationDir.absolutePath,
        "--vendor", "ChopLab",
        "--app-version", choplabVersion.get(),
        "--description", "ChopLab original-style おとひろい desktop sampler",
        "--copyright", "ChopLab contributors",
        // Startup trims that stay valid on the JDK 17 CI runtime: skip the hsperfdata mmap and
        // start with a heap that already fits the deck so the first frames avoid resize GCs.
        "--java-options", "-XX:-UsePerfData",
        "--java-options", "-Xms160m",
        "--java-options", "-Dfile.encoding=UTF-8",
    )
    val spotifyClient=providers.environmentVariable("CHOPLAB_SPOTIFY_CLIENT_ID").orElse("").get()
    require(spotifyClient.isEmpty() || spotifyClient.matches(Regex("[A-Za-z0-9]{16,128}")))
    if(spotifyClient.isNotEmpty()) args(
        "--java-options", "-Dchoplab.spotifyClientId=$spotifyClient",
    )
}

tasks.named("packageWindows") {
    doLast {
        copy {
            from(rootProject.file("work/media-tools"))
            into(layout.buildDirectory.dir("${windowsPackageDirectory.get()}/ChopLab/tools"))
        }
        copy {
            from(rootProject.file("work/separator-models"))
            into(layout.buildDirectory.dir("${windowsPackageDirectory.get()}/ChopLab/models"))
        }
    }
}

tasks.register<JavaExec>("sourceImport") {
    dependsOn(tasks.classes)
    classpath=sourceSets["main"].runtimeClasspath
    mainClass.set("com.choplab.desktop.source.SourceImportCliKt")
    workingDir(rootProject.projectDir)
    providers.gradleProperty("sourceSpotifyCheck").orNull?.let { args("--spotify-check",it) }
    providers.gradleProperty("sourceListFile").orNull?.let { args("--list",it) }
    providers.gradleProperty("sourceLibrary").orNull?.let { args("--library",it) }
    providers.gradleProperty("sourceYoutube").orNull?.let { args("--youtube",it) }
    providers.gradleProperty("sourceExport").orNull?.let { args("--export",it) }
}

tasks.register<JavaExec>("separateDrums") {
    dependsOn(tasks.classes)
    classpath=sourceSets["main"].runtimeClasspath
    mainClass.set("com.choplab.desktop.separation.SeparateDrumsCliKt")
    workingDir(rootProject.projectDir)
    providers.gradleProperty("separateInput").orNull?.let { args("--input",it) }
    providers.gradleProperty("separateOutput").orNull?.let { args("--output",it) }
    providers.gradleProperty("separateModels").orNull?.let { args("--models",it) }
}
