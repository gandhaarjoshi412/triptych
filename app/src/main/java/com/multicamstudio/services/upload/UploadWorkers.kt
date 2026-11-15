package com.multicamstudio.services.upload

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.multicamstudio.MultiCamApp
import com.multicamstudio.core.cloud.RunOutcome
import com.multicamstudio.core.cloud.UploadMode
import com.multicamstudio.domain.upload.UploadGate
import com.multicamstudio.services.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Automatic backup. Runs only when the gate allows (not recording, app backgrounded >= 5 min, network,
 * no thermal/memory pressure) and only for a bounded time per run so it respects WorkManager's
 * execution limits; remaining work is retried with backoff.
 */
class AutoUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as MultiCamApp).container
        val gate: suspend () -> UploadGate = { if (isStopped) UploadGate.Blocked("Stopped by system") else c.autoUploadGate() }
        when (gate()) {
            is UploadGate.Blocked -> return Result.success()
            is UploadGate.Wait -> return Result.retry()
            UploadGate.Allowed -> Unit
        }
        return when (val out = c.uploadEngine.run(UploadMode.AUTO, gate, budgetMs = 8 * 60_000L)) {
            RunOutcome.Completed -> Result.success()
            is RunOutcome.Paused -> if (out.retryAfterMs != null) Result.retry() else Result.success()
            RunOutcome.AuthRequired -> {
                Notifications.alert(applicationContext, Notifications.ID_AUTH, "Google Drive needs reconnecting", "Automatic backup is paused. Open MultiCam Studio → Settings → Google Drive.")
                Result.success()
            }
        }
    }
}

/** Shared body of the manual transfer for both the UIDT job (API 34+) and the worker fallback. */
internal suspend fun runManualTransfer(context: Context, isStopped: () -> Boolean): RunOutcome {
    val c = (context.applicationContext as MultiCamApp).container
    ManualTransferState.running = true
    ManualTransferState.cancelRequested = false
    try {
        val gate: suspend () -> UploadGate = {
            if (isStopped() || ManualTransferState.cancelRequested) UploadGate.Blocked("Cancelled") else c.manualUploadGate()
        }
        val out = c.uploadEngine.run(UploadMode.MANUAL, gate)
        if (out == RunOutcome.AuthRequired) {
            Notifications.alert(context, Notifications.ID_AUTH, "Google Drive needs reconnecting", "Open MultiCam Studio → Settings → Google Drive.")
        }
        return out
    } finally {
        ManualTransferState.running = false
    }
}

/** API 34+: user-initiated data transfer job – the platform's mechanism for long user-requested transfers. */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class ManualUploadJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var stopped = false

    override fun onStartJob(params: JobParameters): Boolean {
        val c = (applicationContext as MultiCamApp).container
        setNotification(params, Notifications.ID_UPLOAD, Notifications.upload(this, c.uploadEngine.progress.value), JOB_END_NOTIFICATION_POLICY_REMOVE)
        stopped = false
        scope.launch {
            val ticker = launch {
                while (isActive) {
                    Notifications.notify(this@ManualUploadJobService, Notifications.ID_UPLOAD, Notifications.upload(this@ManualUploadJobService, c.uploadEngine.progress.value))
                    delay(2_000)
                }
            }
            val out = runManualTransfer(this@ManualUploadJobService) { stopped }
            ticker.cancel()
            val reschedule = out is RunOutcome.Paused && out.retryAfterMs != null && !stopped && !ManualTransferState.cancelRequested
            jobFinished(params, reschedule)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        stopped = true
        return !ManualTransferState.cancelRequested
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

/** API 30–33 fallback: expedited worker promoted to a dataSync foreground service. */
class ManualUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val c = (applicationContext as MultiCamApp).container
        return ForegroundInfo(Notifications.ID_UPLOAD, Notifications.upload(applicationContext, c.uploadEngine.progress.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    override suspend fun doWork(): Result {
        runCatching { setForeground(getForegroundInfo()) }
        val out = runManualTransfer(applicationContext) { isStopped }
        return if (out is RunOutcome.Paused && out.retryAfterMs != null && !ManualTransferState.cancelRequested) Result.retry() else Result.success()
    }
}
