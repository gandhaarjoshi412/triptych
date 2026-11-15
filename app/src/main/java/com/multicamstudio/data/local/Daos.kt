package com.multicamstudio.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.multicamstudio.domain.model.BackupState
import com.multicamstudio.domain.model.FinalizationState
import com.multicamstudio.domain.model.LocalDeletionState
import com.multicamstudio.domain.model.RecordingState
import com.multicamstudio.domain.model.UploadState
import kotlinx.coroutines.flow.Flow

data class SessionWithDetails(
    @androidx.room.Embedded val session: SessionEntity,
    @androidx.room.Relation(parentColumn = "sessionId", entityColumn = "sessionId")
    val cameras: List<CameraRecordingEntity>,
    @androidx.room.Relation(parentColumn = "sessionId", entityColumn = "sessionId")
    val segments: List<MediaSegmentEntity>,
    @androidx.room.Relation(parentColumn = "sessionId", entityColumn = "sessionId")
    val audio: List<AudioRecordingEntity>,
    @androidx.room.Relation(parentColumn = "sessionId", entityColumn = "sessionId")
    val uploads: List<UploadTaskEntity>,
)

@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(session: SessionEntity)

    @Update
    suspend fun update(session: SessionEntity)

    @Query("SELECT * FROM sessions WHERE sessionId = :id")
    suspend fun get(id: String): SessionEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM sessions WHERE sessionId = :id)")
    suspend fun exists(id: String): Boolean

    @Query("SELECT * FROM sessions ORDER BY createdAtUtc DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Transaction
    @Query("SELECT * FROM sessions ORDER BY createdAtUtc DESC")
    fun observeAllWithDetails(): Flow<List<SessionWithDetails>>

    @Transaction
    @Query("SELECT * FROM sessions WHERE sessionId = :id")
    fun observeWithDetails(id: String): Flow<SessionWithDetails?>

    @Transaction
    @Query("SELECT * FROM sessions WHERE sessionId = :id")
    suspend fun getWithDetails(id: String): SessionWithDetails?

    @Query("SELECT * FROM sessions WHERE recordingState IN (:states)")
    suspend fun withRecordingStates(states: List<RecordingState>): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE recordingState IN ('COMPLETED','INTERRUPTED','FAILED') AND localDeletionState = 'PRESENT' AND backupState != 'BACKED_UP' ORDER BY createdAtUtc ASC")
    suspend fun uploadCandidates(): List<SessionEntity>

    @Query("UPDATE sessions SET backupState = :state WHERE sessionId = :id")
    suspend fun setBackupState(id: String, state: BackupState)

    @Query("UPDATE sessions SET displayName = :name, pendingRemoteRename = :pending, remoteRenameError = NULL WHERE sessionId = :id")
    suspend fun rename(id: String, name: String, pending: String?)

    @Query("UPDATE sessions SET pendingRemoteRename = NULL, remoteRenameError = :error WHERE sessionId = :id")
    suspend fun setRemoteRenameResult(id: String, error: String?)

    @Query("UPDATE sessions SET driveFolderId = :folderId WHERE sessionId = :id")
    suspend fun setDriveFolder(id: String, folderId: String)

    @Query("UPDATE sessions SET localDeletionState = :state, localStorageSize = :size WHERE sessionId = :id")
    suspend fun setLocalDeletion(id: String, state: LocalDeletionState, size: Long)

    @Query("SELECT * FROM sessions WHERE pendingRemoteRename IS NOT NULL AND driveFolderId IS NOT NULL")
    suspend fun pendingRemoteRenames(): List<SessionEntity>
}

@Dao
interface MediaDao {
    @Upsert
    suspend fun upsertCamera(camera: CameraRecordingEntity)

    @Query("SELECT * FROM camera_recordings WHERE sessionId = :sessionId")
    suspend fun cameras(sessionId: String): List<CameraRecordingEntity>

    @Upsert
    suspend fun upsertSegment(segment: MediaSegmentEntity)

    @Query("SELECT * FROM media_segments WHERE sessionId = :sessionId ORDER BY recordingId, segmentIndex")
    suspend fun segments(sessionId: String): List<MediaSegmentEntity>

    @Query("SELECT * FROM media_segments WHERE finalizationState = :state")
    suspend fun segmentsInState(state: FinalizationState): List<MediaSegmentEntity>

    @Query("SELECT * FROM media_segments WHERE segmentId = :id")
    suspend fun segment(id: String): MediaSegmentEntity?

    @Query("UPDATE media_segments SET uploadState = :state, driveFileId = :driveFileId, checksum = COALESCE(:checksum, checksum) WHERE segmentId = :id")
    suspend fun setSegmentUpload(id: String, state: UploadState, driveFileId: String?, checksum: String?)

