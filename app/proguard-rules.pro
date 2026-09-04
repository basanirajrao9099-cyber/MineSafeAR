# Keep rules for MineSafeAR release builds.
# Minification is currently disabled; rules are collected here as the app grows.

# ARCore / SceneView load native code and are reflected over by Filament.
-keep class com.google.ar.core.** { *; }
-keep class com.google.android.filament.** { *; }

# ZXing reflectively instantiates readers/writers.
-keep class com.google.zxing.** { *; }
