# AgentHub ProGuard Rules

# === Keep line numbers for debugging ===
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# === AndroidX ===
# 注：以下两条过于宽泛的 keep 规则已移除。
# androidx.appcompat 与 com.google.android.material 自带 consumer-rules，
# 无需在此整体保留；保留反而会显著增加 APK 体积并削弱混淆效果。
# -keep class androidx.appcompat.** { *; }
# -keep class com.google.android.material.** { *; }

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
# Critical 1 修复：entity 实际位于 com.agenthub.app.core.database.entity 包，
# 原 com.agenthub.app.data.local.entity 路径不存在，release 混淆会导致 Room 反射崩溃。
-keep class com.agenthub.app.core.database.entity.** { *; }

# OkHttp (used by Ktor okhttp engine)
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keep class okio.** { *; }
