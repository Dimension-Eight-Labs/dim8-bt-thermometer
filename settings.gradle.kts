// Single-module project: docs/ARCHITECTURE.md requires a demonstrated need
// before the app is split into additional Gradle modules.
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
    }
}

rootProject.name = "dim8-bt-thermometer"
include(":app")
