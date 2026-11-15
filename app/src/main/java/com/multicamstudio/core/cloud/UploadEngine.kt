package com.multicamstudio.core.cloud

import com.multicamstudio.core.common.AppLog
import com.multicamstudio.core.storage.SessionStorage
import com.multicamstudio.data.local.AppDatabase
import com.multicamstudio.data.local.UploadTaskEntity
import com.multicamstudio.domain.model.AssetType
import com.multicamstudio.domain.model.LocalDeletionState
import com.multicamstudio.domain.model.UploadState
import com.multicamstudio.domain.upload.AssetCheck
import com.multicamstudio.domain.upload.BackupVerifier
import com.multicamstudio.domain.upload.ResumableProtocol
import com.multicamstudio.domain.upload.UploadGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.random.Random

enum class UploadMode { AUTO, MANUAL }

data class UploadProgress(
    val running: Boolean = false,
    val mode: UploadMode? = null,
    val currentFile: String? = null,
    val currentSessionId: String? = null,
    val currentBytes: Long = 0,
    val currentTotal: Long = 0,
    val completedThisRun: Int = 0,
    val failedThisRun: Int = 0,
    val status: String = "Idle",
)

sealed interface RunOutcome {
    data object Completed : RunOutcome
    data class Paused(val reason: String, val retryAfterMs: Long?) : RunOutcome
    data object AuthRequired : RunOutcome
}

/**
 * Sequential (one file at a time) resumable uploader with server-side verification.
 *
 * Guarantees:
 *  - Originals are only read, never modified.
 *  - Progress (bytes, encrypted session URI) is persisted after every chunk, so uploads resume.
 *  - An asset becomes VERIFIED only after Drive metadata matches local size, MD5 and parent folder.
 *  - The [gate] is consulted before every chunk: recording, foreground return (auto), thermal or memory
 *    pressure pause the transfer within one chunk.
 */
