plugins { alias(libs.plugins.kotlin.jvm) }

kotlin {
    jvmToolchain(21)
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
tasks.withType<JavaCompile>().configureEach { options.release.set(17) }

dependencies {
    api(project(":core"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.kotlinx.coroutines.test)
}

// Development-only comparison; the new production runtime never depends on the legacy renderer.
val comparison by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations[comparison.implementationConfigurationName].extendsFrom(configurations.implementation.get())
dependencies {
    add(comparison.implementationConfigurationName, project(":jvm-core"))
    add(comparison.implementationConfigurationName, project(":shared"))
}
tasks.register<JavaExec>("renderDemo") {
    group = "verification"
    description = "Render deterministic synthetic legacy/new A/B audio with documented level matching"
    dependsOn(comparison.classesTaskName)
    classpath = comparison.runtimeClasspath
    mainClass.set("com.choplab.comparison.RenderDemoKt")
    args(layout.buildDirectory.dir("reports/audio-ab").get().asFile.absolutePath)
}
