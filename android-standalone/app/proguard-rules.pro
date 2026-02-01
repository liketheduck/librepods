# OpenPods ProGuard Rules

# Keep Bluetooth classes
-keep class android.bluetooth.** { *; }

# Keep Compose
-keep class androidx.compose.** { *; }

# Keep data classes
-keepclassmembers class com.openpods.app.data.** { *; }
