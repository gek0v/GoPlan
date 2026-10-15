package com.goplan.app.data.sync

import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.api.client.http.ByteArrayContent
import com.google.api.services.drive.Drive
import com.goplan.app.data.repository.WorkspaceRepository
import com.goplan.app.data.sync.model.RemoteFileInfo
import com.goplan.app.data.sync.model.SyncResult
import com.goplan.app.data.sync.model.SyncState
import com.goplan.app.domain.model.Idea
import com.goplan.app.domain.model.IdeaStatus
import com.goplan.app.domain.model.PlanLink
import com.goplan.app.domain.model.TodoDocument
import com.goplan.app.domain.model.TodoLine
import com.goplan.app.domain.model.Workspace
import com.goplan.app.domain.parser.TodoMarkdownParser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

interface DriveServiceAdapter {
    fun findFolder(name: String, parentId: String?): RemoteFileInfo?
    fun createFolder(name: String, parentId: String?): RemoteFileInfo
    fun listFiles(folderId: String): List<RemoteFileInfo>
    fun downloadFile(fileId: String): String
    fun uploadFile(name: String, parentId: String, content: String, mimeType: String = "text/markdown"): RemoteFileInfo
    fun updateFile(fileId: String, content: String): RemoteFileInfo
}

class GoogleApiDriveServiceAdapter(private val drive: Drive) : DriveServiceAdapter {
    override fun findFolder(name: String, parentId: String?): RemoteFileInfo? {
        val escapedName = name.replace("'", "\\'")
        val query = "mimeType = 'application/vnd.google-apps.folder' and name = '$escapedName' and trashed = false" +
                if (parentId != null) " and '$parentId' in parents" else " and 'root' in parents"
        val result = drive.files().list()
            .setQ(query)
            .setSpaces("drive")
            .setFields("files(id, name, md5Checksum, modifiedTime, mimeType)")
            .execute()
        val file = result.files?.firstOrNull() ?: return null
        return RemoteFileInfo(
            id = file.id,
            name = file.name,
            md5Checksum = file.md5Checksum,
            modifiedTimeMillis = file.modifiedTime?.value ?: 0L,
            mimeType = file.mimeType
        )
    }

    override fun createFolder(name: String, parentId: String?): RemoteFileInfo {
        val folderMetadata = com.google.api.services.drive.model.File().apply {
            this.name = name
            this.mimeType = "application/vnd.google-apps.folder"
            if (parentId != null) {
                this.parents = listOf(parentId)
            }
        }
        val created = drive.files().create(folderMetadata)
            .setFields("id, name, md5Checksum, modifiedTime, mimeType")
            .execute()
        return RemoteFileInfo(
            id = created.id,
            name = created.name,
            md5Checksum = created.md5Checksum,
            modifiedTimeMillis = created.modifiedTime?.value ?: 0L,
            mimeType = created.mimeType
        )
    }

    override fun listFiles(folderId: String): List<RemoteFileInfo> {
        val query = "'$folderId' in parents and trashed = false"
        val result = drive.files().list()
            .setQ(query)
            .setSpaces("drive")
            .setFields("files(id, name, md5Checksum, modifiedTime, mimeType)")
            .execute()
        return result.files?.map { file ->
            RemoteFileInfo(
                id = file.id,
                name = file.name,
                md5Checksum = file.md5Checksum,
                modifiedTimeMillis = file.modifiedTime?.value ?: 0L,
                mimeType = file.mimeType
            )
        } ?: emptyList()
    }

    override fun downloadFile(fileId: String): String {
        val outputStream = ByteArrayOutputStream()
        drive.files().get(fileId).executeMediaAndDownloadTo(outputStream)
        return outputStream.toString("UTF-8")
    }

