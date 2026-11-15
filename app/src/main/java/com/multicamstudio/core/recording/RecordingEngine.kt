package com.multicamstudio.core.recording

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.multicamstudio.BuildConfig
import com.multicamstudio.core.audio.AudioResult
import com.multicamstudio.core.audio.MasterAudioRecorder
import com.multicamstudio.core.camera.CameraDeviceHandle
import com.multicamstudio.core.camera.CameraDeviceListener
import com.multicamstudio.core.camera.CameraOverrides
import com.multicamstudio.core.camera.OutputKind
import com.multicamstudio.core.camera.PreviewHub
import com.multicamstudio.core.camera.PreviewSizes
import com.multicamstudio.core.camera.ResolvedTopology
import com.multicamstudio.core.camera.SensorInfo
import com.multicamstudio.core.camera.StreamOutput
import com.multicamstudio.core.camera.StreamTarget
import com.multicamstudio.core.camera.TopologyResolver
import com.multicamstudio.core.common.AppLog
import com.multicamstudio.core.common.LooperThread
import com.multicamstudio.core.common.writeTextAtomically
import com.multicamstudio.core.media.EncoderCatalog
import com.multicamstudio.core.media.EncoderConfig
import com.multicamstudio.core.media.EncoderInfo
import com.multicamstudio.core.media.SegmentResult
import com.multicamstudio.core.media.VideoEncoderPipeline
import com.multicamstudio.core.storage.SessionStorage
import com.multicamstudio.core.thermal.SystemHealthMonitor
import com.multicamstudio.data.local.AppDatabase
import com.multicamstudio.data.local.AudioRecordingEntity
import com.multicamstudio.data.local.CameraRecordingEntity
import com.multicamstudio.data.local.MediaSegmentEntity
import com.multicamstudio.data.local.SessionEntity
import com.multicamstudio.data.local.SessionEventEntity
import com.multicamstudio.data.prefs.AppSettings
import com.multicamstudio.data.prefs.SettingsRepository
import com.multicamstudio.domain.manifest.AppInfo
import com.multicamstudio.domain.manifest.AudioRoute
import com.multicamstudio.domain.manifest.AudioTrack
import com.multicamstudio.domain.manifest.CameraTrack
import com.multicamstudio.domain.manifest.DeviceInfo
import com.multicamstudio.domain.manifest.ResolutionChange
import com.multicamstudio.domain.manifest.SegmentInfo
import com.multicamstudio.domain.manifest.SessionEvent
import com.multicamstudio.domain.manifest.SessionManifest
import com.multicamstudio.domain.manifest.SyncInfo
import com.multicamstudio.domain.model.BackupState
import com.multicamstudio.domain.model.CameraRole
import com.multicamstudio.domain.model.FinalizationState
import com.multicamstudio.domain.model.LocalDeletionState
import com.multicamstudio.domain.model.RecordingState
import com.multicamstudio.domain.model.SessionPhase
import com.multicamstudio.domain.model.StreamStatus
import com.multicamstudio.domain.model.UploadState
import com.multicamstudio.domain.model.VideoCodecChoice
import com.multicamstudio.domain.model.VideoResolution
import com.multicamstudio.domain.session.BitratePolicy
import com.multicamstudio.domain.session.SegmentPlanner
import com.multicamstudio.domain.session.SessionNaming
import com.multicamstudio.domain.session.StoragePolicy
import com.multicamstudio.domain.thermal.CameraDecisionOption
import com.multicamstudio.domain.thermal.CameraPriority
import com.multicamstudio.domain.thermal.HealthSample
import com.multicamstudio.domain.thermal.ThermalAction
import com.multicamstudio.domain.thermal.ThermalPolicy
import com.multicamstudio.domain.timing.ClockBase
import com.multicamstudio.domain.timing.PhaseAlignment
import com.multicamstudio.domain.timing.SessionTimeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Collections
import java.util.concurrent.Executors

/**
 * Coordinates one recording session: per-camera pipelines, the master audio, thermal strategy,
 * segment boundaries, storage guardrails and persistence. Independent of UI and network.
 *
 * Threading: all mutable state is confined to a single-threaded engine dispatcher. Camera callbacks
 * run on a camera HandlerThread, each encoder on its own thread, audio on its own thread; they hop
 * back onto the engine dispatcher to touch state.
 */
