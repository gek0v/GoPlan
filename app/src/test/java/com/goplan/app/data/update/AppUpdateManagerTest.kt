package com.goplan.app.data.update

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AppUpdateManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testVersionComparison_newerVersions() {
        assertTrue(VersionComparator.isNewerVersion("1.0.0", "1.0.1"))
        assertTrue(VersionComparator.isNewerVersion("1.0.0", "v1.0.1"))
        assertTrue(VersionComparator.isNewerVersion("0.9.9", "1.0.0"))
        assertTrue(VersionComparator.isNewerVersion("1.0.0", "1.1.0"))
        assertTrue(VersionComparator.isNewerVersion("1.0.0", "2.0.0"))
        assertTrue(VersionComparator.isNewerVersion("1.0", "1.0.1"))
    }

    @Test
    fun testVersionComparison_sameOrOlderVersions() {
        assertFalse(VersionComparator.isNewerVersion("1.0.0", "1.0.0"))
        assertFalse(VersionComparator.isNewerVersion("v1.0.0", "1.0.0"))
        assertFalse(VersionComparator.isNewerVersion("1.0.1", "1.0.0"))
        assertFalse(VersionComparator.isNewerVersion("2.0.0", "1.9.9"))
        assertFalse(VersionComparator.isNewerVersion("1.1.0", "1.0.9"))
    }

    @Test
    fun testApkHeaderValidation() {
        val validZipHeader = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00)
        assertTrue(AppUpdateManager.isValidApkHeader(validZipHeader))

        val invalidHeader = byteArrayOf(0x7F, 0x45, 0x4C, 0x46)
        assertFalse(AppUpdateManager.isValidApkHeader(invalidHeader))

        val shortHeader = byteArrayOf(0x50, 0x4B)
        assertFalse(AppUpdateManager.isValidApkHeader(shortHeader))
    }

    @Test
    fun testCheckForUpdates_updateAvailable() = runTest {
        val jsonPayload = """
            {
              "tag_name": "v1.1.0",
              "name": "Release 1.1.0",
              "body": "Fixed background sync and added Wi-Fi update.",
              "draft": false,
              "prerelease": false,
              "published_at": "2026-10-17T12:00:00Z",
              "assets": [
                {
                  "name": "app-debug.apk",
                  "browser_download_url": "https://github.com/gek0v/GoPlan/releases/download/v1.1.0/app-debug.apk",
                  "size": 33400000,
                  "content_type": "application/vnd.android.package-archive"
                }
              ]
            }
        """.trimIndent()

        val mockClient = object : ReleaseApiClient {
            override suspend fun fetchLatestRelease(): String = jsonPayload
            override suspend fun downloadFile(url: String, destinationFile: File, onProgress: (Float, Long, Long) -> Unit) {}
        }

        val cacheDir = tempFolder.newFolder("cache")
        val updateManager = AppUpdateManager(
            context = null,
            apiClient = mockClient,
            cacheDirProvider = { cacheDir }
        )

        val result = updateManager.checkForUpdates(currentVersion = "1.0.0")
        assertTrue("Expected UpdateAvailable but got $result", result is UpdateCheckResult.UpdateAvailable)
        val available = result as UpdateCheckResult.UpdateAvailable
        assertEquals("1.1.0", available.updateInfo.versionName)
        assertEquals("Fixed background sync and added Wi-Fi update.", available.updateInfo.releaseNotes)
        assertEquals("https://github.com/gek0v/GoPlan/releases/download/v1.1.0/app-debug.apk", available.updateInfo.downloadUrl)
    }

    @Test
    fun testCheckForUpdates_upToDate() = runTest {
        val jsonPayload = """
            {
              "tag_name": "v1.0.0",
              "name": "Release 1.0.0",
              "assets": [
                {
                  "name": "app-debug.apk",
                  "browser_download_url": "https://example.com/app.apk",
                  "size": 1000
                }
              ]
            }
        """.trimIndent()

        val mockClient = object : ReleaseApiClient {
            override suspend fun fetchLatestRelease(): String = jsonPayload
            override suspend fun downloadFile(url: String, destinationFile: File, onProgress: (Float, Long, Long) -> Unit) {}
        }

        val cacheDir = tempFolder.newFolder("cache")
        val updateManager = AppUpdateManager(
            context = null,
            apiClient = mockClient,
            cacheDirProvider = { cacheDir }
        )

        val result = updateManager.checkForUpdates(currentVersion = "1.0.0")
        assertEquals(UpdateCheckResult.UpToDate, result)
    }

    @Test
    fun testDownloadUpdate_validApk() = runTest {
        val cacheDir = tempFolder.newFolder("cache")

        val mockClient = object : ReleaseApiClient {
            override suspend fun fetchLatestRelease(): String = ""
            override suspend fun downloadFile(url: String, destinationFile: File, onProgress: (Float, Long, Long) -> Unit) {
                destinationFile.parentFile?.mkdirs()
                destinationFile.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0x00))
                onProgress(1.0f, 6, 6)
            }
        }

        val updateManager = AppUpdateManager(
            context = null,
            apiClient = mockClient,
            cacheDirProvider = { cacheDir }
        )
        val updateInfo = UpdateInfo(
            versionName = "1.2.0",
            releaseNotes = "Test notes",
            downloadUrl = "https://example.com/app.apk",
            apkSize = 6,
            publishedAt = ""
        )

        var reportedProgress = 0f
        val downloadResult = updateManager.downloadUpdate(updateInfo) { progress, _, _ ->
            reportedProgress = progress
        }

        assertTrue(downloadResult.isSuccess)
        assertEquals(1.0f, reportedProgress, 0.01f)
        val file = downloadResult.getOrThrow()
        assertTrue(file.exists())
        assertEquals("GoPlan-1.2.0.apk", file.name)
    }

    @Test
    fun testDownloadUpdate_invalidHeaderFails() = runTest {
        val cacheDir = tempFolder.newFolder("cache")

        val mockClient = object : ReleaseApiClient {
            override suspend fun fetchLatestRelease(): String = ""
            override suspend fun downloadFile(url: String, destinationFile: File, onProgress: (Float, Long, Long) -> Unit) {
                destinationFile.parentFile?.mkdirs()
                destinationFile.writeBytes(byteArrayOf(0x00, 0x00, 0x00, 0x00))
            }
        }

        val updateManager = AppUpdateManager(
            context = null,
            apiClient = mockClient,
            cacheDirProvider = { cacheDir }
        )
        val updateInfo = UpdateInfo(
            versionName = "1.2.0",
            releaseNotes = "Test notes",
            downloadUrl = "https://example.com/app.apk",
            apkSize = 4,
            publishedAt = ""
        )

        val downloadResult = updateManager.downloadUpdate(updateInfo) { _, _, _ -> }
        assertTrue(downloadResult.isFailure)
    }
}
