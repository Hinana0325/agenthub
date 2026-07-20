package com.agentcontrolcenter.app.data.backup

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.agentcontrolcenter.app.agent.model.AgentConfig
import com.agentcontrolcenter.app.core.datastore.SettingsDataStore
import com.agentcontrolcenter.app.core.security.KeystoreManager
import com.agentcontrolcenter.app.data.model.Message
import com.agentcontrolcenter.app.data.model.Session
import com.agentcontrolcenter.app.data.repository.ChatRepository
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 云备份/恢复管理器 — P3-3。
 *
 * 在原有 [com.agentcontrolcenter.app.feature.settings.SettingsViewModel.exportChatHistory] /
 * [com.agentcontrolcenter.app.feature.settings.SettingsViewModel.importChatHistory] 的本地
 * JSON 导出/导入基础上，提取为独立类并增强：
 *
 * - 支持完整数据备份（sessions / messages / agentConfigs / settings），不再仅 sessions + messages；
 * - 支持基于 [KeystoreManager] 的硬件级加密备份（设备绑定密钥，AES-256-GCM）；
 * - 暴露 [getAutoBackupSchedule] 用于自动备份调度查询。
 *
 * 设计说明：
 *  - 通过 Hilt `@Singleton` 注入，全局唯一实例，避免在多个 ViewModel 中重复创建；
 *  - 所有 I/O 操作均通过 `withContext(Dispatchers.IO)` 切换到 IO 线程，避免阻塞主线程；
 *  - 加密备份使用 [KeystoreManager.encrypt] —— 密钥由 Android Keystore 硬件安全模块保护，
 *    加密后的备份只能在同一设备（或已恢复 Keystore 的设备）上解密，防止备份文件外泄后
 *    被离线破解；
 *  - 文件写入使用 MediaStore.Downloads（API 29+）或 legacy 外部存储路径，与原实现兼容。
 *
 * @property repository 聊天数据仓库，提供 sessions / messages / agentConfigs 查询
 * @property dataStore 设置数据仓库，提供 settings 快照与 autoBackup 调度读取
 */
