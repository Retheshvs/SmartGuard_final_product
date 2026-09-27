# SmartGuard ProGuard rules.
# Release build currently ships with minification disabled (see build.gradle.kts).
# Keep TensorFlow Lite and ML Kit types if minification is enabled later.
-keep class org.tensorflow.lite.** { *; }
-keep class com.google.mlkit.** { *; }
-dontwarn org.tensorflow.lite.**
