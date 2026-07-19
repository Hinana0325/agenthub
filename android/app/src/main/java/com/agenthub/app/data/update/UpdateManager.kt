package com.agenthub.app.data.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches the latest GitHub release, compares it against the installed version,
 * and launches a [DownloadManager] request (user installs via system notification).
 */
class UpdateManager(
    private val githubRepo: String = "Hinana0325/agenthub"
) {
    private val apiUrl = "https://api.github.com/repos/$githubRepo/releases/latest"

    /** Result of a release check: either an available update, an error, or null (up-to-date). */
    data class UpdateInfo(
        val version: String,
        val downloadUrl: String,
        val changelog: String,
        val assetSize: Long = 0
    )

    data class GitHubRelease(
        @SerializedName("tag_name") val tagName: String,
        @SerializedName("body") val body: String?,
        @SerializedName("assets") val assets: List<GitHubAsset>? = emptyList()
    )

    data class GitHubAsset(
        @SerializedName("browser_download_url") val browserDownloadUrl: String,
        @SerializedName("name") val name: String,
        @SerializedName("size") val size: Long
    )

    /** Checks GitHub for a newer release. Returns [UpdateInfo] if one is available, null if up-to-date. */
    suspend fun checkForUpdates(currentVersion: String): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                setRequestProperty("Accept", "application/vnd.github+json")
                connectTimeout = 10_000
                readTimeout = 10_000
            }

            if (connection.responseCode == 403) {
                // Rate-limited or blocked — gracefully report.
                return@withContext Result.failure(Exception("GitHub API rate-limited; try again later."))
            }

            val json = InputStreamReader(connection.inputStream).use { it.readText() }

            val release = Gson().fromJson(json, GitHubRelease::class.java)
            val latestVersion = release.tagName.removePrefix("v")

            if (isNewer(latestVersion, currentVersion)) {
                val apkAsset = release.assets?.find {
                    it.name.endsWith(".apk") && it.browserDownloadUrl.isNotBlank()
                }
                if (apkAsset != null) {
                    Result.success(
                        UpdateInfo(
                            version = latestVersion,
                            downloadUrl = apkAsset.browserDownloadUrl,
                            changelog = release.body ?: "",
                            assetSize = apkAsset.size
                        )
                    )
                } else {
                    Result.success(null) // tag is newer but no APK asset
                }
            } else {
                Result.success(null) // already up-to-date
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            // Critical 2 修复：无论成功还是异常都 disconnect，避免 HttpURLConnection 泄漏。
            connection?.disconnect()
        }
    }

    /** Enqueues the APK download through [DownloadManager]; the user taps the system
     *  notification to install. */
    fun downloadUpdate(context: Context, info: UpdateInfo) {
        val filename = "agenthub-v${info.version}.apk"
        val request = DownloadManager.Request(Uri.parse(info.downloadUrl))
            .setTitle("AgentHub v${info.version}")
            .setDescription("Downloading update…")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
            .setMimeType("application/vnd.android.package-archive")

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.enqueue(request)
        // Critical 2 修复：downloadUpdate 可能从 IO 线程调用，Toast 必须在主线程执行，
        // 否则抛 RuntimeException。用 Handler post 到主线程。
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, "Download started — check your notifications", Toast.LENGTH_SHORT).show()
        }
    }

    /** Opens the downloaded APK directly via [FileProvider] (alternative to notification tap). */
    fun installApk(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * Simple semantic version comparison (e.g. "1.2.0" vs "1.1.5").
     * Returns true if [a] is strictly greater than [b].
     */
    private fun isNewer(a: String, b: String): Boolean {
        if (a == b) return false
        val pa = a.split(".").map { it.toIntOrNull() ?: 0 }
        val pb = b.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val va = pa.getOrElse(i) { 0 }
            val vb = pb.getOrElse(i) { 0 }
            if (va != vb) return va > vb
        }
        return false
    }
}