    @Upsert
    suspend fun upsertAudio(audio: AudioRecordingEntity)

    @Query("SELECT * FROM audio_recordings WHERE sessionId = :sessionId")
    suspend fun audio(sessionId: String): AudioRecordingEntity?

    @Query("SELECT * FROM audio_recordings WHERE finalizationState = :state")
    suspend fun audioInState(state: FinalizationState): List<AudioRecordingEntity>

    @Query("UPDATE audio_recordings SET uploadState = :state, driveFileId = :driveFileId, checksum = COALESCE(:checksum, checksum) WHERE audioId = :id")
    suspend fun setAudioUpload(id: String, state: UploadState, driveFileId: String?, checksum: String?)

    @Insert
    suspend fun insertEvent(event: SessionEventEntity)

    @Query("SELECT * FROM session_events WHERE sessionId = :sessionId ORDER BY sessionTimeMs")
    suspend fun events(sessionId: String): List<SessionEventEntity>

    @Query("SELECT * FROM session_events WHERE sessionId = :sessionId ORDER BY sessionTimeMs")
    fun observeEvents(sessionId: String): Flow<List<SessionEventEntity>>
}

@Dao
interface UploadDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(task: UploadTaskEntity): Long

    @Update
    suspend fun update(task: UploadTaskEntity)

    @Query("SELECT * FROM upload_tasks WHERE taskId = :id")
    suspend fun get(id: String): UploadTaskEntity?

    @Query("SELECT * FROM upload_tasks WHERE sessionId = :sessionId ORDER BY sortOrder, fileName")
    suspend fun forSession(sessionId: String): List<UploadTaskEntity>

    @Query("SELECT * FROM upload_tasks ORDER BY queuedAt DESC, sortOrder")
    fun observeAll(): Flow<List<UploadTaskEntity>>

    /** Next runnable task, oldest session first, manifest last within a session. */
    @Query(
        """SELECT * FROM upload_tasks
           WHERE uploadState IN ('PENDING','PAUSED','IN_PROGRESS','UPLOADED_UNVERIFIED')
             AND (:manualOnly = 0 OR manualRequested = 1)
             AND (:includeFailed = 1 OR retryCount < 8)
           ORDER BY queuedAt ASC, sessionId ASC, sortOrder ASC
           LIMIT 1"""
    )
    suspend fun nextRunnable(manualOnly: Boolean, includeFailed: Boolean): UploadTaskEntity?

    @Query("SELECT COUNT(*) FROM upload_tasks WHERE uploadState IN ('PENDING','PAUSED','IN_PROGRESS','UPLOADED_UNVERIFIED') AND (:manualOnly = 0 OR manualRequested = 1)")
    suspend fun runnableCount(manualOnly: Boolean): Int

    @Query("UPDATE upload_tasks SET manualRequested = 1, uploadState = CASE WHEN uploadState IN ('FAILED','NOT_QUEUED') THEN 'PENDING' ELSE uploadState END, retryCount = CASE WHEN uploadState = 'FAILED' THEN 0 ELSE retryCount END WHERE sessionId IN (:sessionIds) AND uploadState != 'VERIFIED'")
    suspend fun markManual(sessionIds: List<String>)

    @Query("UPDATE upload_tasks SET manualRequested = 0 WHERE manualRequested = 1 AND uploadState IN ('VERIFIED')")
    suspend fun clearCompletedManualFlags()

    @Query("UPDATE upload_tasks SET uploadState = 'PENDING', retryCount = 0, lastError = NULL WHERE uploadState = 'FAILED'")
    suspend fun retryAllFailed()

    @Query("UPDATE upload_tasks SET uploadState = 'PAUSED' WHERE uploadState = 'IN_PROGRESS'")
    suspend fun pauseInProgress()

    @Query("UPDATE upload_tasks SET manualRequested = 0 WHERE manualRequested = 1")
    suspend fun cancelManual()

    @Query("SELECT COALESCE(SUM(totalBytes - bytesUploaded), 0) FROM upload_tasks WHERE sessionId IN (:sessionIds) AND uploadState != 'VERIFIED'")
    suspend fun remainingBytes(sessionIds: List<String>): Long
}

@Dao
interface DriveFolderDao {
    @Query("SELECT * FROM drive_folders WHERE `key` = :key")
    suspend fun get(key: String): DriveFolderEntity?

    @Upsert
    suspend fun upsert(folder: DriveFolderEntity)

    @Query("DELETE FROM drive_folders WHERE `key` = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM drive_folders")
    suspend fun clear()
}