class UploadEngine(
    private val db: AppDatabase,
    private val drive: DriveClient,
    private val auth: GoogleAuthManager,
    private val folders: DriveFolderResolver,
    private val secretBox: SecretBox,
    private val chunkSize: Int = ResumableProtocol.normalizeChunkSize(8 * 1024 * 1024),
) {
    private val mutex = Mutex()
    private val _progress = MutableStateFlow(UploadProgress())
    val progress: StateFlow<UploadProgress> = _progress.asStateFlow()

    val isRunning: Boolean get() = mutex.isLocked

    private sealed interface TaskOutcome {
        data object Done : TaskOutcome
        data class Paused(val gate: UploadGate) : TaskOutcome
        data object Auth : TaskOutcome
        data class Failed(val message: String, val permanent: Boolean) : TaskOutcome
    }

    suspend fun run(mode: UploadMode, gate: suspend () -> UploadGate, budgetMs: Long? = null): RunOutcome = mutex.withLock {
        val deadline = budgetMs?.let { System.currentTimeMillis() + it }
        _progress.value = UploadProgress(running = true, mode = mode, status = "Starting")
        var completed = 0
        var failed = 0
        try {
            var token = auth.accessTokenSilently() ?: return@withLock RunOutcome.AuthRequired.also { status("Google authorization required") }
            folders.forgetValidation()
            while (true) {
                when (val g = gate()) {
                    is UploadGate.Allowed -> Unit
                    is UploadGate.Wait -> return@withLock RunOutcome.Paused(g.reason, g.retryAfterMs).also { status("Paused: ${g.reason}") }
                    is UploadGate.Blocked -> return@withLock RunOutcome.Paused(g.reason, null).also { status("Paused: ${g.reason}") }
                }
                if (deadline != null && System.currentTimeMillis() > deadline) {
                    return@withLock RunOutcome.Paused("Time budget used; continuing later", 30_000).also { status("Continuing later") }
                }
                val task = db.uploads().nextRunnable(manualOnly = mode == UploadMode.MANUAL, includeFailed = false) ?: break
                var outcome = processTask(task, token, gate)
                if (outcome is TaskOutcome.Auth) {
                    auth.invalidate(token)
                    token = auth.accessTokenSilently() ?: return@withLock RunOutcome.AuthRequired.also { status("Google authorization required") }
                    outcome = processTask(db.uploads().get(task.taskId) ?: task, token, gate)
                    if (outcome is TaskOutcome.Auth) return@withLock RunOutcome.AuthRequired.also { status("Google authorization required") }
                }
                when (outcome) {
                    TaskOutcome.Done -> completed++
                    is TaskOutcome.Failed -> failed++
                    is TaskOutcome.Paused -> {
                        val g = outcome.gate
                        val retry = (g as? UploadGate.Wait)?.retryAfterMs
                        val reason = (g as? UploadGate.Wait)?.reason ?: (g as? UploadGate.Blocked)?.reason ?: "paused"
                        return@withLock RunOutcome.Paused(reason, retry).also { status("Paused: $reason") }
                    }
                    TaskOutcome.Auth -> Unit
                }
                _progress.update { it.copy(completedThisRun = completed, failedThisRun = failed) }
                refreshSessionState(task.sessionId)
            }
            applyPendingRenames(token)
            db.uploads().clearCompletedManualFlags()
            status(if (failed > 0) "Finished with $failed failed file(s)" else "All uploads complete")
            RunOutcome.Completed
        } catch (e: IOException) {
            AppLog.w(TAG, "Upload run interrupted", e)
            status("Network problem: ${e.message}")
            RunOutcome.Paused("Network problem", 60_000)
        } finally {
            withContext(NonCancellable) {
                db.uploads().pauseInProgress()
                _progress.value.currentSessionId?.let { refreshSessionState(it) }
            }
            _progress.update { it.copy(running = false, currentFile = null) }
        }
    }

    private fun status(text: String) = _progress.update { it.copy(status = text) }

    private suspend fun processTask(task0: UploadTaskEntity, token: String, gate: suspend () -> UploadGate): TaskOutcome {
        var task = task0
        val file = File(task.localPath)
        if (!file.exists()) return fail(task, "Local file missing: ${file.name}", permanent = true)
        val size = file.length()
        if (size == 0L) return fail(task, "Local file is empty", permanent = true)
        _progress.update { it.copy(currentFile = "${task.remoteSubfolder}/${task.fileName}".trimStart('/'), currentSessionId = task.sessionId, currentBytes = task.bytesUploaded, currentTotal = size, status = "Uploading") }

        try {
            if (task.localMd5 == null || task.totalBytes != size) {
                val md5 = withContext(Dispatchers.IO) { SessionStorage.md5Hex(file) }
                task = task.copy(localMd5 = md5, totalBytes = size)
                db.uploads().update(task)
            }
            val session = db.sessions().get(task.sessionId) ?: return fail(task, "Session record missing", permanent = true)
            val parentId = folders.subFolderId(token, session, task.remoteSubfolder)
            if (task.parentFolderId != parentId) {
                task = task.copy(parentFolderId = parentId)
                db.uploads().update(task)
            }

            // Already uploaded, only verification pending.
            if (task.uploadState == UploadState.UPLOADED_UNVERIFIED && task.driveFileId != null) {
                return verify(task, token)
            }

            var existingUri = task.resumableUriEncrypted?.let { secretBox.decrypt(it) }
            var offset = 0L
            if (existingUri != null) {
                val resumeFrom: String = existingUri
                when (val st = withRetry { drive.queryResumable(resumeFrom, size) }) {
                    is ResumableStatus.Complete -> return finishUpload(task, st.file, token)
                    is ResumableStatus.Incomplete -> offset = st.nextOffset
                    ResumableStatus.Expired -> existingUri = null
                }
            }
            val uri: String = existingUri ?: run {
                val replaceId = if (task.assetType == AssetType.MANIFEST) task.driveFileId else null
                val created = withRetry { drive.startResumable(token, task.fileName, parentId, task.mimeType, size, replaceId) }
                offset = 0
                task = task.copy(resumableUriEncrypted = secretBox.encrypt(created), bytesUploaded = 0)
                created
            }
            task = task.copy(uploadState = UploadState.IN_PROGRESS, lastAttempt = System.currentTimeMillis())
            db.uploads().update(task)
            refreshSessionState(task.sessionId)

            val buffer = ByteArray(chunkSize)
            RandomAccessFile(file, "r").use { raf ->
                while (offset < size) {
                    val g = gate()
                    if (g !is UploadGate.Allowed) {
                        db.uploads().update(task.copy(uploadState = UploadState.PAUSED, bytesUploaded = offset))
                        return TaskOutcome.Paused(g)
                    }
                    val len = minOf(chunkSize.toLong(), size - offset).toInt()
                    withContext(Dispatchers.IO) {
                        raf.seek(offset)
                        raf.readFully(buffer, 0, len)
                    }
                    val start = offset
                    val st = withRetry(onRetry = { drive.queryResumable(uri, size) }) {
                        drive.uploadChunk(uri, buffer, len, start, size, task.mimeType)
                    }
                    when (st) {
                        is ResumableStatus.Complete -> return finishUpload(task, st.file, token)
                        is ResumableStatus.Incomplete -> {
                            offset = st.nextOffset
                            task = task.copy(bytesUploaded = offset)
                            db.uploads().update(task)
                            _progress.update { it.copy(currentBytes = offset) }
                        }
                        ResumableStatus.Expired -> {
                            db.uploads().update(task.copy(resumableUriEncrypted = null, bytesUploaded = 0, uploadState = UploadState.PENDING))
                            return fail(task.copy(resumableUriEncrypted = null, bytesUploaded = 0), "Upload session expired; will restart", permanent = false)
                        }
                    }
                }
            }
            // All bytes sent but no completion response: ask once more.
            return when (val st = drive.queryResumable(uri, size)) {
                is ResumableStatus.Complete -> finishUpload(task, st.file, token)
                else -> fail(task, "Server did not confirm completion", permanent = false)
            }
        } catch (e: DriveHttpException) {
            if (e.isAuth) return TaskOutcome.Auth
            if (e.isQuota) return fail(task, "Google Drive storage is full", permanent = true)
            return fail(task, e.message ?: "HTTP ${e.code}", permanent = !e.isTransient)
        }
    }

    private suspend fun finishUpload(task: UploadTaskEntity, remote: DriveFile, token: String): TaskOutcome {
        val t = task.copy(
            driveFileId = remote.id, uploadState = UploadState.UPLOADED_UNVERIFIED, bytesUploaded = task.totalBytes,
            resumableUriEncrypted = null, remoteMd5 = remote.md5Checksum, remoteSize = remote.size,
        )
        db.uploads().update(t)
        return verify(t, token)
    }

    /** Re-reads metadata from Drive (not the upload response) and compares size, MD5 and parent. */
    private suspend fun verify(task: UploadTaskEntity, token: String): TaskOutcome {
        _progress.update { it.copy(status = "Verifying ${task.fileName}") }
        val remote = drive.getFile(token, task.driveFileId!!)
            ?: return fail(task.copy(driveFileId = null, uploadState = UploadState.PENDING), "Uploaded file not found on Drive", permanent = false)
        val check = AssetCheck(
            assetType = task.assetType, localName = task.fileName, localSize = task.totalBytes, localMd5 = task.localMd5,
            uploadState = task.uploadState, driveFileId = remote.id, remoteSize = remote.size, remoteMd5 = remote.md5Checksum,
            remoteParents = remote.parents, expectedParentId = task.parentFolderId,
        )
        val result = BackupVerifier.verifyAsset(check)
        if (!result.verified || remote.trashed) {
            val msg = (result.problems + if (remote.trashed) listOf("file is in Drive trash") else emptyList()).joinToString("; ")
            // A mismatched upload is re-uploaded from scratch next time (the bad remote copy is left untouched).
            return fail(task.copy(uploadState = UploadState.PENDING, resumableUriEncrypted = null, bytesUploaded = 0, driveFileId = if (task.assetType == AssetType.MANIFEST) task.driveFileId else null), "Verification failed: $msg", permanent = false)
        }
        db.uploads().update(task.copy(uploadState = UploadState.VERIFIED, remoteMd5 = remote.md5Checksum, remoteSize = remote.size, lastError = null))
        when (task.assetType) {
            AssetType.VIDEO_SEGMENT -> db.media().setSegmentUpload(task.assetId, UploadState.VERIFIED, remote.id, task.localMd5)
            AssetType.AUDIO -> db.media().setAudioUpload(task.assetId, UploadState.VERIFIED, remote.id, task.localMd5)
            AssetType.MANIFEST -> Unit
        }
        return TaskOutcome.Done
    }

    private suspend fun fail(task: UploadTaskEntity, message: String, permanent: Boolean): TaskOutcome {
        val retries = task.retryCount + 1
        val state = if (permanent || retries >= MAX_TASK_RETRIES) UploadState.FAILED else UploadState.PENDING
        db.uploads().update(task.copy(uploadState = state, retryCount = retries, lastError = message, lastAttempt = System.currentTimeMillis()))
        AppLog.w(TAG, "Task ${task.fileName} failed ($state): $message")
        refreshSessionState(task.sessionId)
        return TaskOutcome.Failed(message, permanent)
    }

    /** Retries transient network/server errors with exponential backoff; [onRetry] can resync state first. */
    private suspend fun <T> withRetry(onRetry: (suspend () -> ResumableStatus)? = null, block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: Exception) {
                val transient = e is IOException && (e !is DriveHttpException || e.isTransient)
                if (!transient || attempt >= 4) throw e
                delay(ResumableProtocol.backoffMs(attempt, jitterMs = Random.nextLong(0, 500)))
                attempt++
                if (onRetry != null) {
                    // If the server already has this chunk, the status query tells us and the next chunk proceeds.
                    @Suppress("UNCHECKED_CAST")
                    when (val st = runCatching { onRetry() }.getOrNull()) {
                        is ResumableStatus.Complete, is ResumableStatus.Incomplete -> return st as T
                        else -> Unit
                    }
                }
            }
        }
    }

    suspend fun refreshSessionState(sessionId: String) {
        val session = db.sessions().get(sessionId) ?: return
        val states = db.uploads().forSession(sessionId).map { it.uploadState }
        val state = BackupVerifier.sessionState(states, session.localDeletionState == LocalDeletionState.DELETED)
        if (state != session.backupState) db.sessions().setBackupState(sessionId, state)
    }

    private suspend fun applyPendingRenames(token: String) {
        db.sessions().pendingRemoteRenames().forEach { s ->
            val folderId = s.driveFolderId ?: return@forEach
            val name = DriveFolderResolver.sessionFolderName(s, s.pendingRemoteRename ?: s.displayName)
            val err = runCatching { drive.rename(token, folderId, name) }.exceptionOrNull()
            db.sessions().setRemoteRenameResult(s.sessionId, err?.let { "Drive rename failed: ${it.message}" })
        }
    }

    companion object {
        private const val TAG = "UploadEngine"
        const val MAX_TASK_RETRIES = 5
    }
}
