package com.agentcontrolcenter.app.transport.comfyui

import android.util.Base64
import android.util.Log
import com.agentcontrolcenter.app.core.security.UrlValidator
import com.google.gson.JsonObject
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.readRawBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import java.io.IOException

/**
 * ComfyUI 图片输出信息（filename + subfolder + type）。
 *
 * 通过 `/view` 端点下载，对应查询参数与字段同名。
 */
internal data class ComfyImageOutput(
    val filename: String,
    val subfolder: String,
    val type: String
) {
    /**
     * 下载图片并转为 markdown data URI。
     *
     * 返回 `![ComfyUI](data:image/png;base64,...)` 或 null（下载失败）。
     */
    suspend fun toMarkdown(base: String, apiKey: String, client: HttpClient): String? {
        val viewUrl = "$base/view?filename=" +
            java.net.URLEncoder.encode(filename, "UTF-8") +
            "&subfolder=" + java.net.URLEncoder.encode(subfolder, "UTF-8") +
            "&type=$type"
        if (UrlValidator.validate(viewUrl, allowLocalhost = true) == null) return null
        return try {
            val response = withTimeout(30_000) {
                client.get(viewUrl) {
                    if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
                }
            }
            if (response.status.value !in 200..299) return null
            val bytes = response.readRawBytes()
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            "![ComfyUI](data:image/png;base64,$base64)"
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w("ComfyImageOutput", "Failed to download image $filename: ${e.message}")
            null
        }
    }
}

/**
 * 从 ComfyUI `/history/{prompt_id}` 响应中提取所有图片输出。
 *
 * history 响应格式：
 * ```
 * {
 *   "<prompt_id>": {
 *     "outputs": {
 *       "9": {  // SaveImage 节点 ID
 *         "images": [
 *           { "filename": "ComfyUI_00001_.png", "subfolder": "", "type": "output" }
 *         ]
 *       }
 *     }
 *   }
 * }
 * ```
 */
internal object ComfyImageOutputExtractor {

    fun extract(promptEntry: JsonObject): List<ComfyImageOutput> {
        val outputs = promptEntry.getAsJsonObject("outputs") ?: return emptyList()
        val images = mutableListOf<ComfyImageOutput>()
        for ((_, nodeOutput) in outputs.entrySet()) {
            val outputObj = nodeOutput.asJsonObject
            val imagesArray = outputObj.getAsJsonArray("images") ?: continue
            for (imgElement in imagesArray) {
                val img = imgElement.asJsonObject
                val filename = img.get("filename")?.asString ?: continue
                val subfolder = img.get("subfolder")?.asString ?: ""
                val type = img.get("type")?.asString ?: "output"
                images.add(ComfyImageOutput(filename, subfolder, type))
            }
        }
        return images
    }
}