class RecordingEngine(
    private val context: Context,
    private val db: AppDatabase,
    private val storage: SessionStorage,
    private val settingsRepo: SettingsRepository,
    private val health: SystemHealthMonitor,
    val preview: PreviewHub,
    private val appVisible: StateFlow<Boolean>,
    private val onSessionFinalized: suspend (sessionId: String) -> Unit,
) {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val resolver = TopologyResolver(manager)
    private val cameraThread = LooperThread("camera")
    private val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "engine") }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _state = MutableStateFlow(RecordingUiState())
    val state: StateFlow<RecordingUiState> = _state.asStateFlow()

    private var resolved: ResolvedTopology? = null
    private val devices = mutableMapOf<String, CameraDeviceHandle>()
    private val previewReaders = mutableMapOf<CameraRole, ImageReader>()
    private var session: ActiveSession? = null
    private var previewRequested = false

    // ---------------------------------------------------------------- public API (fire-and-forget)

    fun startPreview() = scope.launch {
        previewRequested = true
        if (session != null) return@launch
        runCatching { openPreview() }.onFailure {
            AppLog.e(TAG, "Preview failed", it)
            _state.update { s -> s.copy(errors = (s.errors + "Preview: ${it.message}").takeLast(5)) }
        }
    }

    fun stopPreview() = scope.launch {
        previewRequested = false
        if (session != null) return@launch // recording keeps devices; rendering is paused by the UI
        closeAllDevices()
        preview.releaseAll()
        previewReaders.clear()
        if (_state.value.phase == SessionPhase.PREVIEWING) _state.update { it.copy(phase = SessionPhase.IDLE) }
    }

    fun startRecording(deviceRotationDeg: Int) = scope.launch {
        if (session != null) return@launch
        try {
            beginSession(deviceRotationDeg)
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to start recording", e)
            val s = session
            if (s != null) finalizeSession("Start failed: ${e.message}") else {
                _state.update { it.copy(phase = SessionPhase.FAILED, lastStopReason = "Could not start: ${e.message}", endedCount = it.endedCount + 1) }
            }
        }
    }

    fun stopRecording(reason: String = "Stopped by user") = scope.launch {
        if (session != null) finalizeSession(reason)
    }

    fun applyDecision(option: CameraDecisionOption) = scope.launch { applyCameraDecision(option, userChosen = true) }

    /** Clears the thermal topology cache, e.g. after camera overrides change. */
    fun invalidateTopology() = scope.launch { if (session == null) resolved = null }

    fun isRecording(): Boolean = _state.value.phase.isActive

    // ---------------------------------------------------------------- topology / preview

    private suspend fun ensureTopology(s: AppSettings): ResolvedTopology {
        resolved?.let { return it }
        val r = withContext(Dispatchers.Default) {
            resolver.resolve(CameraOverrides(s.cameraOverrideFront, s.cameraOverrideMain, s.cameraOverrideWide), VideoResolution.P1080, s.previewWidth)
        } ?: throw IllegalStateException("No usable camera configuration found. Run Diagnostics.")
        resolved = r
        _state.update {
            it.copy(
                architecture = r.topology.architecture.label,
                topologyNote = "${r.topology.description} · query=${r.verification}" + if (!r.previewAllowed) " · preview disabled (stream limit)" else "",
            )
        }
        return r
    }

    private fun sensorFor(t: StreamTarget): SensorInfo? = resolved?.sensors?.firstOrNull { it.id == t.sensorId }

    private fun hasPermission(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    private suspend fun openPreview() {
        check(hasPermission(Manifest.permission.CAMERA)) { "Camera permission not granted" }
        val s = settingsRepo.current()
        preview.configure(s.previewEnabled, s.previewFps)
        val r = ensureTopology(s)
        if (!r.previewAllowed || !s.previewEnabled) {
            _state.update { it.copy(phase = SessionPhase.IDLE) }
            return
        }
        for (deviceId in r.topology.deviceIds) {
            val outs = r.topology.targetsFor(deviceId).mapNotNull { t -> previewOutput(t, s, deviceRotation = 0) }
            if (outs.isEmpty()) continue
            try {
                val h = ensureDevice(deviceId)
                h.stopStreaming()
                h.configure(outs, fpsRange(r.topology.targetsFor(deviceId).first()), deviceListener)
            } catch (e: Exception) {
                AppLog.w(TAG, "Preview on $deviceId failed: ${e.message}")
                _state.update { st -> st.copy(errors = (st.errors + "Preview $deviceId: ${e.message}").takeLast(5)) }
            }
        }
        _state.update { it.copy(phase = SessionPhase.PREVIEWING) }
    }

    private fun previewOutput(t: StreamTarget, s: AppSettings, deviceRotation: Int): StreamOutput? {
        if (!s.previewEnabled || resolved?.previewAllowed != true) return null
        val sensor = sensorFor(t) ?: return null
        val reader = previewReaders[t.role] ?: run {
            val size = PreviewSizes.choose(sensor.yuvSizes, s.previewWidth) ?: return null
            preview.createReader(t.role, size, sensor.sensorOrientation, sensor.isFront, deviceRotation).also { previewReaders[t.role] = it }
        }
        return StreamOutput(t.role, t.physicalId, reader.surface, OutputKind.PREVIEW)
    }

    private fun fpsRange(t: StreamTarget) =
        sensorFor(t)?.fixedFpsRange(TARGET_FPS)
            ?: resolved?.sensors?.firstOrNull { it.id == t.deviceId }?.fixedFpsRange(TARGET_FPS)
            ?: android.util.Range(TARGET_FPS, TARGET_FPS)

    private suspend fun ensureDevice(deviceId: String): CameraDeviceHandle {
        devices[deviceId]?.takeIf { !it.closed }?.let { return it }
        val h = CameraDeviceHandle.open(manager, deviceId, cameraThread, deviceListener)
        devices[deviceId] = h
        return h
    }

    private fun closeAllDevices() {
        devices.values.forEach { it.close() }
        devices.clear()
    }

    private val deviceListener = object : CameraDeviceListener {
        override fun onDisconnected(deviceId: String) {
            scope.launch { handleDeviceLost(deviceId, "disconnected") }
        }

        override fun onError(deviceId: String, error: Int) {
            scope.launch { handleDeviceLost(deviceId, "error ${CameraDeviceHandle.errorName(error)}") }
        }

        override fun onBufferLost(deviceId: String, role: CameraRole?, frameNumber: Long) {
            if (role != null) scope.launch { session?.roles?.get(role)?.let { it.bufferLost++ } }
        }
    }

    // ---------------------------------------------------------------- session start

    private suspend fun beginSession(deviceRotation: Int) {
        check(hasPermission(Manifest.permission.CAMERA)) { "Camera permission not granted" }
        _state.update { it.copy(phase = SessionPhase.INITIALIZING, errors = emptyList(), lastStopReason = null, thermalWarning = null) }
        val settings = settingsRepo.current()
        preview.configure(settings.previewEnabled, settings.previewFps)
        val topo = ensureTopology(settings)

        val encoder = EncoderCatalog.selectHardware(settings.codec.mime, VideoResolution.P1080)
            ?: throw IllegalStateException(
                "No hardware ${settings.codec.manifestName} encoder supports 1080p30. " +
                    if (settings.codec == VideoCodecChoice.HEVC) "You can explicitly select H.264 in Settings." else ""
            )
        val freeBytes = storage.freeBytes()
        check(freeBytes > settings.storageStopGb * GB) { "Not enough free storage to start recording" }

        // Session clock origin.
        val r1 = SystemClock.elapsedRealtimeNanos()
        val mono = System.nanoTime()
        val r2 = SystemClock.elapsedRealtimeNanos()
        val timeline = SessionTimeline(originRealtimeNs = r2, monotonicToRealtimeOffsetNs = (r1 + r2) / 2 - mono)
        val wallStart = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val local = LocalDateTime.ofInstant(Instant.ofEpochMilli(wallStart), zone)

        val prefix = settings.sessionNamePrefix.trim().ifEmpty { null }
        val baseId = SessionNaming.sessionId(local)
        var sessionId = baseId
        var relativeDir = "${SessionNaming.dateFolder(local)}/" + SessionNaming.sessionFolder(local, prefix)
        var n = 2
        while (db.sessions().exists(sessionId) || storage.sessionDir(relativeDir).exists()) {
            sessionId = "${baseId}_$n"
            relativeDir = "${SessionNaming.dateFolder(local)}/" + SessionNaming.sessionFolder(local, prefix, n.toString())
            n++
        }
        storage.createSessionDirs(relativeDir, topo.topology.roles)

        val s = ActiveSession(
            id = sessionId,
            displayName = prefix?.let { "$it $sessionId" } ?: sessionId,
            relativeDir = relativeDir,
            timeline = timeline,
            wallStartMs = wallStart,
            zone = zone,
            settings = settings,
            encoder = encoder,
            deviceRotation = deviceRotation,
            topology = topo,
        )
        session = s
        db.sessions().insert(
            SessionEntity(
                sessionId = s.id, displayName = s.displayName, createdAtUtc = wallStart, localDate = SessionNaming.dateFolder(local),
                timezone = zone.id, startMonotonicTimestamp = timeline.originRealtimeNs, endMonotonicTimestamp = null, durationMs = 0,
                recordingState = RecordingState.RECORDING, backupState = BackupState.LOCAL_ONLY, localStorageSize = 0,
                relativeDir = relativeDir, driveFolderId = null, manifestPath = storage.manifestFile(relativeDir).absolutePath,
                localDeletionState = LocalDeletionState.PRESENT, schemaVersion = SessionManifest.SCHEMA_VERSION,
                captureArchitecture = topo.topology.architecture.name, stopReason = null, hasAudio = false, camerasRecorded = "",
            )
        )
        event(s, "INFO", "Session started: ${topo.topology.description}; encoder ${encoder.name}")
        _state.update {
            it.copy(sessionId = s.id, displayName = s.displayName, startedWallMs = wallStart, elapsedMs = 0, lastSessionId = null)
        }

        // Audio first so it brackets the video.
        if (hasPermission(Manifest.permission.RECORD_AUDIO)) {
            val audioFile = File(storage.audioDir(relativeDir), SessionStorage.AUDIO_NAME)
            val rec = MasterAudioRecorder(
                audioFile, timeline,
                onError = { msg -> scope.launch { event(s, "ERROR", "Audio: $msg"); s.audioStatus = "Failed: $msg" } },
                onRouteChanged = { ch -> scope.launch { event(s, "AUDIO_ROUTE", "${ch.deviceType} ${ch.deviceName}") } },
            )
            db.media().upsertAudio(
                AudioRecordingEntity(
                    audioId = "${s.id}_audio", sessionId = s.id, codec = "AAC", sampleRate = rec.sampleRate, channels = 1,
                    bitrate = rec.bitrate, startTimestamp = null, endTimestamp = null, filePath = audioFile.absolutePath, fileSize = 0,
                    checksum = null, finalizationState = FinalizationState.IN_PROGRESS, uploadState = UploadState.NOT_QUEUED, driveFileId = null,
                )
            )
            rec.start()
            s.audio = rec
            s.audioStatus = "Recording"
        } else {
            s.audioStatus = "Microphone permission not granted"
            event(s, "AUDIO", "Audio disabled: RECORD_AUDIO permission not granted")
        }

        topo.topology.targets.forEach { t ->
            s.roles[t.role] = RoleRuntime(t.role, t, sensorFor(t)).also { rr ->
                rr.resolution = VideoResolution.P1080
                rr.resolutionHistory += ResolutionChange(0.0, VideoResolution.P1080.toString(), "initial")
            }
            // Parent row must exist before any segment row (foreign key), and survives a crash.
            db.media().upsertCamera(
                CameraRecordingEntity(
                    recordingId = recordingId(s.id, t.role), sessionId = s.id, cameraType = t.role, cameraId = t.deviceId,
                    physicalCameraId = t.physicalId, resolution = VideoResolution.P1080.toString(), targetFps = TARGET_FPS, actualFps = null,
                    codec = settings.codec.manifestName, codecName = encoder.name, bitrate = 0, frameCount = 0, droppedFrameCount = 0,
                    syncOffsetMs = null, filePath = storage.cameraDir(relativeDir, t.role).absolutePath, checksum = null, status = StreamStatus.INITIALIZING,
                )
            )
        }
        startPipelines(s, s.roles.keys, VideoResolution.P1080)
        if (s.roles.values.none { it.status == StreamStatus.RECORDING }) {
            throw IllegalStateException("No camera could start recording")
        }
        s.segmentEpochNs = SystemClock.elapsedRealtimeNanos() - timeline.originRealtimeNs
        _state.update { it.copy(phase = SessionPhase.RECORDING) }
        s.tickJob = scope.launch { tickLoop(s) }
    }

    /** Creates encoders for [roles] and configures their camera devices. Updates role status. */
    private suspend fun startPipelines(s: ActiveSession, roles: Set<CameraRole>, resolution: VideoResolution) {
        if (s.stopping) return
        s.resolution = resolution
        val topo = s.topology.topology
        val bitrateFor = { _: CameraRole ->
            BitratePolicy.clamp(BitratePolicy.estimate(resolution, TARGET_FPS, s.settings.codec, s.settings.quality), s.encoder.bitrateMin, s.encoder.bitrateMax)
        }
        for (deviceId in topo.deviceIds) {
            val targets = topo.targetsFor(deviceId).filter { it.role in roles && s.roles[it.role]?.terminal != true }
            if (targets.isEmpty()) continue
            val created = mutableListOf<Pair<RoleRuntime, VideoEncoderPipeline>>()
            try {
                for (t in targets) {
                    val rr = s.roles.getValue(t.role)
                    val sensor = rr.sensor
                    if (sensor != null && !sensor.supportsEncoderSize(resolution)) {
                        event(s, "CAMERA", "${t.role}: ${sensor.id} does not advertise $resolution@30 for MediaCodec; attempting anyway")
                    }
                    val orientation = sensor?.let {
                        if (it.isFront) (it.sensorOrientation + s.deviceRotation) % 360 else (it.sensorOrientation - s.deviceRotation + 360) % 360
                    } ?: 0
                    val cfg = EncoderConfig(
                        role = t.role, codecName = s.encoder.name, mime = s.settings.codec.mime, resolution = resolution, fps = TARGET_FPS,
                        bitrate = bitrateFor(t.role), useVbr = s.encoder.vbr, hevcMainProfile = s.encoder.hevcMainProfile, orientationHint = orientation,
                    )
                    val p = VideoEncoderPipeline(
                        cfg, storage.cameraDir(s.relativeDir, t.role), { idx -> SessionNaming.segmentFileName(t.role, idx) },
                        rr.nextSegmentIndex, s.timeline, pipelineListener(s),
                    )
                    p.start()
                    rr.bitrate = cfg.bitrate
                    rr.codecName = s.encoder.name
                    created += rr to p
                }
                val outputs = created.map { (rr, p) -> StreamOutput(rr.role, rr.target.physicalId, p.inputSurface, OutputKind.ENCODER) }
                val h = ensureDevice(deviceId)
                h.stopStreaming()
                val fps = fpsRange(targets.first())
                val withPreview = outputs + targets.mapNotNull { previewOutput(it, s.settings, s.deviceRotation) }
                try {
                    h.configure(withPreview, fps, deviceListener)
                } catch (e: Exception) {
                    if (withPreview.size == outputs.size) throw e
                    event(s, "CAMERA", "Device $deviceId: session with preview failed (${e.message}); retrying without preview")
                    h.configure(outputs, fps, deviceListener)
                }
                created.forEach { (rr, p) ->
                    rr.pipeline = p
                    rr.status = if (resolution == VideoResolution.P1080) StreamStatus.RECORDING else StreamStatus.DEGRADED
                    rr.message = null
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "Device $deviceId failed to start", e)
                created.forEach { (rr, p) ->
                    withContext(Dispatchers.IO) { runCatching { p.release() } }
                    rr.status = StreamStatus.FAILED
                    rr.message = e.message
                }
                targets.forEach { t -> s.roles[t.role]?.let { if (it.pipeline == null) { it.status = StreamStatus.FAILED; it.message = e.message } } }
                devices.remove(deviceId)?.close()
                event(s, "ERROR", "Device $deviceId (${targets.joinToString { it.role.name }}) failed: ${e.message}")
            }
        }
        publish(s)
    }

    private fun pipelineListener(s: ActiveSession) = object : VideoEncoderPipeline.Listener {
        override fun onSegmentOpened(role: CameraRole, index: Int, inProgressFile: File, finalFile: File, resolution: VideoResolution) {
            s.track(scope.launch {
                db.media().upsertSegment(
                    MediaSegmentEntity(
                        segmentId = segmentId(s.id, role, index), recordingId = recordingId(s.id, role), sessionId = s.id, segmentIndex = index,
                        resolution = resolution.toString(), startTimestamp = null, endTimestamp = null, frameCount = 0, droppedFrames = 0,
                        filePath = inProgressFile.absolutePath, fileSize = 0, checksum = null, finalizationState = FinalizationState.IN_PROGRESS,
                        uploadState = UploadState.NOT_QUEUED, driveFileId = null, resumableSessionState = null,
                    )
                )
            })
        }

        override fun onSegmentClosed(result: SegmentResult) {
            s.track(scope.launch {
                val rr = s.roles[result.role]
                rr?.segments?.add(result)
                if (rr != null && rr.firstFrameSessionNs == null) rr.firstFrameSessionNs = result.startSessionNs
                db.media().upsertSegment(
                    MediaSegmentEntity(
                        segmentId = segmentId(s.id, result.role, result.index), recordingId = recordingId(s.id, result.role), sessionId = s.id,
                        segmentIndex = result.index, resolution = result.resolution.toString(), startTimestamp = result.startSessionNs,
                        endTimestamp = result.endSessionNs, frameCount = result.frames, droppedFrames = result.droppedFrames,
                        filePath = result.file.absolutePath, fileSize = result.sizeBytes, checksum = null,
                        finalizationState = if (result.finalized) FinalizationState.FINALIZED else FinalizationState.INTERRUPTED_UNRECOVERABLE,
                        uploadState = UploadState.NOT_QUEUED, driveFileId = null, resumableSessionState = null,
                    )
                )
                event(s, "SEGMENT", "${result.role} #${result.index} ${if (result.finalized) "finalized" else "NOT finalized: ${result.error}"} (${result.frames} frames, ${result.droppedFrames} dropped)")
            })
        }

        override fun onClockBaseDetected(role: CameraRole, base: ClockBase, ambiguous: Boolean) {
            scope.launch {
                s.roles[role]?.clockBase = base
                event(s, "SYNC", "$role timestamps in $base${if (ambiguous) " (clocks nearly equal; either mapping is accurate)" else ""}")
            }
        }

        override fun onEncoderError(role: CameraRole, message: String, fatal: Boolean) {
            scope.launch {
                s.recentErrors++
                event(s, "ERROR", "$role: $message")
                if (fatal) {
                    s.roles[role]?.let { rr ->
                        rr.status = StreamStatus.FAILED
                        rr.message = message
                        rr.pipeline?.let { p -> rr.accumulate(p); scope.launch(Dispatchers.IO) { p.release() } }
                        rr.pipeline = null
                    }
                    checkAllFailed(s)
                }
            }
        }
    }

    // ---------------------------------------------------------------- runtime loop

    private suspend fun tickLoop(s: ActiveSession) {
        val planner = SegmentPlanner(s.settings.segmentMinutes * 60_000_000_000L)
        var tick = 0
        while (scope.isActive && session === s && !s.stopping) {
            delay(1_000)
            if (session !== s || s.stopping) break
            tick++
            val nowNs = SystemClock.elapsedRealtimeNanos() - s.timeline.originRealtimeNs
            val h = health.sample()

            // Per-interval drop ratio across active streams.
            var worstDropRatio = 0.0
            val lastFrames = mutableListOf<Long>()
            s.roles.values.forEach { rr ->
                val st = rr.pipeline?.stats?.snapshot() ?: return@forEach
                val prev = s.lastCounts[rr.role] ?: (0L to 0L)
                val df = st.frameCount - prev.first
                val dd = st.droppedFrames - prev.second
                s.lastCounts[rr.role] = st.frameCount to st.droppedFrames
                if (df + dd > 0) worstDropRatio = maxOf(worstDropRatio, dd.toDouble() / (df + dd))
                if (rr.firstFrameSessionNs == null) rr.firstFrameSessionNs = st.firstPtsNs
                st.lastPtsNs?.let { lastFrames += it }
            }
            if (lastFrames.size >= 2) {
                val period = 1_000_000_000L / TARGET_FPS
                for (i in lastFrames.indices) for (j in i + 1 until lastFrames.size) {
                    s.maxPhaseNs = maxOf(s.maxPhaseNs, PhaseAlignment.phaseDifferenceNs(lastFrames[i], lastFrames[j], period))
                }
            }

            // Thermal strategy.
            when (val a = s.thermal.evaluate(HealthSample(nowNs, h.thermalStatus, h.thermalHeadroom, worstDropRatio, s.recentErrors))) {
                ThermalAction.None -> s.thermalWarning = null
                is ThermalAction.Warn -> s.thermalWarning = a.reason
                is ThermalAction.ReduceResolution -> {
                    event(s, "THERMAL", "Reducing all cameras to ${a.to}: ${a.reason}")
                    s.thermalWarning = "Reduced to 720p: ${a.reason}"
                    reconfigure(s, a.to, s.activeRoles(), "thermal: ${a.reason}")
                }
                is ThermalAction.RequestCameraDecision -> requestDecision(s, a.reason)
                is ThermalAction.EmergencyStop -> {
                    event(s, "THERMAL", "Emergency stop: ${a.reason}")
                    requestFinalize("Thermal emergency: ${a.reason}")
                    return
                }
            }
            s.recentErrors = 0

            // Storage guardrail.
            val bytesPerSec = s.roles.values.filter { it.pipeline != null }.sumOf { it.bitrate.toDouble() / 8 } + 20_000
            val st = StoragePolicy.evaluate(storage.freeBytes(), bytesPerSec, s.settings.storageWarnGb * GB, s.settings.storageStopGb * GB)
            s.storageMinutes = st.estimatedMinutesLeft
            s.storageWarning = if (st.warn) "Storage low: about ${st.estimatedMinutesLeft?.toInt() ?: 0} min left" else null
            if (st.stop) {
                event(s, "STORAGE", "Stopping: free storage below ${s.settings.storageStopGb} GB")
                requestFinalize("Storage nearly full")
                return
            }

            // Coordinated segment boundaries.
            if (nowNs - s.segmentEpochNs >= planner.boundaryAfter(s.segmentWindow)) {
                s.segmentWindow++
                s.roles.values.forEach { it.pipeline?.requestSegmentRoll() }
            }

            publish(s, h.thermalStatus, h.thermalHeadroom)
            if (tick % 15 == 0) writeManifest(s, final = false)
        }
    }

    private fun ActiveSession.activeRoles() = roles.values.filter { it.pipeline != null }.map { it.role }.toSet()

    private suspend fun reconfigure(s: ActiveSession, resolution: VideoResolution, keep: Set<CameraRole>, reason: String) {
        val nowMs = (SystemClock.elapsedRealtimeNanos() - s.timeline.originRealtimeNs) / 1e6
        s.reconfiguring = true
        try {
            devices.values.forEach { it.stopStreaming() }
            stopPipelines(s)
            s.roles.values.filter { it.role !in keep && !it.terminal }.forEach {
                it.status = StreamStatus.STOPPED_THERMAL
                it.message = reason
                event(s, "CAMERA", "${it.role} stopped: $reason")
            }
            // Close devices that no longer have kept roles.
            s.topology.topology.deviceIds.forEach { id ->
                if (s.topology.topology.targetsFor(id).none { it.role in keep }) devices.remove(id)?.close()
            }
            keep.forEach { role ->
                s.roles[role]?.let { rr ->
                    if (rr.resolution != resolution) rr.resolutionHistory += ResolutionChange(nowMs, resolution.toString(), reason)
                    rr.resolution = resolution
                }
            }
            startPipelines(s, keep, resolution)
            s.segmentEpochNs = SystemClock.elapsedRealtimeNanos() - s.timeline.originRealtimeNs
            s.segmentWindow = 1
            s.lastCounts.clear()
            checkAllFailed(s)
        } finally {
            s.reconfiguring = false
        }
    }

    private suspend fun stopPipelines(s: ActiveSession) {
        val running = s.roles.values.mapNotNull { rr -> rr.pipeline?.let { rr to it } }
        running.map { (rr, p) ->
            scope.async {
                p.stop()
                rr.accumulate(p)
                rr.nextSegmentIndex = p.nextSegmentIndex
                rr.pipeline = null
            }
        }.awaitAll()
    }

    private suspend fun requestDecision(s: ActiveSession, reason: String) {
        val active = s.activeRoles()
        val options = CameraPriority.options(active, s.settings.cameraPriority)
        val deadline = SystemClock.elapsedRealtime() + s.settings.thermalDecisionTimeoutSec * 1000L
        s.decision = PendingDecision(reason, options, deadline)
        event(s, "THERMAL", "Camera decision requested (${if (appVisible.value) "dialog" else "notification"}): $reason")
        s.decisionJob?.cancel()
        s.decisionJob = scope.launch {
            delay(s.settings.thermalDecisionTimeoutSec * 1000L)
            if (s.decision != null && session === s) {
                val keep = CameraPriority.reduceByOne(s.activeRoles(), s.settings.cameraPriority)
                event(s, "THERMAL", "No answer within ${s.settings.thermalDecisionTimeoutSec}s; applying camera priority -> keep $keep")
                // Fresh job: applying the decision cancels decisionJob, which must not be the caller.
                scope.launch { applyCameraDecision(CameraDecisionOption.Keep(keep, "priority policy"), userChosen = false) }
            }
        }
        publish(s)
    }

    private suspend fun applyCameraDecision(option: CameraDecisionOption, userChosen: Boolean) {
        val s = session ?: return
        s.decision = null
        s.decisionJob?.cancel()
        val nowNs = SystemClock.elapsedRealtimeNanos() - s.timeline.originRealtimeNs
        when (option) {
            CameraDecisionOption.ContinueAll -> event(s, "THERMAL", "User chose to continue with all cameras at ${s.resolution}")
            is CameraDecisionOption.Keep -> {
                event(s, "THERMAL", "${if (userChosen) "User" else "Policy"} kept ${option.roles}")
                reconfigure(s, s.resolution, option.roles, if (userChosen) "user thermal decision" else "camera priority policy")
            }
            CameraDecisionOption.StopAll -> {
                requestFinalize("Stopped after thermal warning")
                return
            }
        }
        s.thermal.onCameraDecisionApplied(nowNs)
        publish(s)
    }

    private suspend fun handleDeviceLost(deviceId: String, why: String) {
        devices.remove(deviceId)?.close()
        val s = session
        if (s == null || s.stopping) return
        if (s.reconfiguring) return
        val affected = s.topology.topology.targetsFor(deviceId).mapNotNull { s.roles[it.role] }.filter { it.pipeline != null }
        if (affected.isEmpty()) return
        event(s, "CAMERA", "Camera device $deviceId $why; affected ${affected.map { it.role }}")
        affected.map { rr ->
            scope.async {
                rr.status = StreamStatus.DISCONNECTED
                rr.message = "Camera $why"
                rr.pipeline?.let { p -> p.stop(); rr.accumulate(p); rr.nextSegmentIndex = p.nextSegmentIndex }
                rr.pipeline = null
            }
        }.awaitAll()
        _state.update { it.copy(phase = SessionPhase.CAMERA_DISCONNECTED) }
        publish(s)
        // One controlled re-open attempt; other cameras continue regardless.
        if (affected.none { it.reopenAttempted }) {
            affected.forEach { it.reopenAttempted = true }
            delay(3_000)
            if (session === s && !s.stopping) {
                event(s, "CAMERA", "Re-opening $deviceId")
                startPipelines(s, affected.map { it.role }.toSet(), s.resolution)
            }
        } else {
            affected.forEach { it.status = StreamStatus.FAILED }
        }
        checkAllFailed(s)
    }

    private fun checkAllFailed(s: ActiveSession) {
        if (!s.stopping && s.roles.values.none { it.pipeline != null } && !s.reconfiguring) {
            requestFinalize("All cameras stopped or failed")
        }
    }

    /** Finalization always runs in its own job so it can safely cancel the tick/decision jobs. */
    private fun requestFinalize(reason: String) {
        scope.launch { finalizeSession(reason) }
    }

    // ---------------------------------------------------------------- finalization

    private suspend fun finalizeSession(reason: String) {
        val s = session ?: return
        if (s.stopping) return
        s.stopping = true
        while (s.reconfiguring) delay(50)
        val self = currentCoroutineContext()[Job]
        s.tickJob?.takeIf { it !== self }?.cancel()
        s.decisionJob?.takeIf { it !== self }?.cancel()
        s.decision = null
        _state.update { it.copy(phase = SessionPhase.FINALIZING) }
        event(s, "INFO", "Stopping: $reason")

        devices.values.forEach { it.stopStreaming() }
        stopPipelines(s)
        s.roles.values.filter { it.status == StreamStatus.RECORDING || it.status == StreamStatus.DEGRADED }.forEach { it.status = StreamStatus.STOPPED_BY_USER }
        s.audioResult = withContext(Dispatchers.IO) { s.audio?.stop() }
        s.pending.toList().joinAll()

        val endNs = SystemClock.elapsedRealtimeNanos()
        val durationMs = (endNs - s.timeline.originRealtimeNs) / 1_000_000
        val audio = s.audioResult
        audio?.let { a ->
            db.media().upsertAudio(
                AudioRecordingEntity(
                    audioId = "${s.id}_audio", sessionId = s.id, codec = "AAC", sampleRate = a.sampleRate, channels = a.channels,
                    bitrate = a.bitrate, startTimestamp = a.firstSampleSessionNs, endTimestamp = a.firstSampleSessionNs?.plus(a.durationNs),
                    filePath = a.file.absolutePath, fileSize = a.file.length(), checksum = null,
                    finalizationState = if (a.finalized) FinalizationState.FINALIZED else FinalizationState.INTERRUPTED_UNRECOVERABLE,
                    uploadState = UploadState.NOT_QUEUED, driveFileId = null,
                )
            )
        }
        s.roles.values.forEach { rr ->
            db.media().upsertCamera(
                CameraRecordingEntity(
                    recordingId = recordingId(s.id, rr.role), sessionId = s.id, cameraType = rr.role, cameraId = rr.target.deviceId,
                    physicalCameraId = rr.target.physicalId, resolution = rr.resolution.toString(), targetFps = TARGET_FPS,
                    actualFps = rr.averageFps(), codec = s.settings.codec.manifestName, codecName = rr.codecName, bitrate = rr.bitrate,
                    frameCount = rr.totalFrames, droppedFrameCount = rr.totalDrops, syncOffsetMs = rr.firstFrameSessionNs?.let { it / 1e6 },
                    filePath = storage.cameraDir(s.relativeDir, rr.role).absolutePath, checksum = null, status = rr.status,
                )
            )
        }
        writeManifest(s, final = true, stopReason = reason)
        val recordedRoles = s.roles.values.filter { rr -> rr.segments.any { it.finalized } }.map { it.role }
        val anyVideo = recordedRoles.isNotEmpty()
        db.sessions().get(s.id)?.let { e ->
            db.sessions().update(
                e.copy(
                    endMonotonicTimestamp = endNs, durationMs = durationMs,
                    recordingState = if (anyVideo) RecordingState.COMPLETED else RecordingState.FAILED,
                    localStorageSize = storage.sizeOf(storage.sessionDir(s.relativeDir)),
                    stopReason = reason, hasAudio = audio?.finalized == true,
                    camerasRecorded = recordedRoles.joinToString(",") { it.name },
                )
            )
        }
        runCatching { onSessionFinalized(s.id) }.onFailure { AppLog.e(TAG, "Post-finalize hook failed", it) }

        session = null
        _state.update {
            it.copy(
                phase = if (anyVideo) SessionPhase.COMPLETED else SessionPhase.FAILED,
                lastStopReason = reason, lastSessionId = s.id, decision = null, endedCount = it.endedCount + 1,
            )
        }
        // Return to preview if the recorder is on screen, otherwise release cameras.
        closeAllDevices()
        preview.releaseAll()
        previewReaders.clear()
        if (previewRequested && appVisible.value) runCatching { openPreview() }
    }

    // ---------------------------------------------------------------- state, events, manifest

    private fun publish(s: ActiveSession, thermal: Int? = null, headroom: Float? = null) {
        val elapsed = (SystemClock.elapsedRealtimeNanos() - s.timeline.originRealtimeNs) / 1_000_000
        val anyDisconnected = s.roles.values.any { it.status == StreamStatus.DISCONNECTED }
        val degraded = s.resolution != VideoResolution.P1080 || s.roles.values.any { it.status == StreamStatus.STOPPED_THERMAL || it.status == StreamStatus.FAILED }
        _state.update { st ->
            st.copy(
                phase = when {
                    s.stopping -> SessionPhase.FINALIZING
                    s.decision != null -> SessionPhase.WAITING_FOR_THERMAL_DECISION
                    anyDisconnected -> SessionPhase.CAMERA_DISCONNECTED
                    degraded -> SessionPhase.RECORDING_DEGRADED
                    st.phase == SessionPhase.INITIALIZING && s.roles.values.none { it.pipeline != null } -> SessionPhase.INITIALIZING
                    else -> SessionPhase.RECORDING
                },
                elapsedMs = elapsed,
                cameras = s.roles.mapValues { (_, rr) ->
                    val snap = rr.pipeline?.stats?.snapshot()
                    CameraTelemetry(
                        role = rr.role, status = rr.status, resolution = if (rr.pipeline != null) rr.resolution.toString() else null,
                        fps = snap?.currentFps, frames = rr.totalFrames, dropped = rr.totalDrops, bufferLost = rr.bufferLost, message = rr.message,
                    )
                },
                audioStatus = s.audioStatus,
                thermalStatus = thermal ?: st.thermalStatus,
                thermalHeadroom = headroom ?: st.thermalHeadroom,
                thermalWarning = s.thermalWarning,
                freeBytes = storage.freeBytes(),
                minutesLeft = s.storageMinutes,
                storageWarning = s.storageWarning,
                decision = s.decision,
                maxPhaseMs = if (s.maxPhaseNs > 0) s.maxPhaseNs / 1e6 else null,
                errors = s.events.filter { it.type == "ERROR" }.takeLast(5).map { it.message },
            )
        }
    }

    private suspend fun event(s: ActiveSession, type: String, message: String) {
        val t = (SystemClock.elapsedRealtimeNanos() - s.timeline.originRealtimeNs) / 1e6
        s.events += SessionEvent(t, type, message)
        AppLog.i(TAG, "[$type] $message")
        runCatching { db.media().insertEvent(SessionEventEntity(sessionId = s.id, sessionTimeMs = t, wallTimeMs = System.currentTimeMillis(), type = type, message = message)) }
    }

    private suspend fun writeManifest(s: ActiveSession, final: Boolean, stopReason: String? = null) {
        val manifest = buildManifest(s, final, stopReason)
        withContext(Dispatchers.IO) {
            runCatching { storage.manifestFile(s.relativeDir).writeTextAtomically(SessionManifest.json.encodeToString(SessionManifest.serializer(), manifest)) }
                .onFailure { AppLog.e(TAG, "Manifest write failed", it) }
        }
    }

    private fun buildManifest(s: ActiveSession, final: Boolean, stopReason: String?): SessionManifest {
        val sessionDir = storage.sessionDir(s.relativeDir)
        fun rel(f: File) = f.relativeTo(sessionDir).path
        val audio = s.audioResult
        val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
        return SessionManifest(
            sessionId = s.id,
            displayName = s.displayName,
            startTimeUtc = Instant.ofEpochMilli(s.wallStartMs).toString(),
            endTimeUtc = if (final) Instant.now().toString() else null,
            timezone = s.zone.id,
            targetFrameRate = TARGET_FPS,
            videoCodec = s.settings.codec.manifestName,
            audioCodec = "AAC",
            app = AppInfo(BuildConfig.VERSION_NAME, pkg.longVersionCode),
            device = DeviceInfo(Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE, Build.VERSION.SDK_INT, Build.FINGERPRINT),
            captureArchitecture = s.topology.topology.architecture.name,
            cameras = CameraRole.entries.associate { role ->
                val rr = s.roles[role]
                role.manifestKey to if (rr == null) {
                    CameraTrack(enabled = false, cameraId = null, resolution = "", frameRate = TARGET_FPS, status = StreamStatus.UNAVAILABLE.name)
                } else CameraTrack(
                    enabled = true, cameraId = rr.target.deviceId, physicalCameraId = rr.target.physicalId, resolution = rr.resolution.toString(),
                    frameRate = TARGET_FPS, codecName = rr.codecName, bitrate = rr.bitrate, bitrateMode = if (s.encoder.vbr) "VBR" else "default",
                    timestampClock = rr.clockBase?.name, status = rr.status.name, frameCount = rr.totalFrames, droppedFrames = rr.totalDrops,
                    averageFps = rr.averageFps(), maxFrameGapMs = rr.maxGapMs(), firstFrameSessionMs = rr.firstFrameSessionNs?.let { it / 1e6 },
                    resolutionHistory = rr.resolutionHistory.toList(),
                )
            },
            audio = audio?.let { a ->
                AudioTrack(
                    enabled = true, file = rel(a.file), sampleRate = a.sampleRate, channels = a.channels, format = "AAC-LC/M4A", bitrate = a.bitrate,
                    firstSampleSessionMs = a.firstSampleSessionNs?.let { it / 1e6 }, timestampSource = a.timestampSource,
                    durationMs = a.durationNs / 1e6, driftPpm = a.driftPpm, accumulatedDriftMs = a.accumulatedDriftMs,
                    routing = a.routes.map { AudioRoute(it.sessionTimeMs, it.deviceType, it.deviceName) },
                )
            } ?: if (s.audio != null) AudioTrack(true, "audio/${SessionStorage.AUDIO_NAME}", 48_000, 1, "AAC-LC/M4A", 160_000, null, "pending", null) else null,
            synchronization = SyncInfo(
                originElapsedRealtimeNs = s.timeline.originRealtimeNs,
                sensorSyncType = s.topology.sensors.firstOrNull { it.id == s.topology.topology.target(CameraRole.REAR_MAIN)?.deviceId }?.syncTypeLabel(),
                maxPairwisePhaseMs = if (s.maxPhaseNs > 0) s.maxPhaseNs / 1e6 else null,
                notes = listOf(
                    "All timestamps are milliseconds on the session timeline (0 = record pressed, CLOCK_BOOTTIME).",
                    "Place each segment on an editor timeline at its startSessionMs; place audio at audio.firstSampleSessionMs.",
                    "Independently clocked sensors can differ in frame phase by up to half a frame period (16.7 ms at 30 fps); see maxPairwisePhaseMs.",
                ),
            ),
            segments = s.roles.values.flatMap { rr ->
                rr.segments.map { seg ->
                    SegmentInfo(
                        camera = rr.role.manifestKey, index = seg.index, file = rel(seg.file), resolution = seg.resolution.toString(),
                        startSessionMs = seg.startSessionNs?.let { it / 1e6 }, endSessionMs = seg.endSessionNs?.let { it / 1e6 },
                        frameCount = seg.frames, droppedFrames = seg.droppedFrames, sizeBytes = seg.sizeBytes,
                        finalization = if (seg.finalized) "FINALIZED" else "NOT_FINALIZED: ${seg.error}",
                    )
                }
            }.sortedWith(compareBy({ it.camera }, { it.index })),
            events = s.events.toList(),
            stopReason = stopReason,
        )
    }

    // ---------------------------------------------------------------- runtime holders

    private inner class ActiveSession(
        val id: String,
        val displayName: String,
        val relativeDir: String,
        val timeline: SessionTimeline,
        val wallStartMs: Long,
        val zone: ZoneId,
        val settings: AppSettings,
        val encoder: EncoderInfo,
        val deviceRotation: Int,
        val topology: ResolvedTopology,
    ) {
        val roles = linkedMapOf<CameraRole, RoleRuntime>()
        var resolution: VideoResolution = VideoResolution.P1080
        var audio: MasterAudioRecorder? = null
        var audioResult: AudioResult? = null
        var audioStatus = "Idle"
        val thermal = ThermalPolicy()
        var thermalWarning: String? = null
        var storageWarning: String? = null
        var storageMinutes: Double? = null
        val events = mutableListOf<SessionEvent>()
        var segmentEpochNs = 0L
        var segmentWindow = 1
        var maxPhaseNs = 0L
        var recentErrors = 0
        val lastCounts = mutableMapOf<CameraRole, Pair<Long, Long>>()
        var stopping = false
        var reconfiguring = false
        var decision: PendingDecision? = null
        var decisionJob: Job? = null
        var tickJob: Job? = null
        val pending: MutableList<Job> = Collections.synchronizedList(mutableListOf())
        fun track(job: Job) {
            pending += job
        }
    }

    private class RoleRuntime(val role: CameraRole, val target: StreamTarget, val sensor: SensorInfo?) {
        var status = StreamStatus.INITIALIZING
        var pipeline: VideoEncoderPipeline? = null
        var nextSegmentIndex = 1
        var resolution: VideoResolution = VideoResolution.P1080
        val resolutionHistory = mutableListOf<ResolutionChange>()
        var bitrate = 0
        var codecName: String? = null
        var bufferLost = 0L
        var firstFrameSessionNs: Long? = null
        var clockBase: ClockBase? = null
        var message: String? = null
        var reopenAttempted = false
        val segments = mutableListOf<SegmentResult>()
        private var doneFrames = 0L
        private var doneDrops = 0L
        private var doneSpanNs = 0L
        private var maxGap = 0.0

        val terminal: Boolean get() = status == StreamStatus.FAILED || status == StreamStatus.STOPPED_THERMAL

        fun accumulate(p: VideoEncoderPipeline) {
            val s = p.stats.snapshot()
            doneFrames += s.frameCount
            doneDrops += s.droppedFrames
            if (s.firstPtsNs != null && s.lastPtsNs != null) doneSpanNs += s.lastPtsNs - s.firstPtsNs
            maxGap = maxOf(maxGap, s.maxGapMs)
        }

        val totalFrames: Long get() = doneFrames + (pipeline?.stats?.snapshot()?.frameCount ?: 0)
        val totalDrops: Long get() = doneDrops + (pipeline?.stats?.snapshot()?.droppedFrames ?: 0)

        fun averageFps(): Double? {
            val cur = pipeline?.stats?.snapshot()
            val span = doneSpanNs + if (cur?.firstPtsNs != null && cur.lastPtsNs != null) cur.lastPtsNs - cur.firstPtsNs else 0
            val frames = totalFrames
            return if (span > 0 && frames > 1) (frames - 1) * 1e9 / span else null
        }

        fun maxGapMs(): Double = maxOf(maxGap, pipeline?.stats?.snapshot()?.maxGapMs ?: 0.0)
    }

    companion object {
        private const val TAG = "Engine"
        const val TARGET_FPS = 30
        private const val GB = 1_000_000_000L
        fun recordingId(sessionId: String, role: CameraRole) = "${sessionId}_${role.dirName}"
        fun segmentId(sessionId: String, role: CameraRole, index: Int) = "${sessionId}_${role.dirName}_%03d".format(index)
    }
}
