# Ping ProGuard / R8 rules
# Hilt, Room, CameraX and Nearby ship their own consumer rules.

# MediaPipe Tasks Vision — HandLandmarker loads runners via JNI + reflection;
# R8 must not strip these or the app crashes on the first gesture attempt.
-keep class com.google.mediapipe.tasks.vision.** { *; }
-keep class com.google.mediapipe.tasks.core.** { *; }
-keep class com.google.mediapipe.framework.** { *; }
-keep class com.google.mediapipe.tasks.components.** { *; }
-keep class com.google.mediapipe.proto.** { *; }
-keepclassmembers class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# AutoValue annotation-processor leakage (transitive) — not present on Android.
-dontwarn javax.lang.model.**
-dontwarn javax.annotation.processing.**
-dontwarn autovalue.shaded.**
-dontwarn com.google.auto.value.**
