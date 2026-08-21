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
