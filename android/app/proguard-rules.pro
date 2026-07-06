# AgentHub ProGuard Rules

# === AgentHub app classes ===
-keep class com.agenthub.app.** { *; }
-keepclassmembers class com.agenthub.app.** {
    public *;
    <init>();
}

# === Keep line numbers for debugging ===
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# === AndroidX ===
-keep class androidx.appcompat.** { *; }
-keep class com.google.android.material.** { *; }

# === Suppress warnings ===
-dontwarn android.webkit.**
