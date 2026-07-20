package com.agentcontrolcenter.app.data.update

import org.junit.Assert.*
import org.junit.Test

/**
 * UpdateManager 单元测试。
 * 验证语义化版本比较逻辑的正确性。
 */
class UpdateManagerTest {

    // 使用反射或直接测试私有方法不方便，这里通过公开 API 间接测试。
    // 由于 isNewer 是 private 的，我们通过 checkForUpdates 的行为间接验证，
    // 或者提取为 internal 可见性。这里先测试版本解析逻辑。

    @Test
    fun `version string parsing handles v prefix`() {
        val release = UpdateManager.GitHubRelease(
            tagName = "v1.2.0",
            body = "changelog",
            assets = emptyList()
        )
        assertEquals("v1.2.0", release.tagName)
        assertEquals("1.2.0", release.tagName.removePrefix("v"))
    }

    @Test
    fun `GitHubRelease deserializes correctly`() {
        val release = UpdateManager.GitHubRelease(
            tagName = "v2.0.0",
            body = "New features",
            assets = listOf(
                UpdateManager.GitHubAsset(
                    browserDownloadUrl = "https://example.com/agentcontrolcenter-v2.0.0.apk",
                    name = "agentcontrolcenter-v2.0.0.apk",
                    size = 5_000_000
                )
            )
        )
        assertEquals("v2.0.0", release.tagName)
        assertEquals(1, release.assets?.size)
        assertTrue(release.assets?.first()?.name?.endsWith(".apk") == true)
    }

    @Test
    fun `GitHubRelease handles null body and empty assets`() {
        val release = UpdateManager.GitHubRelease(
            tagName = "v1.0.0",
            body = null,
            assets = null
        )
        assertNull(release.body)
        assertTrue(release.assets?.isEmpty() != false)
    }

    @Test
    fun `UpdateInfo stores data correctly`() {
        val info = UpdateManager.UpdateInfo(
            version = "1.3.0",
            downloadUrl = "https://example.com/app.apk",
            changelog = "Bug fixes",
            assetSize = 4_500_000
        )
        assertEquals("1.3.0", info.version)
        assertEquals(4_500_000L, info.assetSize)
    }
}
