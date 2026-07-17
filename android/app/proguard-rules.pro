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

# === Room (SQLite / DAO) ===
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class com.agenthub.app.data.local.entity.** { *; }
-keep class com.agenthub.app.data.local.dao.** { *; }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# === DataStore ===
-keep class androidx.datastore.** { *; }
-dontwarn androidx.datastore.**

# === Ktor ===
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-keep class io.ktor.utils.io.** { *; }

# === Gson (prevent reflection issues) ===
-keep class com.google.gson.** { *; }
-keepclassmembers class com.agenthub.app.data.model.** { *; }
-keep class com.agenthub.app.data.model.** { *; }

# === Kotlin coroutines ===
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# === AndroidViewModel (prevent Application removal) ===
-keep class androidx.lifecycle.AndroidViewModel { *; }
-keep class * extends androidx.lifecycle.AndroidViewModel { *; }
