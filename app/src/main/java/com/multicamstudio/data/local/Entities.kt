package com.multicamstudio.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.multicamstudio.domain.model.AssetType
import com.multicamstudio.domain.model.BackupState
import com.multicamstudio.domain.model.CameraRole
import com.multicamstudio.domain.model.FinalizationState
import com.multicamstudio.domain.model.LocalDeletionState
import com.multicamstudio.domain.model.RecordingState
import com.multicamstudio.domain.model.StreamStatus
import com.multicamstudio.domain.model.UploadState

/** The database is the source of truth for session and upload state. */
@Entity(
    tableName = "sessions",
    indices = [Index("createdAtUtc"), Index("localDate"), Index("backupState")],
)
data class SessionEntity(
    @PrimaryKey val sessionId: String,
    val displayName: String,
    val createdAtUtc: Long,
    /** yyyy-MM-dd in the device's local time zone at session start. */
    val localDate: String,
    val timezone: String,
    val startMonotonicTimestamp: Long,
    val endMonotonicTimestamp: Long?,
    val durationMs: Long,
    val recordingState: RecordingState,
    val backupState: BackupState,
    val localStorageSize: Long,
    /** Relative path under files/recordings, e.g. 2026-11-16/09-30-25. Never changes after creation. */
    val relativeDir: String,
    val driveFolderId: String?,
    val manifestPath: String,
    val localDeletionState: LocalDeletionState,
    val schemaVersion: Int,
    val captureArchitecture: String,
    val stopReason: String?,
    val hasAudio: Boolean,
    /** Comma-separated CameraRole names that produced at least one finalized segment. */
    val camerasRecorded: String,
    /** Rename that has not yet been applied to Drive (null when in sync). */
    val pendingRemoteRename: String? = null,
    val remoteRenameError: String? = null,
)

@Entity(
    tableName = "camera_recordings",
    foreignKeys = [ForeignKey(entity = SessionEntity::class, parentColumns = ["sessionId"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId"), Index(value = ["sessionId", "cameraType"], unique = true)],
)
data class CameraRecordingEntity(
    @PrimaryKey val recordingId: String,
    val sessionId: String,
    val cameraType: CameraRole,
    val cameraId: String?,
    val physicalCameraId: String?,
    /** Latest resolution; history lives in session events and segments. */
    val resolution: String,
    val targetFps: Int,
    val actualFps: Double?,
    val codec: String,
    val codecName: String?,
    val bitrate: Int,
    val frameCount: Long,
    val droppedFrameCount: Long,
    /** Session time of this camera's first frame (ms). Used to align on an editing timeline. */
    val syncOffsetMs: Double?,
    /** Folder holding this camera's segments. */
    val filePath: String,
    /** Not used for multi-segment recordings; per-segment checksums live on MediaSegmentEntity. */
    val checksum: String?,
    val status: StreamStatus,
)

@Entity(
    tableName = "media_segments",
    foreignKeys = [ForeignKey(entity = CameraRecordingEntity::class, parentColumns = ["recordingId"], childColumns = ["recordingId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("recordingId"), Index(value = ["recordingId", "segmentIndex"], unique = true)],
)
data class MediaSegmentEntity(
    @PrimaryKey val segmentId: String,
    val recordingId: String,
    val sessionId: String,
    val segmentIndex: Int,
    val resolution: String,
    /** Session-relative ns of first/last frame. */
    val startTimestamp: Long?,
    val endTimestamp: Long?,
    val frameCount: Long,
    val droppedFrames: Long,
    /** Absolute path of the finalized .mp4 (or the .inprogress file while recording). */
    val filePath: String,
    val fileSize: Long,
    val checksum: String?,
    val finalizationState: FinalizationState,
    val uploadState: UploadState,
    val driveFileId: String?,
    val resumableSessionState: String?,
)

@Entity(
    tableName = "audio_recordings",
    foreignKeys = [ForeignKey(entity = SessionEntity::class, parentColumns = ["sessionId"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId", unique = true)],
)
data class AudioRecordingEntity(
    @PrimaryKey val audioId: String,
    val sessionId: String,
    val codec: String,
    val sampleRate: Int,
    val channels: Int,
    val bitrate: Int,
    val startTimestamp: Long?,
    val endTimestamp: Long?,
    val filePath: String,
    val fileSize: Long,
    val checksum: String?,
    val finalizationState: FinalizationState,
    val uploadState: UploadState,
    val driveFileId: String?,
)

@Entity(
    tableName = "upload_tasks",
    foreignKeys = [ForeignKey(entity = SessionEntity::class, parentColumns = ["sessionId"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId"), Index("uploadState"), Index(value = ["assetType", "assetId"], unique = true)],
)
data class UploadTaskEntity(
    @PrimaryKey val taskId: String,
    val sessionId: String,
    val assetType: AssetType,
    /** segmentId, audioId, or "manifest:<sessionId>". */
    val assetId: String,
    val localPath: String,
    /** Path relative to the session's Drive folder, e.g. "front" or "" for the session root. */
    val remoteSubfolder: String,
    val fileName: String,
    val mimeType: String,
    val driveFileId: String?,
    val parentFolderId: String?,
    val uploadState: UploadState,
    val bytesUploaded: Long,
    val totalBytes: Long,
    val localMd5: String?,
    val remoteMd5: String?,
    val remoteSize: Long?,
    val retryCount: Int,
    val lastError: String?,
    val lastAttempt: Long?,
    /** Resumable session URI, AES-GCM encrypted with an Android Keystore key. */
    val resumableUriEncrypted: String?,
    /** Order within a session: manifest last so it reflects everything above it. */
    val sortOrder: Int,
    /** True when queued by an explicit user action (manual upload). */
    val manualRequested: Boolean,
    val queuedAt: Long,
)

/** Persisted Drive folder ids; never resolve folders by name/path. key examples: "root", "date:2026-11-16", "session:<id>", "session:<id>/front". */
@Entity(tableName = "drive_folders")
data class DriveFolderEntity(
    @PrimaryKey val key: String,
    val driveId: String,
    val name: String,
    val parentDriveId: String?,
    val createdAt: Long,
)

@Entity(
    tableName = "session_events",
    foreignKeys = [ForeignKey(entity = SessionEntity::class, parentColumns = ["sessionId"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId")],
)
data class SessionEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val sessionTimeMs: Double,
    val wallTimeMs: Long,
    /** THERMAL, RESOLUTION, CAMERA, AUDIO_ROUTE, STORAGE, ERROR, SEGMENT, INFO */
    val type: String,
    val message: String,
)
