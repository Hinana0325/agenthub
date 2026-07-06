# AgentHub ProGuard Rules

# === Capacitor Core ===
-keep class com.getcapacitor.** { *; }
-keep class com.getcapacitor.community.** { *; }
-keep @com.getcapacitor.JSImport class *
-keep @com.getcapacitor.JSExport class *
-keep @com.getcapacitor.Plugin class *
-keep @com.getcapacitor.PluginMethod class *

# === Capacitor Plugin Registration (reflection) ===
-keep class * extends com.getcapacitor.Plugin { *; }
-keepclassmembers class * extends com.getcapacitor.Plugin {
    public *;
    <init>();
}

# === AgentHub Plugins ===
-keep class com.agenthub.app.** { *; }
-keepclassmembers class com.agenthub.app.** {
    public *;
    <init>();
}

# === Cordova (Capacitor compatibility layer) ===
-keep class org.apache.cordova.** { *; }
-keep class **.CordovaPlugin { *; }

# === WebView JavaScript Interface ===
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# === Keep line numbers for debugging ===
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# === AndroidX ===
-keep class androidx.appcompat.** { *; }
-keep class com.google.android.material.** { *; }

# === Suppress warnings ===
-dontwarn android.webkit.**
-dontwarn org.apache.cordova.**
