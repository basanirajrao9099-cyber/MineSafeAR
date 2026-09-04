// Top-level build file.
//
// Note: `org.jetbrains.kotlin.android` is deliberately absent. AGP 9.x ships
// built-in Kotlin support and enables it by default, and that plugin is not
// compatible with AGP 9's new DSL. Declaring the Compose compiler plugin at
// Kotlin 2.4.10 also raises AGP's bundled Kotlin Gradle Plugin to 2.4.10,
// which is what SceneView 4.33.0 is compiled against.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
