import org.cyclonedx.model.Component

plugins {
    alias(libs.plugins.cyclonedx)
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.compose.multiplatform) apply false
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
