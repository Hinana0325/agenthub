# AgentHub ProGuard Rules

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
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
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
-keep class com.agenthub.app.data.model.** { *; }
-keepclassmembers class com.agenthub.app.data.model.** { *; }

# === Kotlin coroutines ===
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# === AndroidViewModel (prevent Application removal) ===
-keep class * extends androidx.lifecycle.AndroidViewModel { *; }

# === Keep ViewModels for Hilt (future) ===
-keep class * extends androidx.lifecycle.ViewModel { *; }

# === Keep entity classes for Room reflection ===
-keep class com.agenthub.app.data.local.entity.** { *; }
