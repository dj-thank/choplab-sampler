pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // The upstream's documented release channel; unrelated coordinates never resolve through it.
        exclusiveContent {
            forRepository { maven("https://jitpack.io") }
            filter { includeGroup("com.github.TeamNewPipe") }
        }
    }
}

rootProject.name = "ChopLab"
include(":app")
include(":desktop")
include(":shared")
include(":jvm-core")
include(":engine")
include(":core", ":jvm")
include(":ui")

// Apple host is opt-in; the Android/JVM release graph does not change.
if (providers.gradleProperty("choplabIosPreview").map(String::toBooleanStrict).orElse(false).get()) {
    include(":apple")
}
