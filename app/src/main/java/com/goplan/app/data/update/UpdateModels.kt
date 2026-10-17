package com.goplan.app.data.update

import kotlinx.serialization.Serializable

@Serializable
data class GitHubReleaseAsset(
    val name: String = "",
    val browser_download_url: String = "",
    val size: Long = 0L,
    val content_type: String = ""
)

@Serializable
data class GitHubReleaseResponse(
    val tag_name: String = "",
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val published_at: String? = null,
    val assets: List<GitHubReleaseAsset> = emptyList()
)

data class UpdateInfo(
    val versionName: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val apkSize: Long,
    val publishedAt: String
)

sealed class UpdateCheckResult {
    data class UpdateAvailable(val updateInfo: UpdateInfo) : UpdateCheckResult()
    data object UpToDate : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}

sealed class UpdateDownloadState {
    data object Idle : UpdateDownloadState()
    data class Downloading(val progress: Float, val bytesDownloaded: Long, val totalBytes: Long) : UpdateDownloadState()
    data class ReadyToInstall(val apkPath: String) : UpdateDownloadState()
    data class Error(val message: String) : UpdateDownloadState()
}
