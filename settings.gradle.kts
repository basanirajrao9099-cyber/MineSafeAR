pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// Supplies the toolchain download repository Gradle 9 requires before it will
// resolve or provision a JDK. Without it, any task that evaluates a toolchain
// (notably `:updateDaemonJvm`, which Studio fires when the Gradle JDK changes)
// fails with "Toolchain download repositories have not been configured."
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MineSafeAR"

include(":app")
