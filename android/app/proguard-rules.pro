# Agent Control Center ProGuard Rules

# === Keep line numbers for debugging ===
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# === Suppress warnings ===
-dontwarn android.webkit.**

# === Room (SQLite / DAO) ===
# Room 编译器生成代码通过反射访问 Entity 和 DAO，必须保留。
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-dontwarn androidx.room.paging.**

# Phase 2.4: 移除以下宽泛 keep 规则（自带 consumer-rules，无需手动 keep）：
# -keep class androidx.datastore.** { *; }      → DataStore 自带 consumer rules
# -keep class io.ktor.** { *; }                  → Ktor 自带 consumer rules
# -keep class okhttp3.** { *; }                  → OkHttp 自带 consumer rules
# -keep class okio.** { *; }                     → Okio 自带 consumer rules
# -keep class * extends ViewModel { *; }         → Hilt 已生成 keep 规则

# === Gson (prevent reflection issues) ===
# Gson 通过反射读写字段，必须保留 data model 类及其成员。
-keep class com.google.gson.** { *; }
-keep class com.agenthub.app.data.model.** { *; }
-keepclassmembers class com.agenthub.app.data.model.** { *; }
# Gson TypeToken 子类需要保留（ChatRepository 中的 METADATA_MAP_TYPE）
-keep class * extends com.google.gson.reflect.TypeToken { *; }
-keep class com.agenthub.app.core.database.entity.** { *; }

# === Kotlin coroutines ===
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# === AndroidViewModel (prevent Application removal) ===
-keep class * extends androidx.lifecycle.AndroidViewModel { *; }

# OkHttp (used by Ktor okhttp engine) — 只保留 dontwarn，keep 由 consumer rules 提供
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn io.ktor.**
-dontwarn androidx.datastore.**
