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

val desktopVersion = providers.gradleProperty("desktopVersion").orElse("0.2.0")

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
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
        "--app-version", desktopVersion.get(),
        "--description", "ChopLab original-style おとひろい desktop sampler",
        "--copyright", "ChopLab contributors",
    )
}
