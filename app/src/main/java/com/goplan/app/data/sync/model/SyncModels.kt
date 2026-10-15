package com.goplan.app.data.sync.model

data class SyncState(
    val lastSyncMillis: Long,
    val fileHashes: Map<String, String> = emptyMap()
)

sealed class SyncResult {
    data class Success(
        val uploadedCount: Int,
        val downloadedCount: Int,
        val conflictsCount: Int
    ) : SyncResult()

    data class Error(
        val message: String,
        val throwable: Throwable? = null
    ) : SyncResult()
}

data class RemoteFileInfo(
    val id: String,
    val name: String,
    val md5Checksum: String? = null,
    val modifiedTimeMillis: Long = 0L,
    val mimeType: String? = null
)

data class SyncItemMetadata(
    val relativePath: String,
    val sha256: String,
    val lastModifiedMillis: Long = 0L
)
