package com.multicamstudio.data.repo

import com.multicamstudio.core.cloud.DriveClient
import com.multicamstudio.core.cloud.GoogleAuthManager
import com.multicamstudio.core.common.AppLog
import com.multicamstudio.core.common.writeTextAtomically
import com.multicamstudio.core.storage.SessionStorage
import com.multicamstudio.data.local.AppDatabase
import com.multicamstudio.data.local.SessionWithDetails
import com.multicamstudio.data.local.UploadTaskEntity
import com.multicamstudio.domain.manifest.SessionManifest
import com.multicamstudio.domain.model.AssetType
import com.multicamstudio.domain.model.BackupState
import com.multicamstudio.domain.model.FinalizationState
import com.multicamstudio.domain.model.LocalDeletionState
import com.multicamstudio.domain.model.UploadState
import com.multicamstudio.domain.upload.AssetCheck
import com.multicamstudio.domain.upload.BackupVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

data class DeletionPreview(
    val sessionId: String,
    val displayName: String,
    val fileCount: Int,
    val bytes: Long,
    val eligible: Boolean,
    val problems: List<String>,
)

data class DeletionResult(val sessionId: String, val deleted: Boolean, val freedBytes: Long, val message: String)

/**
 * Session-level operations used by the UI and background work. The Room database is the source of truth.
 */
