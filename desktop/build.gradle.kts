import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.Sync

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    application
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

application {
    mainClass.set("com.choplab.desktop.DesktopAppKt")
}

tasks.register<JavaExec>("runLinkedPreview") {
    group = "application"
    description = "Run the four-stage editor from source with a checkout-local development profile"
    dependsOn("classes")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.choplab.desktop.next.LinkedPreviewMainKt")
    systemProperty("choplab.preview", "true")
    val developmentRoot = rootProject.file(providers.gradleProperty("choplabDevelopmentRoot")
        .orElse("work/desktop-development").get())
    environment("LOCALAPPDATA", developmentRoot.resolve("data").absolutePath)
    systemProperty("java.io.tmpdir", developmentRoot.resolve("tmp").absolutePath)
    debugOptions {
        host.set("127.0.0.1")
        port.set(providers.gradleProperty("choplabDebugPort").map(String::toInt).orElse(5005))
        suspend.set(true)
    }
    doFirst {
        check(developmentRoot.resolve("data").let { it.isDirectory || it.mkdirs() })
        check(developmentRoot.resolve("tmp").let { it.isDirectory || it.mkdirs() })
    }
}

val macHost = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
val macSystemAudioHelper = layout.buildDirectory.file("choplab-sck-audio")
// ScreenCaptureKit capture is built against a stable OS baseline; bundled native tools may require newer macOS.
val macSystemAudioTarget = "${System.getProperty("os.arch").let { if (it == "aarch64") "arm64" else it }}-apple-macos14.0"
val compileMacSystemAudioHelper = tasks.register<Exec>("compileMacSystemAudioHelper") {
    onlyIf { macHost }
    val source = layout.projectDirectory.file("src/main/swift/ChoplabSystemAudio.swift")
    inputs.file(source)
    inputs.property("deploymentTarget", macSystemAudioTarget)
    outputs.file(macSystemAudioHelper)
    commandLine(
        "swiftc", "-O", "-parse-as-library", "-target", macSystemAudioTarget,
        "-o", macSystemAudioHelper.get().asFile.absolutePath,
        source.asFile.absolutePath,
    )
}
if (macHost) {
    listOf("run", "runLinkedPreview").forEach { taskName -> tasks.named<JavaExec>(taskName) {
        dependsOn(compileMacSystemAudioHelper)
        systemProperty("choplab.systemAudioHelper", macSystemAudioHelper.get().asFile.absolutePath)
    } }
    tasks.named<Sync>("installDist") {
        dependsOn(compileMacSystemAudioHelper)
        from(macSystemAudioHelper) { into("lib") }
    }
}

val choplabVersion = providers.gradleProperty("choplabVersion").orElse("0.0.0")

dependencies {
    implementation(project(":shared"))
    implementation(project(":jvm-core"))
    implementation(project(":jvm"))
    implementation(project(":ui"))
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(libs.compose.resources)
    implementation(libs.jna.core)
    implementation(libs.jna.platform)
    implementation(libs.onnxruntime.desktop)
    testImplementation(libs.kotlin.test)
}

