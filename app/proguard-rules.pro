# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep BLE related classes
-keep class android.bluetooth.** { *; }

# Keep data model classes
-keep class com.tpmsapp.model.** { *; }
-keep class com.tpmsapp.ble.** { *; }
