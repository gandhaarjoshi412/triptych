package com.multicamstudio.core.recording

import android.media.MediaExtractor
import android.media.MediaFormat
import com.multicamstudio.core.common.AppLog
import com.multicamstudio.core.common.writeTextAtomically
import com.multicamstudio.core.storage.SessionStorage
import com.multicamstudio.data.local.AppDatabase
import com.multicamstudio.data.local.SessionEventEntity
import com.multicamstudio.domain.manifest.SessionEvent
import com.multicamstudio.domain.manifest.SessionManifest
import com.multicamstudio.domain.model.FinalizationState
import com.multicamstudio.domain.model.RecordingState
import java.io.File

/**
 * Runs once per process start, before any recording can begin. Sessions left in RECORDING/FINALIZING
 * belong to a process that died (crash, force-stop, reboot). Recording is never restarted automatically.
 *
 *  - Finalized segments are untouched and remain playable.
 *  - Each `.inprogress` file is probed with MediaExtractor. MediaMuxer writes the MP4 index only on stop(),
 *    so these are normally unplayable; they are kept on disk (never deleted, never uploaded) and marked
 *    INTERRUPTED_UNRECOVERABLE. Only a file that MediaExtractor can actually read samples from is promoted.
 */
class RecoveryManager(
    private val db: AppDatabase,
    private val storage: SessionStorage,
    private val enqueue: suspend (sessionId: String) -> Unit,
) {
    suspend fun recover() {
        val stale = db.sessions().withRecordingStates(listOf(RecordingState.RECORDING, RecordingState.FINALIZING))
        if (stale.isEmpty()) return
        val openSegments = db.media().segmentsInState(FinalizationState.IN_PROGRESS).groupBy { it.sessionId }
        val openAudio = db.media().audioInState(FinalizationState.IN_PROGRESS).associateBy { it.sessionId }
        for (s in stale) {
            val notes = mutableListOf<String>()
            openSegments[s.sessionId].orEmpty().forEach { seg ->
                val f = File(seg.filePath)
                val playable = f.exists() && isReadable(f)
                if (playable) {
                    val final = File(f.parentFile, f.name.removeSuffix(SessionStorage.IN_PROGRESS_SUFFIX))
                    val target = if (!final.exists() && f.renameTo(final)) final else f
                    db.media().upsertSegment(seg.copy(filePath = target.absolutePath, fileSize = target.length(), finalizationState = FinalizationState.FINALIZED))
                    notes += "Recovered ${final.name} (verified readable with MediaExtractor)"
                } else {
                    db.media().upsertSegment(seg.copy(fileSize = if (f.exists()) f.length() else 0, finalizationState = FinalizationState.INTERRUPTED_UNRECOVERABLE))
                    notes += "${f.name}: incomplete, not playable (kept on disk, not uploaded)"
                }
            }
            openAudio[s.sessionId]?.let { a ->
                val f = File(a.filePath)
                if (f.exists() && isReadable(f)) {
                    val final = File(f.parentFile, f.name.removeSuffix(SessionStorage.IN_PROGRESS_SUFFIX))
                    val target = if (!final.exists() && f.renameTo(final)) final else f
                    db.media().upsertAudio(a.copy(filePath = target.absolutePath, fileSize = target.length(), finalizationState = FinalizationState.FINALIZED))
                    notes += "Recovered master audio"
                } else {
                    db.media().upsertAudio(a.copy(finalizationState = FinalizationState.INTERRUPTED_UNRECOVERABLE))
                    notes += "Master audio incomplete, not playable"
                }
            }
            val segs = db.media().segments(s.sessionId)
            val good = segs.filter { it.finalizationState == FinalizationState.FINALIZED }
            val endNs = good.mapNotNull { it.endTimestamp }.maxOrNull()
            val reason = "Recording ended unexpectedly (app process stopped)"
            db.sessions().update(
                s.copy(
                    recordingState = if (good.isEmpty()) RecordingState.FAILED else RecordingState.INTERRUPTED,
                    durationMs = (endNs ?: 0) / 1_000_000,
                    stopReason = reason,
                    localStorageSize = storage.sizeOf(storage.sessionDir(s.relativeDir)),
                    hasAudio = db.media().audio(s.sessionId)?.finalizationState == FinalizationState.FINALIZED,
                    camerasRecorded = good.map { it.recordingId.substringAfter("${s.sessionId}_") }.distinct()
                        .mapNotNull { dir -> com.multicamstudio.domain.model.CameraRole.fromDirName(dir)?.name }.joinToString(","),
                )
            )
            notes.forEach { n ->
                db.media().insertEvent(SessionEventEntity(sessionId = s.sessionId, sessionTimeMs = (endNs ?: 0) / 1e6, wallTimeMs = System.currentTimeMillis(), type = "RECOVERY", message = n))
            }
            updateManifest(s.relativeDir, reason, notes)
            enqueue(s.sessionId)
            AppLog.w(TAG, "Recovered session ${s.sessionId}: ${notes.joinToString()}")
        }
    }

    private fun updateManifest(relativeDir: String, reason: String, notes: List<String>) = runCatching {
        val f = storage.manifestFile(relativeDir)
        if (!f.exists()) return@runCatching
        val m = SessionManifest.json.decodeFromString(SessionManifest.serializer(), f.readText())
        f.writeTextAtomically(
            SessionManifest.json.encodeToString(
                SessionManifest.serializer(),
                m.copy(stopReason = reason, events = m.events + notes.map { SessionEvent(-1.0, "RECOVERY", it) }),
            )
        )
    }

    private fun isReadable(f: File): Boolean {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(f.absolutePath)
            if (ex.trackCount == 0) return false
            ex.selectTrack(0)
            val fmt = ex.getTrackFormat(0)
            val buf = java.nio.ByteBuffer.allocate(1 shl 20)
            ex.readSampleData(buf, 0) > 0 && (fmt.containsKey(MediaFormat.KEY_DURATION) && fmt.getLong(MediaFormat.KEY_DURATION) > 0)
        } catch (e: Exception) {
            false
        } finally {
            ex.release()
        }
    }

    companion object {
        private const val TAG = "Recovery"
    }
}