tasks.test {
    useJUnitPlatform()
    // The offscreen input fixture has its own bounded, explicitly headless target.
    exclude("**/ui/DesktopLongPressUiTest*", "**/ui/DesktopUiQualityTest*")
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

val windowsAudioRuntime = providers.gradleProperty("choplabWindowsAudioRuntime")
val windowsMediaToolsDirectory = if (windowsAudioRuntime.isPresent) "work/media-tools-audio" else "work/media-tools"
val prepareMediaTools = tasks.register<Exec>("prepareMediaTools") {
    onlyIf { System.getProperty("os.name").contains("Windows",ignoreCase=true) }
    workingDir(rootProject.projectDir)
    commandLine(listOf("python", "scripts/prepare_media_tools.py", "--out", windowsMediaToolsDirectory) +
        windowsAudioRuntime.orNull?.let { listOf("--ffmpeg-audio-runtime", it) }.orEmpty())
}

val windowsRuntimeDirectory = layout.buildDirectory.dir("windows-runtime-inputs")
val windowsNoticesDirectory = layout.buildDirectory.dir("windows-source-notices")
val prepareWindowsNotices = tasks.register<Exec>("prepareWindowsNotices") {
    onlyIf { System.getProperty("os.name").contains("Windows", ignoreCase = true) }
    workingDir(rootProject.projectDir)
    outputs.dir(windowsNoticesDirectory)
    outputs.upToDateWhen { false }
    // Refresh only this task-owned generated notice output, including legacy recipe names.
    doFirst { project.delete(windowsNoticesDirectory.get().asFile) }
    commandLine("python", "scripts/prepare_source_notices.py", "--platform", "windows", "--out",
        windowsNoticesDirectory.get().asFile.absolutePath)
}
val stageWindowsRuntime = tasks.register<Sync>("stageWindowsRuntime") {
    dependsOn(tasks.installDist)
    onlyIf { System.getProperty("os.name").contains("Windows",ignoreCase=true) }
    from(tasks.installDist.map { it.destinationDir.resolve("lib") })
    from(rootProject.file("licenses/onnxruntime-LICENSE.txt"))
    into(windowsRuntimeDirectory)
}
val prepareWindowsRuntime = tasks.register<Exec>("prepareWindowsRuntime") {
    dependsOn(stageWindowsRuntime)
    onlyIf { System.getProperty("os.name").contains("Windows",ignoreCase=true) }
    workingDir(rootProject.projectDir)
    doFirst {
        val upstream = tasks.installDist.get().destinationDir.resolve("lib").listFiles()!!.single {
            it.name.startsWith("onnxruntime-") && it.extension == "jar"
        }
        commandLine("python", "scripts/prepare_windows_runtime.py", "--input", upstream.absolutePath,
            "--output", windowsRuntimeDirectory.get().file(upstream.name).asFile.absolutePath,
            "--receipt", windowsRuntimeDirectory.get().file("onnxruntime-windows.json").asFile.absolutePath)
    }
}

val windowsPackageDirectory=providers.gradleProperty("windowsPackageDirectory").orElse("windows-app-image")
require(windowsPackageDirectory.get().matches(Regex("[A-Za-z0-9_-]+"))) { "Use a directory name inside desktop/build" }
val desktopRuntimeToolchain = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(21))
}

fun registerWindowsImage(taskName: String, imageName: String, outputFolder: org.gradle.api.provider.Provider<String>, preview: Boolean, linked: Boolean = false) {
    tasks.register<Exec>(taskName) {
        dependsOn(prepareWindowsRuntime, prepareMediaTools, prepareWindowsNotices)
        onlyIf { System.getProperty("os.name").contains("Windows", ignoreCase = true) }
        val inputDir = windowsRuntimeDirectory.get().asFile
        val destinationDir = layout.buildDirectory.dir(outputFolder).get().asFile
        doFirst {
            val buildRoot = layout.buildDirectory.get().asFile.canonicalFile.toPath()
            val packageRoot = destinationDir.canonicalFile.toPath()
            check(packageRoot != buildRoot && packageRoot.startsWith(buildRoot)) {
                "Windows app image destination must stay strictly below desktop/build."
            }
            executable(desktopRuntimeToolchain.get().metadata.installationPath.file("bin/jpackage.exe").asFile.absolutePath)
            val imageExecutable = destinationDir.resolve("$imageName/$imageName.exe").canonicalFile.path
            val running = ProcessHandle.allProcesses().use { handles ->
                handles.anyMatch { it.info().command().orElse("").equals(imageExecutable, ignoreCase = true) }
            }
            check(!running) { "This Windows app image is running; close that exact instance before packaging." }
            check(destinationDir.deleteRecursively()) { "Windows app image is in use." }
            check(destinationDir.mkdirs() || destinationDir.isDirectory) { "Cannot create package directory" }
        }
        commandLine(
            "jpackage", "--type", "app-image",
            // jdeps plus reflective desktop/HTTPS locale providers; omit compiler tools and ct.sym.
            "--add-modules", "java.base,java.desktop,java.instrument,java.logging,java.management,java.net.http,java.naming,jdk.httpserver,jdk.unsupported,jdk.crypto.ec,jdk.localedata,jdk.charsets",
            "--jlink-options", "--strip-debug --no-header-files --no-man-pages --compress=2",
            "--name", imageName,
            "--input", inputDir.absolutePath,
            "--main-jar", tasks.jar.get().archiveFileName.get(),
            "--main-class", if (linked) "com.choplab.desktop.next.LinkedPreviewMainKt" else application.mainClass.get(),
            "--dest", destinationDir.absolutePath,
            "--vendor", "ChopLab", "--app-version", choplabVersion.get(),
            "--description", "Earth Song / おとひろい desktop sampler",
            "--copyright", "ChopLab contributors",
            "--java-options", "-XX:-UsePerfData",
            "--java-options", "-Xms160m",
            "--java-options", "-Dfile.encoding=UTF-8",
            "--java-options", "-Dchoplab.preview=$preview",
        )
        val spotifyClient = providers.environmentVariable("CHOPLAB_SPOTIFY_CLIENT_ID").orElse("").get()
        require(spotifyClient.isEmpty() || spotifyClient.matches(Regex("[A-Za-z0-9]{16,128}")))
        if (spotifyClient.isNotEmpty()) args("--java-options", "-Dchoplab.spotifyClientId=$spotifyClient")
        doLast {
            project.copy {
                from(rootProject.file(windowsMediaToolsDirectory))
                into(destinationDir.resolve("$imageName/tools"))
            }
            project.copy {
                from(windowsNoticesDirectory)
                into(destinationDir.resolve(imageName))
            }
        }
    }
}

