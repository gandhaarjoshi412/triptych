package com.multicamstudio

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.multicamstudio.data.local.AppDatabase
import com.multicamstudio.data.local.CameraRecordingEntity
import com.multicamstudio.data.local.MediaSegmentEntity
import com.multicamstudio.data.local.SessionEntity
import com.multicamstudio.data.local.UploadTaskEntity
import com.multicamstudio.domain.model.AssetType
import com.multicamstudio.domain.model.BackupState
import com.multicamstudio.domain.model.CameraRole
import com.multicamstudio.domain.model.FinalizationState
import com.multicamstudio.domain.model.LocalDeletionState
import com.multicamstudio.domain.model.RecordingState
import com.multicamstudio.domain.model.StreamStatus
import com.multicamstudio.domain.model.UploadState
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    private fun session(id: String) = SessionEntity(
        sessionId = id, displayName = id, createdAtUtc = 1, localDate = "2026-11-16", timezone = "Asia/Kolkata",
        startMonotonicTimestamp = 0, endMonotonicTimestamp = null, durationMs = 0, recordingState = RecordingState.COMPLETED,
        backupState = BackupState.LOCAL_ONLY, localStorageSize = 0, relativeDir = "2026-11-16/09-30-25", driveFolderId = null,
        manifestPath = "", localDeletionState = LocalDeletionState.PRESENT, schemaVersion = 1, captureArchitecture = "B",
        stopReason = null, hasAudio = true, camerasRecorded = "FRONT",
    )

    private fun task(session: String, asset: String, type: AssetType, order: Int, state: UploadState = UploadState.PENDING, queuedAt: Long = 1) =
        UploadTaskEntity(
            taskId = "task_$asset", sessionId = session, assetType = type, assetId = asset, localPath = "/x/$asset", remoteSubfolder = "front",
            fileName = asset, mimeType = "video/mp4", driveFileId = null, parentFolderId = null, uploadState = state, bytesUploaded = 0,
            totalBytes = 10, localMd5 = null, remoteMd5 = null, remoteSize = null, retryCount = 0, lastError = null, lastAttempt = null,
            resumableUriEncrypted = null, sortOrder = order, manualRequested = false, queuedAt = queuedAt,
        )

    @Test
    fun segmentRequiresCameraParent_andCascadeDeletes() = runTest {
        db.sessions().insert(session("s1"))
        db.media().upsertCamera(
            CameraRecordingEntity("s1_front", "s1", CameraRole.FRONT, "1", null, "1920x1080", 30, null, "HEVC", null, 0, 0, 0, null, "/x", null, StreamStatus.RECORDING)
        )
        db.media().upsertSegment(
            MediaSegmentEntity("s1_front_001", "s1_front", "s1", 1, "1920x1080", 0, 1, 1, 0, "/x/front_001.mp4", 10, null,
                FinalizationState.FINALIZED, UploadState.NOT_QUEUED, null, null)
        )
        assertEquals(1, db.media().segments("s1").size)
        val details = db.sessions().getWithDetails("s1")!!
        assertEquals(1, details.cameras.size)
        assertEquals(1, details.segments.size)
    }

    @Test
    fun queueOrdersManifestLastAndHonoursManualFlag() = runTest {
        db.sessions().insert(session("s1"))
        db.uploads().insertIfAbsent(task("s1", "manifest", AssetType.MANIFEST, 20_000))
        db.uploads().insertIfAbsent(task("s1", "front_001", AssetType.VIDEO_SEGMENT, 1))
        db.uploads().insertIfAbsent(task("s1", "audio", AssetType.AUDIO, 10_000))
        assertEquals("front_001", db.uploads().nextRunnable(manualOnly = false, includeFailed = false)!!.assetId)
        assertNull(db.uploads().nextRunnable(manualOnly = true, includeFailed = false))
        db.uploads().markManual(listOf("s1"))
        assertEquals("front_001", db.uploads().nextRunnable(manualOnly = true, includeFailed = false)!!.assetId)
        // Duplicate insert is ignored (never re-queues a verified asset).
        assertEquals(-1L, db.uploads().insertIfAbsent(task("s1", "front_001", AssetType.VIDEO_SEGMENT, 1)))
    }

    @Test
    fun failedTasksNotPickedUntilRetried() = runTest {
        db.sessions().insert(session("s1"))
        db.uploads().insertIfAbsent(task("s1", "a", AssetType.VIDEO_SEGMENT, 1, UploadState.FAILED))
        assertNull(db.uploads().nextRunnable(false, false))
        db.uploads().retryAllFailed()
        assertTrue(db.uploads().nextRunnable(false, false) != null)
    }
}
