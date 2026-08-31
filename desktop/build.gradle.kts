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
    implementation("net.java.dev.jna:jna:5.19.1")
    implementation("net.java.dev.jna:jna-platform:5.19.1")
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
        includeTestsMatching("com.choplab.desktop.DesktopSamplerControllerTest.h13CapturePad*")
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
    }
}

tasks.register<JavaExec>("runWasapiProbe") {
    group = "verification"
    description = "Probe current Windows default render/capture endpoints through WASAPI"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.choplab.desktop.audio.wasapi.WasapiProbeMainKt")
}

tasks.register<Exec>("packageWindows") {
    dependsOn(tasks.installDist)
    onlyIf { System.getProperty("os.name").contains("Windows", ignoreCase = true) }

    val inputDir = tasks.installDist.get().destinationDir.resolve("lib")
    val destinationDir = layout.buildDirectory.dir("windows-app-image").get().asFile
    doFirst {
        destinationDir.deleteRecursively()
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
    )
}