class SessionRepository(
    private val db: AppDatabase,
    private val storage: SessionStorage,
    private val drive: DriveClient,
    private val auth: GoogleAuthManager,
    private val isRecordingSession: (String) -> Boolean,
    private val isUploading: () -> Boolean,
) {
    fun observeSessions(): Flow<List<SessionWithDetails>> = db.sessions().observeAllWithDetails()
    fun observeSession(id: String): Flow<SessionWithDetails?> = db.sessions().observeWithDetails(id)
    fun observeEvents(id: String) = db.media().observeEvents(id)
    fun observeUploadTasks(): Flow<List<UploadTaskEntity>> = db.uploads().observeAll()

    /**
     * Creates one upload task per finalized asset (every segment, the master audio, the manifest).
     * Incomplete files are never queued. [autoQueue] = whether automatic upload may pick them up.
     */
    suspend fun enqueueSession(sessionId: String, autoQueue: Boolean) {
        val session = db.sessions().get(sessionId) ?: return
        if (session.localDeletionState != LocalDeletionState.PRESENT) return
        val now = System.currentTimeMillis()
        val initial = if (autoQueue) UploadState.PENDING else UploadState.NOT_QUEUED
        db.media().segments(sessionId).filter { it.finalizationState == FinalizationState.FINALIZED }.forEach { seg ->
            val f = File(seg.filePath)
            val role = f.parentFile?.name.orEmpty()
            db.uploads().insertIfAbsent(task(sessionId, AssetType.VIDEO_SEGMENT, seg.segmentId, f, role, "video/mp4", seg.segmentIndex, initial, now))
        }
        db.media().audio(sessionId)?.takeIf { it.finalizationState == FinalizationState.FINALIZED }?.let { a ->
            db.uploads().insertIfAbsent(task(sessionId, AssetType.AUDIO, a.audioId, File(a.filePath), "audio", "audio/mp4", 10_000, initial, now))
        }
        val manifest = storage.manifestFile(session.relativeDir)
        if (manifest.exists()) {
            db.uploads().insertIfAbsent(task(sessionId, AssetType.MANIFEST, "manifest:$sessionId", manifest, "metadata", "application/json", 20_000, initial, now))
        }
        if (autoQueue && session.backupState == BackupState.LOCAL_ONLY) db.sessions().setBackupState(sessionId, BackupState.QUEUED)
    }

    private fun task(sessionId: String, type: AssetType, assetId: String, file: File, sub: String, mime: String, order: Int, state: UploadState, now: Long) =
        UploadTaskEntity(
            taskId = "task_$assetId", sessionId = sessionId, assetType = type, assetId = assetId, localPath = file.absolutePath,
            remoteSubfolder = sub, fileName = file.name, mimeType = mime, driveFileId = null, parentFolderId = null,
            uploadState = state, bytesUploaded = 0, totalBytes = file.length(), localMd5 = null, remoteMd5 = null, remoteSize = null,
            retryCount = 0, lastError = null, lastAttempt = null, resumableUriEncrypted = null, sortOrder = order,
            manualRequested = false, queuedAt = now,
        )

    /** Marks sessions for manual upload (creating tasks if needed). Returns bytes remaining. */
    suspend fun prepareManualUpload(sessionIds: List<String>): Long {
        sessionIds.forEach { enqueueSession(it, autoQueue = false) }
        db.uploads().markManual(sessionIds)
        sessionIds.forEach { id ->
            val s = db.sessions().get(id) ?: return@forEach
            if (s.backupState == BackupState.LOCAL_ONLY || s.backupState == BackupState.UPLOAD_FAILED) db.sessions().setBackupState(id, BackupState.QUEUED)
        }
        return db.uploads().remainingBytes(sessionIds)
    }

    suspend fun pendingSessionIds(): List<String> = db.sessions().uploadCandidates().map { it.sessionId }

    suspend fun retryFailed() = db.uploads().retryAllFailed()

    /**
     * Rename changes only the display name. The immutable session id and local folder stay the same.
     * If the session is already on Drive, the Drive session folder is renamed (by id) on the next upload
     * run and the manifest is re-uploaded in place.
     */
    suspend fun rename(sessionId: String, newName: String): String? {
        val name = newName.trim()
        if (name.isEmpty()) return "Name cannot be empty"
        if (isRecordingSession(sessionId)) return "Cannot rename while recording"
        val s = db.sessions().get(sessionId) ?: return "Session not found"
        db.sessions().rename(sessionId, name, pending = if (s.driveFolderId != null) name else null)
        val manifestFile = storage.manifestFile(s.relativeDir)
        withContext(Dispatchers.IO) {
            runCatching {
                if (manifestFile.exists()) {
                    val m = SessionManifest.json.decodeFromString(SessionManifest.serializer(), manifestFile.readText())
                    manifestFile.writeTextAtomically(SessionManifest.json.encodeToString(SessionManifest.serializer(), m.copy(displayName = name)))
                }
            }.onFailure { AppLog.w(TAG, "Manifest rename failed", it) }
        }
        // Manifest changed -> re-upload it (replaces the Drive file by id).
        db.uploads().forSession(sessionId).firstOrNull { it.assetType == AssetType.MANIFEST }?.let { t ->
            db.uploads().update(
                t.copy(
                    uploadState = UploadState.PENDING, localMd5 = null, totalBytes = manifestFile.length(), bytesUploaded = 0,
                    resumableUriEncrypted = null, retryCount = 0, lastError = null,
                )
            )
        }
        return null
    }

    suspend fun deletionPreview(sessionId: String): DeletionPreview {
        val d = db.sessions().getWithDetails(sessionId) ?: return DeletionPreview(sessionId, "?", 0, 0, false, listOf("Session not found"))
        val files = storage.deletableMedia(d.session.relativeDir)
        val problems = localEligibilityProblems(d)
        return DeletionPreview(sessionId, d.session.displayName, files.size, files.sumOf { it.length() }, problems.isEmpty(), problems)
    }

    private fun localEligibilityProblems(d: SessionWithDetails): List<String> {
        val problems = mutableListOf<String>()
        if (d.session.localDeletionState == LocalDeletionState.DELETED) problems += "Local copies already deleted"
        if (isRecordingSession(d.session.sessionId)) problems += "Session is recording"
        if (d.uploads.any { it.uploadState == UploadState.IN_PROGRESS }) problems += "Upload in progress"
        val finalizedSegments = d.segments.count { it.finalizationState == FinalizationState.FINALIZED }
        val audioFinal = d.audio.any { it.finalizationState == FinalizationState.FINALIZED }
        val checks = d.uploads.map {
            AssetCheck(it.assetType, it.fileName, it.totalBytes, it.localMd5, it.uploadState, it.driveFileId, it.remoteSize, it.remoteMd5, emptyList(), it.parentFolderId)
        }
        problems += BackupVerifier.canDeleteLocal(checks, finalizedSegments, audioFinal).problems
        return problems
    }

    /**
     * "Delete local copies": re-checks eligibility, re-verifies every asset against live Drive metadata,
     * then deletes local media only. Never calls any Drive deletion endpoint. Keeps the database record
     * and the small manifest so the session remains visible as CLOUD_ONLY.
     */
    suspend fun deleteLocalCopies(sessionId: String): DeletionResult {
        val d = db.sessions().getWithDetails(sessionId) ?: return DeletionResult(sessionId, false, 0, "Session not found")
        val name = d.session.displayName
        if (isUploading() && d.uploads.any { it.uploadState == UploadState.IN_PROGRESS }) return DeletionResult(sessionId, false, 0, "$name: upload in progress")
        val problems = localEligibilityProblems(d)
        if (problems.isNotEmpty()) return DeletionResult(sessionId, false, 0, "$name: ${problems.first()}")

        val token = auth.accessTokenSilently() ?: return DeletionResult(sessionId, false, 0, "$name: reconnect Google Drive to re-verify before deleting")
        for (t in d.uploads) {
            val remote = try {
                drive.getFile(token, t.driveFileId!!)
            } catch (e: Exception) {
                return DeletionResult(sessionId, false, 0, "$name: could not re-verify with Drive (${e.message})")
            }
            val check = AssetCheck(
                t.assetType, t.fileName, File(t.localPath).takeIf { it.exists() }?.length() ?: t.totalBytes, t.localMd5, t.uploadState,
                remote?.id, remote?.size, remote?.md5Checksum, remote?.parents.orEmpty(), t.parentFolderId,
            )
            val v = BackupVerifier.verifyAsset(check)
            if (remote == null || remote.trashed || !v.verified) {
                db.uploads().update(t.copy(uploadState = UploadState.PENDING, lastError = "Re-verification failed: ${v.problems.joinToString()}"))
                db.sessions().setBackupState(sessionId, BackupState.PARTIALLY_UPLOADED)
                return DeletionResult(sessionId, false, 0, "$name: ${t.fileName} failed re-verification; it will be uploaded again")
            }
        }

        db.sessions().setLocalDeletion(sessionId, LocalDeletionState.DELETING, d.session.localStorageSize)
        val files = storage.deletableMedia(d.session.relativeDir)
        var freed = 0L
        var failures = 0
        withContext(Dispatchers.IO) {
            files.forEach { f ->
                val len = f.length()
                if (f.delete()) freed += len else failures++
            }
        }
        val remaining = storage.sizeOf(storage.sessionDir(d.session.relativeDir))
        return if (failures == 0) {
            db.sessions().setLocalDeletion(sessionId, LocalDeletionState.DELETED, remaining)
            db.sessions().setBackupState(sessionId, BackupState.CLOUD_ONLY)
            DeletionResult(sessionId, true, freed, "$name: local copies deleted")
        } else {
            db.sessions().setLocalDeletion(sessionId, LocalDeletionState.PRESENT, remaining)
            DeletionResult(sessionId, false, freed, "$name: $failures file(s) could not be deleted")
        }
    }

    companion object {
        private const val TAG = "SessionRepo"
    }
}
