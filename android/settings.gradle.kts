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

rootProject.name = "jarvis-android"

// :core is a plain Kotlin/JVM module (agent engine, provider adapters, security).
// It has no Android dependency, so its unit tests run on any JVM.
// :app is the Android application (UI, device tools, storage).
include(":core")
include(":app")
