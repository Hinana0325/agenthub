# Agent Control Center ProGuard Rules

# === Keep line numbers for debugging ===
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# === Obfuscation hardening ===
# Allow R8 to modify access modifiers for better obfuscation
-allowaccessmodification
# Repackage all non-kept classes into a single package
-repackageclasses ''
# Use obfuscation dictionary to make decompiled output harder to read
-obfuscationdictionary obfdict.txt
-classobfuscationdictionary obfdict.txt
-packageobfuscationdictionary obfdict.txt

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
# L-1: 仅保留通过反射读写的 data model 类，移除对 com.google.gson.** 的全量 keep
# （Gson 自身代码可被 R8 正常混淆，只有 @SerializedName 标注的 model 字段需要保留）。
-keep class com.agentcontrolcenter.app.data.model.** { *; }
-keepclassmembers class com.agentcontrolcenter.app.data.model.** { *; }
# Gson TypeToken 子类需要保留（保留其泛型签名供反射读取）
-keep class * extends com.google.gson.reflect.TypeToken { *; }
-keep class com.agentcontrolcenter.app.core.database.entity.** { *; }

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

# === Sentry (crash reporting) ===
# L-1: 移除对 io.sentry.** 的全量 keep — Sentry 自带 consumer rules 已正确保留
# 其所需反射入口，重复 keep 反而削弱 R8 优化空间。仅保留 dontwarn 避免 R8
# 误报其内部未引用类的告警。
-dontwarn io.sentry.**

# === Strip verbose/debug logs in release (L-1) ===
# 与 H7（release 关闭 Ktor LogLevel）配套：把 android.util.Log.v/Log.d 调用
# 从 release 字节码中彻底移除，防止 release 包通过 logcat 泄漏请求/响应头或
# 调试信息。保留 Log.i/Log.w/Log.e 以便崩溃诊断与线上问题排查。
# 注：assumenosideeffects 仅对 return void 的方法生效，R8 在确认无副作用后
# 会删除调用点；若调用结果被使用（如赋值）则不会删除。
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
}