registerWindowsImage("packageWindows", "ChopLab", windowsPackageDirectory, false)
val windowsPreviewPackageDirectory = providers.gradleProperty("windowsPreviewPackageDirectory").orElse("windows-preview-app-image")
require(windowsPreviewPackageDirectory.get().matches(Regex("[A-Za-z0-9_-]+"))) { "Use a directory name inside desktop/build" }
require(windowsPreviewPackageDirectory.get() != windowsPackageDirectory.get()) { "Preview and production outputs must be separate" }
registerWindowsImage("packageWindowsPreview", "ChopLab Preview", windowsPreviewPackageDirectory, true)
registerWindowsImage("packageWindowsLinkedPreview", "ChopLab Preview", providers.provider { "windows-linked-preview-app-image" }, true, linked = true)

val macMediaToolsDirectory = layout.buildDirectory.dir("mac-media-tools")
val prepareMacMediaTools = tasks.register<Exec>("prepareMacMediaTools") {
    group = "distribution"
    description = "Prepare and hash the self-contained local Mac media tool bundle"
    onlyIf { macHost }
    workingDir(rootProject.projectDir)
    commandLine("python3", "scripts/prepare_mac_media_tools.py", "--out", macMediaToolsDirectory.get().asFile.absolutePath)
}
val macAudioToolsDirectory = layout.buildDirectory.dir("mac-audio-tools")
val prepareMacAudioTools = tasks.register<Exec>("prepareMacAudioTools") {
    group = "distribution"
    description = "Prepare the local Mac audio codecs and their private dependencies"
    onlyIf { macHost }
    workingDir(rootProject.projectDir)
    commandLine("python3", "scripts/prepare_mac_media_tools.py", "--audio-only", "--out", macAudioToolsDirectory.get().asFile.absolutePath)
}

listOf(Triple("packageMacPreview", false, false), Triple("packageMacSignedPreview", true, false),
    Triple("packageMacLinkedPreview", false, true)).forEach { (taskName, signed, linked) ->
    tasks.register<Exec>(taskName) {
        group = "distribution"
        description = when {
            linked -> "Build the linked four-stage editor as an explicitly local, ad-hoc Mac app (おとひろい NEXT)"
            signed -> "Build a Developer ID signed Mac Preview (requires identity)"
            else -> "Build an explicitly local, ad-hoc Mac Preview"
        }
        dependsOn(tasks.installDist)
        onlyIf { macHost }
        workingDir(rootProject.projectDir)
        commandLine("python3", "scripts/package_mac_app.py", "--java-home",
            desktopRuntimeToolchain.get().metadata.installationPath.asFile.absolutePath)
        dependsOn(prepareMacMediaTools)
        args("--tools", macMediaToolsDirectory.get().asFile.absolutePath)
        if (signed) args("--signed")
        if (linked) args("--linked")
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

// Synthetic, offscreen review of the actual shared UI. No native dialogs or audio.
tasks.register<Test>("desktopUiQualityTest") {
    group = "verification"
    description = "Render representative phone/desktop screens and assert control semantics and bounds"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching("com.choplab.desktop.ui.DesktopUiQualityTest") }
    maxParallelForks = 1
    outputs.upToDateWhen { false }
    systemProperty("java.awt.headless", "true")
    systemProperty("skiko.renderApi", "SOFTWARE")
    val evidence = layout.buildDirectory.dir("reports/tests/desktopUiQualityTest/evidence")
    val temporary = layout.buildDirectory.dir("tmp/desktopUiQualityTest")
    systemProperty("uiReview.evidenceDir", evidence.get().asFile.absolutePath)
    systemProperty("java.io.tmpdir", temporary.get().asFile.absolutePath)
    systemProperty("user.home", temporary.get().dir("home").asFile.absolutePath)
    doFirst {
        evidence.get().asFile.mkdirs()
        temporary.get().dir("home").asFile.mkdirs()
    }
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
