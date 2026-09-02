import org.cyclonedx.model.Component

plugins {
    id("org.cyclonedx.bom") version "3.4.1"
    id("com.android.application") version "9.3.2" apply false
    id("com.android.kotlin.multiplatform.library") version "9.3.2" apply false
    id("org.jetbrains.kotlin.jvm") version "2.4.10" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    id("org.jetbrains.compose") version "1.11.1" apply false
}

val choplabVersion = providers.gradleProperty("choplabVersion").orElse("0.0.0-dev")

allprojects {
    group = "com.choplab"
    version = rootProject.providers.gradleProperty("choplabVersion").orElse("0.0.0-dev").get()
}

tasks.cyclonedxDirectBom {
    projectType = Component.Type.APPLICATION
    componentGroup = "com.choplab"
    componentName = "ChopLab"
    componentVersion = choplabVersion.get()
}

tasks.cyclonedxBom {
    projectType = Component.Type.APPLICATION
    componentGroup = "com.choplab"
    componentName = "ChopLab"
    componentVersion = choplabVersion.get()
}