    override fun uploadFile(name: String, parentId: String, content: String, mimeType: String): RemoteFileInfo {
        val fileMetadata = com.google.api.services.drive.model.File().apply {
            this.name = name
            this.parents = listOf(parentId)
        }
        val mediaContent = ByteArrayContent.fromString(mimeType, content)
        val created = drive.files().create(fileMetadata, mediaContent)
            .setFields("id, name, md5Checksum, modifiedTime, mimeType")
            .execute()
        return RemoteFileInfo(
            id = created.id,
            name = created.name,
            md5Checksum = created.md5Checksum,
            modifiedTimeMillis = created.modifiedTime?.value ?: 0L,
            mimeType = created.mimeType
        )
    }

    override fun updateFile(fileId: String, content: String): RemoteFileInfo {
        val mediaContent = ByteArrayContent.fromString("text/markdown", content)
        val updated = drive.files().update(fileId, null, mediaContent)
            .setFields("id, name, md5Checksum, modifiedTime, mimeType")
            .execute()
        return RemoteFileInfo(
            id = updated.id,
            name = updated.name,
            md5Checksum = updated.md5Checksum,
            modifiedTimeMillis = updated.modifiedTime?.value ?: 0L,
            mimeType = updated.mimeType
        )
    }
}

class GoogleDriveSyncEngine(
    val repository: WorkspaceRepository,
    val authManager: GoogleDriveAuthManager,
    val parser: TodoMarkdownParser = TodoMarkdownParser(),
    private val customAdapter: DriveServiceAdapter? = null,
    private val driveServiceProvider: ((GoogleSignInAccount) -> Drive)? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val syncStateCache = ConcurrentHashMap<String, SyncState>()

    fun computeSha256(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(content.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    fun mergeTodoDocuments(local: TodoDocument, remote: TodoDocument): TodoDocument {
        if (local.lines.isEmpty()) return remote
        if (remote.lines.isEmpty()) return local

        val localTasks = local.lines.filterIsInstance<TodoLine.Task>().map { it.idea }
        val remoteTasks = remote.lines.filterIsInstance<TodoLine.Task>().map { it.idea }

        fun normalizeTitle(title: String): String =
            title.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-")

        fun matches(a: Idea, b: Idea): Boolean {
            if (a.id.isNotBlank() && b.id.isNotBlank() && a.id == b.id) return true
            val normA = normalizeTitle(a.title)
            val normB = normalizeTitle(b.title)
            return normA.isNotEmpty() && normA == normB
        }

        fun mergeStatus(status1: IdeaStatus, status2: IdeaStatus): IdeaStatus {
            fun rank(status: IdeaStatus): Int = when (status) {
                IdeaStatus.COMPLETED -> 4
                IdeaStatus.IN_PROGRESS -> 3
                IdeaStatus.PENDING -> 2
                IdeaStatus.DISCARDED -> 1
            }
            return if (rank(status1) >= rank(status2)) status1 else status2
        }

        fun mergeIdeas(localIdea: Idea, remoteIdea: Idea): Idea {
            val mergedStatus = mergeStatus(localIdea.status, remoteIdea.status)
            val mergedTitle = if (remoteIdea.title.isNotBlank() && remoteIdea.title.length > localIdea.title.length) {
                remoteIdea.title
            } else {
                localIdea.title
            }
            val mergedTags = (localIdea.tags + remoteIdea.tags).toSortedSet()

            val combinedPlans = mutableListOf<PlanLink>()
            val seenPaths = mutableSetOf<String>()
            for (plan in localIdea.plans + remoteIdea.plans) {
                if (seenPaths.add(plan.relativePath)) {
                    combinedPlans.add(plan)
                }
            }

            val id = if (localIdea.id.isNotBlank()) localIdea.id else remoteIdea.id
            val mergedIdea = Idea(
                id = id,
                status = mergedStatus,
                title = mergedTitle,
                tags = mergedTags,
                plans = combinedPlans,
                rawLine = ""
            )
            return mergedIdea.copy(rawLine = formatIdeaRawLine(mergedIdea))
        }

        val matchedRemoteIndices = mutableSetOf<Int>()
        val matchedRemoteIds = mutableSetOf<String>()

        val mergedLines = local.lines.map { line ->
            if (line is TodoLine.Task) {
                val remoteMatchIdx = remoteTasks.indexOfFirst { remoteIdea ->
                    matches(line.idea, remoteIdea)
                }
                if (remoteMatchIdx != -1) {
                    matchedRemoteIndices.add(remoteMatchIdx)
                    val remoteMatch = remoteTasks[remoteMatchIdx]
                    matchedRemoteIds.add(remoteMatch.id)
                    TodoLine.Task(mergeIdeas(line.idea, remoteMatch))
                } else {
                    line
                }
            } else {
                line
            }
        }.toMutableList()

        val remoteExclusiveTasks = remoteTasks.filterIndexed { index, idea ->
            index !in matchedRemoteIndices && idea.id !in matchedRemoteIds
        }

        if (remoteExclusiveTasks.isNotEmpty()) {
            val lastTaskIndex = mergedLines.indexOfLast { it is TodoLine.Task }
            val insertIndex = if (lastTaskIndex != -1) {
                lastTaskIndex + 1
            } else {
                val lastNonBlank = mergedLines.indexOfLast {
                    when (it) {
                        is TodoLine.Other -> it.rawText.isNotBlank()
                        else -> true
                    }
                }
                if (lastNonBlank != -1) lastNonBlank + 1 else mergedLines.size
            }

            val newLines = remoteExclusiveTasks.map { idea ->
                val formatted = if (idea.rawLine.isBlank()) formatIdeaRawLine(idea) else idea.rawLine
                TodoLine.Task(idea.copy(rawLine = formatted))
            }
            mergedLines.addAll(insertIndex, newLines)
        }

        return TodoDocument(lines = mergedLines, lineSeparator = local.lineSeparator)
    }

    private fun formatIdeaRawLine(idea: Idea): String {
        val markerPrefix = "- ${idea.status.marker}"
        val body = if (idea.plans.size == 1 && idea.plans[0].label.equals(idea.title, ignoreCase = true)) {
            "[${idea.title}](${idea.plans[0].relativePath})"
        } else if (idea.plans.size == 1) {
            "${idea.title} ([${idea.plans[0].label}](${idea.plans[0].relativePath}))"
        } else if (idea.plans.size > 1) {
            val linksString = idea.plans.joinToString(", ") { "[${it.label}](${it.relativePath})" }
            "${idea.title} ($linksString)"
        } else {
            idea.title
        }
        val tagsPart = if (idea.tags.isNotEmpty()) " " + idea.tags.joinToString(" ") { "#$it" } else ""
        val defaultId = idea.title.lowercase().replace(Regex("[^a-z0-9]+"), "-")
        val idPart = if (idea.id.isNotBlank() && idea.id != defaultId) " <!-- id:${idea.id} -->" else ""
        return "$markerPrefix $body$tagsPart$idPart"
    }

    suspend fun syncWorkspace(workspace: Workspace): SyncResult {
        val account = authManager.signedInAccount.value
            ?: return SyncResult.Error("No hay cuenta de Google vinculada")

        return withContext(ioDispatcher) {
            try {
                val adapter = customAdapter ?: run {
                    val drive = driveServiceProvider?.invoke(account) ?: authManager.getDriveService(account)
                    GoogleApiDriveServiceAdapter(drive)
                }

                var uploadedCount = 0
                var downloadedCount = 0
                var conflictsCount = 0

                val lastSyncState = loadSyncState(workspace)

                // 1. Root GoPlan folder
                var goPlanFolder = adapter.findFolder("GoPlan", null)
                if (goPlanFolder == null) {
                    goPlanFolder = adapter.createFolder("GoPlan", null)
                }

                // 2. Workspace subfolder
                var workspaceFolder = adapter.findFolder(workspace.name, goPlanFolder.id)
                if (workspaceFolder == null) {
                    workspaceFolder = adapter.createFolder(workspace.name, goPlanFolder.id)
                }

                // 3. planes subfolder
                var planesFolder = adapter.findFolder("planes", workspaceFolder.id)
                if (planesFolder == null) {
                    planesFolder = adapter.createFolder("planes", workspaceFolder.id)
                }

                val rootFiles = adapter.listFiles(workspaceFolder.id)
                val remoteContext = rootFiles.firstOrNull { it.name == "CONTEXT.md" }
                val remoteTodo = rootFiles.firstOrNull { it.name == "TODO.md" }

                // 4. Sync CONTEXT.md
                val localContextContent = repository.getContextContent(workspace)
                val localContextHash = computeSha256(localContextContent)
                val lastContextHash = lastSyncState?.fileHashes?.get("CONTEXT.md")

                if (remoteContext == null) {
                    adapter.uploadFile("CONTEXT.md", workspaceFolder.id, localContextContent, "text/markdown")
                    uploadedCount++
                } else {
                    val remoteContextContent = adapter.downloadFile(remoteContext.id)
                    val remoteContextHash = computeSha256(remoteContextContent)

                    if (localContextHash != remoteContextHash) {
                        if (lastContextHash != null && localContextHash == lastContextHash) {
                            repository.saveContextContent(workspace, remoteContextContent)
                            downloadedCount++
                        } else if (lastContextHash != null && remoteContextHash == lastContextHash) {
                            adapter.updateFile(remoteContext.id, localContextContent)
                            uploadedCount++
                        } else {
                            val timestamp = System.currentTimeMillis()
                            val conflictFile = File(workspace.rootDir, "CONTEXT.conflict-$timestamp.md")
                            conflictFile.writeText(localContextContent, Charsets.UTF_8)
                            repository.saveContextContent(workspace, remoteContextContent)
                            conflictsCount++
                            downloadedCount++
                        }
                    }
                }

                // 5. Sync TODO.md
                val localTodoDoc = repository.getTodoDocument(workspace)
                val localTodoContent = parser.serialize(localTodoDoc)
                val localTodoHash = computeSha256(localTodoContent)

                if (remoteTodo == null) {
                    adapter.uploadFile("TODO.md", workspaceFolder.id, localTodoContent, "text/markdown")
                    uploadedCount++
                } else {
                    val remoteTodoContent = adapter.downloadFile(remoteTodo.id)
                    val remoteTodoHash = computeSha256(remoteTodoContent)

                    if (localTodoHash != remoteTodoHash) {
                        val remoteDoc = parser.parse(remoteTodoContent)
                        val mergedDoc = mergeTodoDocuments(localTodoDoc, remoteDoc)
                        repository.saveTodoDocument(workspace, mergedDoc)
                        val mergedContent = parser.serialize(mergedDoc)
                        adapter.updateFile(remoteTodo.id, mergedContent)
                        downloadedCount++
                        uploadedCount++
                    }
                }

                // 6. Sync planes/ folder
                val planesDir = File(workspace.rootDir, "planes").apply { if (!exists()) mkdirs() }
                val localPlanFiles = planesDir.listFiles { f ->
                    f.isFile && !f.name.startsWith(".") && !f.name.contains(".conflict-")
                } ?: emptyArray()

                val remotePlanes = adapter.listFiles(planesFolder.id)
                val remotePlanesByName = remotePlanes.associateBy { it.name }

                for (planFile in localPlanFiles) {
                    val planName = planFile.name
                    val localPlanContent = planFile.readText(Charsets.UTF_8)
                    val localPlanHash = computeSha256(localPlanContent)
                    val relativePath = "planes/$planName"
                    val lastPlanHash = lastSyncState?.fileHashes?.get(relativePath)

                    val remotePlan = remotePlanesByName[planName]
                    if (remotePlan == null) {
                        adapter.uploadFile(planName, planesFolder.id, localPlanContent, "text/markdown")
                        uploadedCount++
                    } else {
                        val remotePlanContent = adapter.downloadFile(remotePlan.id)
                        val remotePlanHash = computeSha256(remotePlanContent)

                        if (localPlanHash != remotePlanHash) {
                            if (lastPlanHash != null && localPlanHash == lastPlanHash) {
                                planFile.writeText(remotePlanContent, Charsets.UTF_8)
                                downloadedCount++
                            } else if (lastPlanHash != null && remotePlanHash == lastPlanHash) {
                                adapter.updateFile(remotePlan.id, localPlanContent)
                                uploadedCount++
                            } else {
                                val timestamp = System.currentTimeMillis()
                                val nameWithoutExt = planName.substringBeforeLast(".")
                                val ext = if (planName.contains(".")) ".${planName.substringAfterLast(".")}" else ""
                                val conflictFile = File(planesDir, "$nameWithoutExt.conflict-$timestamp$ext")
                                conflictFile.writeText(localPlanContent, Charsets.UTF_8)
                                planFile.writeText(remotePlanContent, Charsets.UTF_8)
                                conflictsCount++
                                downloadedCount++
                            }
                        }
                    }
                }

                val localPlanNames = localPlanFiles.map { it.name }.toSet()
                for (remotePlan in remotePlanes) {
                    if (remotePlan.name !in localPlanNames && !remotePlan.name.contains(".conflict-")) {
                        val remotePlanContent = adapter.downloadFile(remotePlan.id)
                        repository.savePlanFile(workspace, remotePlan.name, remotePlanContent)
                        downloadedCount++
                    }
                }

                // 7. Save updated SyncState
                val currentHashes = mutableMapOf<String, String>()
                currentHashes["CONTEXT.md"] = computeSha256(repository.getContextContent(workspace))
                currentHashes["TODO.md"] = computeSha256(parser.serialize(repository.getTodoDocument(workspace)))
                val currentPlanesAfter = planesDir.listFiles { f ->
                    f.isFile && !f.name.startsWith(".") && !f.name.contains(".conflict-")
                } ?: emptyArray()
                for (pf in currentPlanesAfter) {
                    currentHashes["planes/${pf.name}"] = computeSha256(pf.readText(Charsets.UTF_8))
                }

                val newSyncState = SyncState(
                    lastSyncMillis = System.currentTimeMillis(),
                    fileHashes = currentHashes
                )
                saveSyncState(workspace, newSyncState)

                SyncResult.Success(
                    uploadedCount = uploadedCount,
                    downloadedCount = downloadedCount,
                    conflictsCount = conflictsCount
                )
            } catch (e: Exception) {
                SyncResult.Error(e.message ?: "Error de sincronización", e)
            }
        }
    }

    private fun loadSyncState(workspace: Workspace): SyncState? {
        val stateFile = File(workspace.rootDir, ".sync_state.json")
        if (!stateFile.exists()) return syncStateCache[workspace.id]
        return try {
            val text = stateFile.readText(Charsets.UTF_8)
            parseSyncStateJson(text) ?: syncStateCache[workspace.id]
        } catch (_: Exception) {
            syncStateCache[workspace.id]
        }
    }

    private fun saveSyncState(workspace: Workspace, state: SyncState) {
        syncStateCache[workspace.id] = state
        try {
            val stateFile = File(workspace.rootDir, ".sync_state.json")
            val json = serializeSyncStateJson(state)
            stateFile.writeText(json, Charsets.UTF_8)
        } catch (_: Exception) {
            // ignore
        }
    }

    private fun serializeSyncStateJson(state: SyncState): String {
        val hashesEntries = state.fileHashes.entries.joinToString(",") { (k, v) ->
            "\"${escapeJson(k)}\":\"${escapeJson(v)}\""
        }
        return "{\"lastSyncMillis\":${state.lastSyncMillis},\"fileHashes\":{$hashesEntries}}"
    }

    private fun parseSyncStateJson(json: String): SyncState? {
        val millisRegex = Regex(""""lastSyncMillis"\s*:\s*(\d+)""")
        val millis = millisRegex.find(json)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

        val hashesBlockRegex = Regex(""""fileHashes"\s*:\s*\{([^}]*)\}""")
        val block = hashesBlockRegex.find(json)?.groupValues?.get(1) ?: ""

        val pairRegex = Regex(""""([^"]+)"\s*:\s*"([^"]+)"""")
        val map = pairRegex.findAll(block).associate {
            it.groupValues[1] to it.groupValues[2]
        }
        return SyncState(lastSyncMillis = millis, fileHashes = map)
    }

    private fun escapeJson(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")
}
