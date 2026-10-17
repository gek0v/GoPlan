package com.goplan.app.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.goplan.app.BuildConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object VersionComparator {
    fun isNewerVersion(current: String, candidate: String): Boolean {
        val currentParts = parseVersion(current)
        val candidateParts = parseVersion(candidate)
        val maxLen = maxOf(currentParts.size, candidateParts.size)
        for (i in 0 until maxLen) {
            val curr = currentParts.getOrElse(i) { 0 }
            val cand = candidateParts.getOrElse(i) { 0 }
            if (cand > curr) return true
            if (cand < curr) return false
        }
        return false
    }

    fun parseVersion(version: String): List<Int> {
        val clean = version.trim().removePrefix("v").removePrefix("V")
        val mainPart = clean.substringBefore("-").substringBefore("+")
        return mainPart.split(".").mapNotNull { it.toIntOrNull() }
    }
}

interface ReleaseApiClient {
    suspend fun fetchLatestRelease(): String
    suspend fun downloadFile(url: String, destinationFile: File, onProgress: (Float, Long, Long) -> Unit)
}

class DefaultReleaseApiClient(
    private val repoOwner: String = "gek0v",
    private val repoName: String = "GoPlan",
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ReleaseApiClient {

    override suspend fun fetchLatestRelease(): String = withContext(ioDispatcher) {
        val url = URL("https://api.github.com/repos/$repoOwner/$repoName/releases/latest")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github.v3+json")
            setRequestProperty("User-Agent", "GoPlan-Android-App")
            connectTimeout = 10_000
            readTimeout = 15_000
        }

        try {
            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                throw IllegalStateException("GitHub API returned HTTP $responseCode")
            }
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun downloadFile(
        url: String,
        destinationFile: File,
        onProgress: (Float, Long, Long) -> Unit
    ): Unit = withContext(ioDispatcher) {
        destinationFile.parentFile?.mkdirs()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", "GoPlan-Android-App")
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }

        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IllegalStateException("Download returned HTTP $responseCode")
            }
            val totalBytes = connection.contentLengthLong
            var downloadedBytes = 0L

            connection.inputStream.use { input ->
                FileOutputStream(destinationFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        val progress = if (totalBytes > 0) {
                            downloadedBytes.toFloat() / totalBytes.toFloat()
                        } else {
                            0f
                        }
                        onProgress(progress, downloadedBytes, totalBytes)
                    }
                    output.flush()
                }
            }
        } finally {
            connection.disconnect()
        }
    }
}

class AppUpdateManager(
    private val context: Context? = null,
    private val apiClient: ReleaseApiClient = DefaultReleaseApiClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val cacheDirProvider: () -> File = {
        context?.cacheDir ?: File(System.getProperty("java.io.tmpdir"), "goplan_cache")
    }
) {
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        fun isValidApkHeader(bytes: ByteArray): Boolean {
            return bytes.size >= 4 &&
                    bytes[0] == 0x50.toByte() &&
                    bytes[1] == 0x4B.toByte() &&
                    bytes[2] == 0x03.toByte() &&
                    bytes[3] == 0x04.toByte()
        }

        fun getUpdatesDir(context: Context): File {
            val dir = File(context.cacheDir, "updates")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            return dir
        }
    }

    suspend fun checkForUpdates(
        currentVersion: String = BuildConfig.VERSION_NAME
    ): UpdateCheckResult = withContext(ioDispatcher) {
        try {
            val jsonResponse = apiClient.fetchLatestRelease()
            val release = json.decodeFromString<GitHubReleaseResponse>(jsonResponse)

            if (release.draft || release.prerelease) {
                return@withContext UpdateCheckResult.UpToDate
            }

            val isNewer = VersionComparator.isNewerVersion(currentVersion, release.tag_name)
            if (!isNewer) {
                return@withContext UpdateCheckResult.UpToDate
            }

            // Find APK asset
            val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
                ?: return@withContext UpdateCheckResult.Error("No se encontró ningún archivo APK en la versión ${release.tag_name}")

            val updateInfo = UpdateInfo(
                versionName = release.tag_name.removePrefix("v").removePrefix("V"),
                releaseNotes = release.body ?: release.name ?: "Nueva actualización disponible.",
                downloadUrl = apkAsset.browser_download_url,
                apkSize = apkAsset.size,
                publishedAt = release.published_at ?: ""
            )

            UpdateCheckResult.UpdateAvailable(updateInfo)
        } catch (e: Exception) {
            UpdateCheckResult.Error(e.message ?: "Error al comprobar actualizaciones")
        }
    }

    suspend fun downloadUpdate(
        updateInfo: UpdateInfo,
        onProgress: (Float, Long, Long) -> Unit
    ): Result<File> = withContext(ioDispatcher) {
        try {
            val updatesDir = File(cacheDirProvider(), "updates").apply { mkdirs() }
            val destinationFile = File(updatesDir, "GoPlan-${updateInfo.versionName}.apk")

            apiClient.downloadFile(updateInfo.downloadUrl, destinationFile, onProgress)

            // Validate header
            val header = ByteArray(4)
            destinationFile.inputStream().use { it.read(header) }
            if (!isValidApkHeader(header)) {
                destinationFile.delete()
                return@withContext Result.failure(IllegalStateException("El archivo descargado no es un APK válido."))
            }

            Result.success(destinationFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun canInstallPackages(): Boolean {
        val ctx = context ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun createInstallPermissionIntent(): Intent? {
        val ctx = context ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !canInstallPackages()) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${ctx.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            null
        }
    }

    fun createInstallIntent(apkFile: File): Intent? {
        val ctx = context ?: return null
        val authority = "${ctx.packageName}.fileprovider"
        val apkUri: Uri = FileProvider.getUriForFile(ctx, authority, apkFile)

        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun promptInstall(apkFile: File): Result<Unit> {
        return try {
            val ctx = context ?: return Result.failure(IllegalStateException("Context no disponible"))
            if (!apkFile.exists()) {
                return Result.failure(IllegalStateException("El archivo APK no existe: ${apkFile.absolutePath}"))
            }

            val permissionIntent = createInstallPermissionIntent()
            if (permissionIntent != null) {
                ctx.startActivity(permissionIntent)
                return Result.failure(SecurityException("Se requiere conceder permiso para instalar aplicaciones desconocidas."))
            }

            val installIntent = createInstallIntent(apkFile)
                ?: return Result.failure(IllegalStateException("No se pudo crear el Intent de instalación"))
            ctx.startActivity(installIntent)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun handleIncomingApk(apkBytes: ByteArray): Result<File> {
        return try {
            val updatesDir = File(cacheDirProvider(), "updates").apply { mkdirs() }
            val targetFile = File(updatesDir, "update_incoming.apk")
            targetFile.writeBytes(apkBytes)
            // If context is present, launch installer prompt
            if (context != null) {
                promptInstall(targetFile)
            }
            Result.success(targetFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
