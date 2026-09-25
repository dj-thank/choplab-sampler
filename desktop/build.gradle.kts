import org.gradle.api.tasks.Exec

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
    description = "Open the preserved four-stage editor against the isolated Preview backend"
    dependsOn("classes")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.choplab.desktop.next.LinkedPreviewMainKt")
    systemProperty("choplab.preview", "true")
}

val choplabVersion = providers.gradleProperty("choplabVersion").orElse("0.0.0")

dependencies {
    implementation(project(":shared"))
    implementation(project(":jvm-core"))
    implementation(project(":jvm"))
    implementation(project(":ui"))
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
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

val prepareMediaTools = tasks.register<Exec>("prepareMediaTools") {
    onlyIf { System.getProperty("os.name").contains("Windows",ignoreCase=true) }
    workingDir(rootProject.projectDir)
    commandLine("python", "scripts/prepare_media_tools.py", "--out", "work/media-tools")
}

val prepareSeparatorModel = tasks.register<Exec>("prepareSeparatorModel") {
    onlyIf { System.getProperty("os.name").contains("Windows",ignoreCase=true) }
    workingDir(rootProject.projectDir)
    commandLine("python", "scripts/prepare_separator_model.py", "--out", "work/separator-models")
}

val windowsPackageDirectory=providers.gradleProperty("windowsPackageDirectory").orElse("windows-app-image")
require(windowsPackageDirectory.get().matches(Regex("[A-Za-z0-9_-]+"))) { "Use a directory name inside desktop/build" }
val windowsRuntimeToolchain = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(21))
}

fun registerWindowsImage(taskName: String, imageName: String, outputFolder: org.gradle.api.provider.Provider<String>, preview: Boolean, linked: Boolean = false) {
    tasks.register<Exec>(taskName) {
        dependsOn(tasks.installDist, prepareMediaTools, prepareSeparatorModel)
        onlyIf { System.getProperty("os.name").contains("Windows", ignoreCase = true) }
        val inputDir = tasks.installDist.get().destinationDir.resolve("lib")
        val destinationDir = layout.buildDirectory.dir(outputFolder).get().asFile
        doFirst {
            val buildRoot = layout.buildDirectory.get().asFile.canonicalFile.toPath()
            val packageRoot = destinationDir.canonicalFile.toPath()
            check(packageRoot != buildRoot && packageRoot.startsWith(buildRoot)) {
                "Windows app image destination must stay strictly below desktop/build."
            }
            executable(windowsRuntimeToolchain.get().metadata.installationPath.file("bin/jpackage.exe").asFile.absolutePath)
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
                from(rootProject.file("work/media-tools"))
                into(destinationDir.resolve("$imageName/tools"))
            }
            project.copy {
                from(rootProject.file("work/separator-models"))
                into(destinationDir.resolve("$imageName/models"))
            }
            project.copy {
                from(rootProject.file("LICENSE"), rootProject.file("NOTICE.md"))
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