@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: ChatRepository,
    private val dataStore: SettingsDataStore
) {
    private val gson: Gson = Gson()

    /**
     * 自动备份调度枚举。
     *
     * - [DAILY]: 每日自动备份一次；
     * - [WEEKLY]: 每周自动备份一次；
     * - [MANUAL]: 仅手动触发备份（默认值）。
     */
    enum class BackupSchedule(val storageValue: String) {
        DAILY("daily"),
        WEEKLY("weekly"),
        MANUAL("manual");

        companion object {
            /** 从 DataStore 中存储的字符串值恢复枚举，未识别值回退到 [MANUAL]。 */
            fun fromStorageValue(value: String?): BackupSchedule =
                entries.firstOrNull { it.storageValue.equals(value, ignoreCase = true) } ?: MANUAL
        }
    }

    /**
     * 备份数据载体 — 包含一次完整快照所需的全部领域数据。
     *
     * 序列化为 JSON 后可作为本地备份文件或加密备份的明文载荷。
     *
     * @property version 备份格式版本号，用于向前兼容（当前 "3.0.0"）。
     * @property timestamp 备份生成时间（epoch milliseconds）。
     * @property sessions 全部会话列表。
     * @property messages 全部消息列表（跨会话扁平化）。
     * @property agentConfigs 全部 Agent 配置列表（apiKey 已解密为明文）。
     * @property settings 设置快照。
     */
    data class BackupData(
        val version: String = BACKUP_FORMAT_VERSION,
        val timestamp: Long = System.currentTimeMillis(),
        val sessions: List<Session> = emptyList(),
        val messages: List<Message> = emptyList(),
        val agentConfigs: List<AgentConfig> = emptyList(),
        val settings: BackupSettings = BackupSettings()
    )

    /**
     * 设置快照 — 备份/恢复时捕获的关键用户偏好。
     *
     * 仅包含可安全跨设备迁移的设置项；`e2eKey` 等绑定设备的敏感凭据不在此处，
     * 由 [KeystoreManager] 单独管理。
     *
     * @property themeMode 主题模式："system" / "light" / "dark"。
     * @property fontSize 字体大小："small" / "medium" / "large"。
     * @property e2eEnabled 是否启用端到端加密。
     * @property autoBackup 自动备份调度（DAILY / WEEKLY / MANUAL 的 storageValue）。
     */
    data class BackupSettings(
        val themeMode: String = "system",
        val fontSize: String = "medium",
        val e2eEnabled: Boolean = false,
        val autoBackup: String = BackupSchedule.MANUAL.storageValue
    )

    // ── 明文导出 / 导入 ──

    /**
     * 将完整备份以明文 JSON 写入 Downloads 目录。
     *
     * @param context 任意 Context（用于 ContentResolver）。
     * @param fileName 目标文件名（不含路径）。
     * @return 成功返回文件 [Uri]，失败返回包含异常的 [Result]。
     */
    suspend fun exportToFile(context: Context, fileName: String): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            val json = buildBackupJson(collectBackupData())
            writeJsonToDownloads(context, fileName, json)
                ?: error("Failed to write backup file to Downloads")
        }
    }

    /**
     * 从指定 [uri] 读取明文 JSON 备份并解析为 [BackupData]。
     *
     * 注意：本方法仅解析数据，不写回数据库。调用方可通过 [restoreBackup] 落库。
     *
     * @param context 任意 Context（用于 ContentResolver）。
     * @param uri 备份文件 URI（如通过 SAF OpenDocument 获取）。
     * @return 成功返回 [BackupData]，失败返回包含异常的 [Result]。
     */
    suspend fun importFromFile(context: Context, uri: Uri): Result<BackupData> = withContext(Dispatchers.IO) {
        runCatching {
            val json = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("Unable to open input stream for URI: $uri")
            parseBackupJson(json)
        }
    }

    // ── 加密导出 / 导入 ──

    /**
     * 将完整备份加密后写入 Downloads 目录。
     *
     * 加密流程：
     *  1. 收集 [BackupData] 并序列化为 JSON；
     *  2. 调用 [KeystoreManager.encrypt] 使用设备绑定的 AES-256-GCM 密钥加密；
     *  3. 将密文写入文件。
     *
     * 加密后的文件只能在持有相同 Keystore 密钥的设备上解密，适合作为设备本地
     * 的安全备份（如上传至用户私有的云存储）。
     *
     * @param context 任意 Context（用于 ContentResolver）。
     * @param fileName 目标文件名（不含路径）。
     * @return 成功返回文件 [Uri]，失败返回包含异常的 [Result]。
     */
    suspend fun exportEncrypted(context: Context, fileName: String): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            val json = buildBackupJson(collectBackupData())
            val encrypted = KeystoreManager.encrypt(json)
            writeJsonToDownloads(context, fileName, encrypted)
                ?: error("Failed to write encrypted backup file to Downloads")
        }
    }

    /**
     * 从指定 [uri] 读取加密备份并解密为 [BackupData]。
     *
     * 解密流程：
     *  1. 读取文件内容为字符串；
     *  2. 调用 [KeystoreManager.decrypt] 使用设备绑定的密钥解密；
     *  3. 将解密后的 JSON 解析为 [BackupData]。
     *
     * 若文件并非加密格式（无 `AKS:` 前缀），将返回失败 —— 调用方应改用 [importFromFile]。
     *
     * @param context 任意 Context（用于 ContentResolver）。
     * @param uri 加密备份文件 URI。
     * @return 成功返回 [BackupData]，失败返回包含异常的 [Result]。
     */
    suspend fun importEncrypted(context: Context, uri: Uri): Result<BackupData> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("Unable to open input stream for URI: $uri")
            val json = KeystoreManager.decrypt(payload)
                ?: error("Decryption failed — file is not an encrypted backup or key mismatch")
            parseBackupJson(json)
        }
    }

    // ── 自动备份调度 ──

    /**
     * 读取当前自动备份调度。
     *
     * 内部读取 [SettingsDataStore.autoBackupSchedule] 的最新值并映射为枚举。
     *
     * @return 当前生效的 [BackupSchedule]，未设置时返回 [BackupSchedule.MANUAL]。
     */
    suspend fun getAutoBackupSchedule(): BackupSchedule = withContext(Dispatchers.IO) {
        BackupSchedule.fromStorageValue(dataStore.autoBackupSchedule.first())
    }

    // ── 落库恢复 ──

    /**
     * 将 [BackupData] 中的会话与消息原子写入数据库。
     *
     * 使用 Room 事务保证原子性：任一插入失败则整体回滚。
     * Agent 配置与设置不在此处自动恢复，避免覆盖用户当前配置；调用方可按需单独处理。
     *
     * @param data 已解析的 [BackupData]。
     */
    suspend fun restoreBackup(data: BackupData) = withContext(Dispatchers.IO) {
        repository.importBackup(data.sessions, data.messages)
    }

    // ── 内部辅助 ──

    /**
     * 收集当前应用状态的完整快照。
     *
     * - sessions / messages / agentConfigs 来自 [ChatRepository]；
     * - settings 来自 [SettingsDataStore]（一次性收集所有相关 Flow 的当前值）。
     */
    private suspend fun collectBackupData(): BackupData = withContext(Dispatchers.IO) {
        val sessions = repository.getAllSessionsList()
        val messages = sessions.flatMap { repository.getMessagesBySessionList(it.id) }
        val agentConfigs = repository.getAllConfigsList()
        val settings = BackupSettings(
            themeMode = dataStore.themeMode.first(),
            fontSize = dataStore.fontSize.first(),
            e2eEnabled = dataStore.e2eEnabled.first(),
            autoBackup = dataStore.autoBackupSchedule.first()
        )
        BackupData(
            version = BACKUP_FORMAT_VERSION,
            timestamp = System.currentTimeMillis(),
            sessions = sessions,
            messages = messages,
            agentConfigs = agentConfigs,
            settings = settings
        )
    }

    /** 将 [BackupData] 序列化为 JSON 字符串。 */
    private fun buildBackupJson(data: BackupData): String = gson.toJson(data)

    /** 将 JSON 字符串反序列化为 [BackupData]；缺失字段使用默认值。 */
    private fun parseBackupJson(json: String): BackupData =
        gson.fromJson(json, BackupData::class.java) ?: error("Failed to parse backup JSON")

    /**
     * 将字符串内容写入 Downloads 目录。
     *
     * - API 29+：使用 MediaStore.Downloads，无需任何存储权限；
     * - 旧版本：使用 legacy `Environment.getExternalStoragePublicDirectory`，
     *   调用方需在 Manifest 声明 WRITE_EXTERNAL_STORAGE 权限。
     *
     * @return 成功返回写入文件的 [Uri]，失败返回 `null`。
     */
    private fun writeJsonToDownloads(context: Context, fileName: String, content: String): Uri? {
        val resolver = context.contentResolver
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            uri?.let {
                resolver.openOutputStream(it)?.use { os ->
                    os.write(content.toByteArray())
                }
            }
            uri
        } else {
            @Suppress("DEPRECATION")
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(downloads, fileName)
            file.writeText(content)
            Uri.fromFile(file)
        }
    }

    companion object {
        /** 备份格式版本号，用于跨版本兼容判断。 */
        const val BACKUP_FORMAT_VERSION: String = "3.0.0"
    }
}
