package com.multicamstudio.services

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.multicamstudio.MultiCamApp
import com.multicamstudio.core.common.AppLog
import com.multicamstudio.domain.model.SessionPhase
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground service (types camera|microphone) that keeps the recording alive when the app is minimised
 * or the screen is off. It must be started while an activity is visible and permissions are granted
 * (Android's while-in-use rules) – [start] is only called from the Record screen.
 *
 * It does not restart itself after a force-stop or reboot (START_NOT_STICKY), by design.
 * No wake lock is held: camera + foreground service keep the pipeline running with the screen off;
 * this is verified as part of docs/DEVICE_TEST_PLAN.md.
 */
class RecordingForegroundService : LifecycleService() {
    private var observer: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val c = (application as MultiCamApp).container
        when (intent?.action) {
            ACTION_START -> {
                if (!goForeground()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                observe()
                if (!c.engine.isRecording()) c.engine.startRecording(intent.getIntExtra(EXTRA_ROTATION, 0))
            }
            ACTION_STOP -> {
                if (c.engine.isRecording()) c.engine.stopRecording("Stopped from notification") else stopSelf()
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun goForeground(): Boolean {
        val c = (application as MultiCamApp).container
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (granted(Manifest.permission.RECORD_AUDIO)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        return try {
            ServiceCompat.startForeground(this, Notifications.ID_RECORDING, Notifications.recording(this, c.engine.state.value), types)
            true
        } catch (e: Exception) {
            // e.g. ForegroundServiceStartNotAllowedException / SecurityException when not started from a visible activity.
            AppLog.e(TAG, "Cannot start foreground recording service", e)
            Notifications.alert(this, Notifications.ID_THERMAL, "Recording could not start", "Android did not allow the recording service to start: ${e.message}")
            false
        }
    }

    private fun observe() {
        if (observer != null) return
        val c = (application as MultiCamApp).container
        val endedAtStart = c.engine.state.value.endedCount
        observer = lifecycleScope.launch {
            // Update the notification only when something user-visible changes (the timer is a chronometer).
            launch {
                c.engine.state.map { s ->
                    listOf(s.phase, s.activeCameraCount, s.thermalStatus, s.cameras.values.firstOrNull { it.resolution != null }?.resolution, s.startedWallMs)
                }.distinctUntilChanged().collect {
                    Notifications.notify(this@RecordingForegroundService, Notifications.ID_RECORDING, Notifications.recording(this@RecordingForegroundService, c.engine.state.value))
                }
            }
            // Thermal decision while the UI is not visible -> high-priority notification.
            launch {
                c.engine.state.map { it.decision }.distinctUntilChanged().collect { d ->
                    if (d != null && !c.visibility.visible.value) {
                        Notifications.alert(
                            this@RecordingForegroundService, Notifications.ID_THERMAL, "Phone is overheating",
                            "${d.reason}. Open the app to choose which cameras to keep; otherwise your camera priority is applied automatically.",
                        )
                    }
                }
            }
            c.engine.state.map { it.endedCount }.distinctUntilChanged().collect { ended ->
                if (ended > endedAtStart) {
                    val st = c.engine.state.value
                    val phase = st.phase
                    if (!c.visibility.visible.value && st.lastStopReason != null && st.lastStopReason != "Stopped by user") {
                        Notifications.alert(this@RecordingForegroundService, Notifications.ID_THERMAL, if (phase == SessionPhase.COMPLETED) "Recording saved" else "Recording failed", st.lastStopReason)
                    }
                    ServiceCompat.stopForeground(this@RecordingForegroundService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "RecordingService"
        const val ACTION_START = "com.multicamstudio.action.START"
        const val ACTION_STOP = "com.multicamstudio.action.STOP"
        const val EXTRA_ROTATION = "rotation"

        fun start(context: Context, deviceRotationDeg: Int) {
            val i = Intent(context, RecordingForegroundService::class.java).setAction(ACTION_START).putExtra(EXTRA_ROTATION, deviceRotationDeg)
            ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RecordingForegroundService::class.java).setAction(ACTION_STOP))
        }
    }
}
