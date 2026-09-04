pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// Fix AGP 8+ AndroidLocationsException caused by simultaneous ANDROID_PREFS_ROOT and ANDROID_USER_HOME env vars
run {
    try {
        val pe = Class.forName("java.lang.ProcessEnvironment")
        for (fieldName in listOf("theEnvironment", "theUnmodifiableEnvironment", "theCaseInsensitiveEnvironment")) {
            try {
                val field = pe.getDeclaredField(fieldName).apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST", "ExplicitThis")
                val map = field[null] as? MutableMap<Any, Any>
                map?.keys?.removeIf { it.toString().equals("ANDROID_PREFS_ROOT", ignoreCase = true) }
            } catch (_: Exception) {}
        }
    } catch (_: Exception) {}
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
