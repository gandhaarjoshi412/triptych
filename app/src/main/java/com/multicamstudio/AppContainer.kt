package com.multicamstudio

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import com.multicamstudio.core.camera.PreviewHub
import com.multicamstudio.core.cloud.DriveClient
import com.multicamstudio.core.cloud.DriveFolderResolver
import com.multicamstudio.core.cloud.GoogleAuthManager
import com.multicamstudio.core.cloud.SecretBox
import com.multicamstudio.core.cloud.UploadEngine
import com.multicamstudio.core.common.AppLog
import com.multicamstudio.core.recording.RecordingEngine
import com.multicamstudio.core.recording.RecoveryManager
import com.multicamstudio.core.storage.SessionStorage
import com.multicamstudio.core.thermal.SystemHealthMonitor
import com.multicamstudio.data.local.AppDatabase
import com.multicamstudio.data.prefs.SettingsRepository
import com.multicamstudio.data.repo.SessionRepository
import com.multicamstudio.domain.upload.AutoUploadInputs
import com.multicamstudio.domain.upload.AutoUploadPolicy
import com.multicamstudio.domain.upload.AutoUploadThresholds
import com.multicamstudio.domain.upload.UploadGate
import com.multicamstudio.services.upload.AppVisibilityTracker
import com.multicamstudio.services.upload.ManualTransferState
import com.multicamstudio.services.upload.UploadScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Manual dependency wiring. Small app, no DI framework needed. */
class AppContainer(private val app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val db: AppDatabase = AppDatabase.build(app)
    val settings = SettingsRepository(app)
    val storage = SessionStorage(app)
    val health = SystemHealthMonitor(app).also { it.start() }
    val preview = PreviewHub()
    val scheduler = UploadScheduler(app, settings)

    val visibility = AppVisibilityTracker(
        settings, appScope,
        onBackground = { appScope.launch { if (!engine.isRecording()) scheduler.scheduleAuto() } },
        onForeground = { scheduler.cancelAuto() },
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .followRedirects(false)
        .retryOnConnectionFailure(true)
        .build()
    val drive = DriveClient(http)
    val auth = GoogleAuthManager(app)
    val folders = DriveFolderResolver(db, drive, settings)
    val uploadEngine = UploadEngine(db, drive, auth, folders, SecretBox())

    val sessions = SessionRepository(
        db, storage, drive, auth,
        isRecordingSession = { id -> engine.state.value.let { it.phase.isActive && it.sessionId == id } },
        isUploading = { uploadEngine.isRunning },
    )

    val engine: RecordingEngine = RecordingEngine(
        app, db, storage, settings, health, preview, visibility.visible,
        onSessionFinalized = { id ->
            val auto = settings.current().autoUploadEnabled
            sessions.enqueueSession(id, autoQueue = auto)
            // If the recording ended while the app was in the background, start the eligibility clock now.
            if (!visibility.visible.value) scheduler.scheduleAuto()
        },
    )

    private val recovery = RecoveryManager(db, storage) { id -> sessions.enqueueSession(id, settings.current().autoUploadEnabled) }

    fun onCreate() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(visibility)
        appScope.launch {
            runCatching { recovery.recover() }.onFailure { AppLog.e("App", "Recovery failed", it) }
        }
    }

    suspend fun autoUploadGate(): UploadGate {
        val s = settings.current()
        val h = health.sample()
        return AutoUploadPolicy.evaluate(
            AutoUploadInputs(
                nowMs = System.currentTimeMillis(),
                autoUploadEnabled = s.autoUploadEnabled,
                isRecording = engine.isRecording(),
                appInForeground = visibility.visible.value,
                backgroundSinceMs = settings.backgroundSince.first(),
                networkAvailable = health.isNetworkAvailable(),
                thermalStatus = h.thermalStatus,
                systemLowMemory = h.lowMemory,
                availableMemoryFraction = h.availableMemoryFraction,
                manualTransferRunning = ManualTransferState.running,
            ),
            AutoUploadThresholds(
                backgroundDelayMs = s.autoUploadDelayMin * 60_000L,
                pauseThermalStatus = s.uploadPauseThermalStatus,
                minAvailableMemoryFraction = s.uploadMinFreeMemoryPercent / 100.0,
            ),
        )
    }

    fun manualUploadGate(): UploadGate =
        AutoUploadPolicy.manualGate(engine.isRecording(), health.sample().thermalStatus, health.isNetworkAvailable())
}
