package com.multicamstudio.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.multicamstudio.MainActivity
import com.multicamstudio.R
import com.multicamstudio.core.cloud.UploadProgress
import com.multicamstudio.core.common.formatBytes
import com.multicamstudio.core.recording.RecordingUiState
import com.multicamstudio.domain.model.SessionPhase
import com.multicamstudio.domain.model.ThermalStatus

object Notifications {
    const val CHANNEL_RECORDING = "recording"
    const val CHANNEL_ALERTS = "alerts"
    const val CHANNEL_UPLOADS = "uploads"

    const val ID_RECORDING = 1001
    const val ID_THERMAL = 1002
    const val ID_UPLOAD = 1003
    const val ID_AUTH = 1004

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_RECORDING, "Recording", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Ongoing recording status"
            setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERTS, "Recording alerts", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Thermal warnings, camera problems, stopped recordings"
        })
        nm.createNotificationChannel(NotificationChannel(CHANNEL_UPLOADS, "Backups", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Google Drive backup progress"
            setShowBadge(false)
        })
    }

    private fun openApp(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun recording(context: Context, state: RecordingUiState): Notification {
        val stop = PendingIntent.getService(
            context, 1, Intent(context, RecordingForegroundService::class.java).setAction(RecordingForegroundService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val res = state.cameras.values.firstOrNull { it.resolution != null }?.resolution
        val text = buildString {
            append(
                when (state.phase) {
                    SessionPhase.INITIALIZING -> "Starting cameras…"
                    SessionPhase.FINALIZING -> "Finalizing files…"
                    SessionPhase.CAMERA_DISCONNECTED -> "${state.activeCameraCount} camera(s) active · a camera disconnected"
                    SessionPhase.WAITING_FOR_THERMAL_DECISION -> "Overheating – choose cameras"
                    else -> "${state.activeCameraCount} camera(s) active" + (res?.let { " · $it" } ?: "")
                }
            )
            if (state.thermalStatus >= ThermalStatus.MODERATE) append(" · ${ThermalStatus.label(state.thermalStatus)} heat")
        }
        return NotificationCompat.Builder(context, CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_stat_record)
            .setContentTitle("MultiCam Studio recording")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setUsesChronometer(state.startedWallMs != null)
            .setWhen(state.startedWallMs ?: System.currentTimeMillis())
            .setShowWhen(state.startedWallMs != null)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp(context))
            .addAction(0, "Stop", stop)
            .build()
    }

    fun alert(context: Context, id: Int, title: String, text: String) {
        val n = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_record)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(id, n) }
    }

    fun upload(context: Context, p: UploadProgress, title: String = "Backing up to Google Drive"): Notification {
        val b = NotificationCompat.Builder(context, CHANNEL_UPLOADS)
            .setSmallIcon(R.drawable.ic_stat_record)
            .setContentTitle(title)
            .setContentText(p.currentFile?.let { "$it · ${p.currentBytes.formatBytes()} / ${p.currentTotal.formatBytes()}" } ?: p.status)
            .setOngoing(p.running)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(context))
        if (p.running && p.currentTotal > 0) {
            b.setProgress(1000, (p.currentBytes * 1000 / p.currentTotal).toInt(), false)
        }
        return b.build()
    }

    fun notify(context: Context, id: Int, n: Notification) {
        runCatching { context.getSystemService(NotificationManager::class.java).notify(id, n) }
    }

    fun cancel(context: Context, id: Int) {
        context.getSystemService(NotificationManager::class.java).cancel(id)
    }
}
