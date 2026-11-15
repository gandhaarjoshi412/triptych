package com.multicamstudio.services.upload

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.Build
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.multicamstudio.core.common.AppLog
import com.multicamstudio.data.prefs.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Tracks whether any app UI is visible (ProcessLifecycleOwner) and persists when the app went to the
 * background, so a worker started later in a fresh process can still apply the 5-minute rule.
 */
class AppVisibilityTracker(
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val onBackground: () -> Unit,
    private val onForeground: () -> Unit,
) : DefaultLifecycleObserver {
    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    override fun onStart(owner: LifecycleOwner) {
        _visible.value = true
        scope.launch { settings.setBackgroundSince(null) }
        onForeground()
    }

    override fun onStop(owner: LifecycleOwner) {
        _visible.value = false
        scope.launch {
            settings.setBackgroundSince(System.currentTimeMillis())
            onBackground()
        }
    }
}

/**
 * Automatic uploads: a unique one-time WorkManager job, enqueued with an initial delay equal to the
 * background requirement each time the app is backgrounded, cancelled when it returns to the foreground.
 * Network: any connected network (Wi-Fi or mobile). No charging / unmetered / device-idle constraints.
 *
 * Manual uploads: User-Initiated Data Transfer job on API 34+, expedited foreground worker below.
 */
class UploadScheduler(private val context: Context, private val settings: SettingsRepository) {
    private val wm get() = WorkManager.getInstance(context)

    suspend fun scheduleAuto(delayMs: Long? = null) {
        val s = settings.current()
        if (!s.autoUploadEnabled) return
        val since = settings.backgroundSinceOrNull()
        val required = s.autoUploadDelayMin * 60_000L
        val delay = delayMs ?: if (since != null) (required - (System.currentTimeMillis() - since)).coerceAtLeast(0) else required
        val req = OneTimeWorkRequestBuilder<AutoUploadWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 60, TimeUnit.SECONDS)
            .build()
        wm.enqueueUniqueWork(AUTO_WORK, ExistingWorkPolicy.REPLACE, req)
        AppLog.i(TAG, "Auto upload eligible in ${delay / 1000}s")
    }

    fun cancelAuto() {
        wm.cancelUniqueWork(AUTO_WORK)
    }

    /** Must be called while the app is visible (required for user-initiated jobs). */
    fun scheduleManual(estimatedUploadBytes: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val js = context.getSystemService(JobScheduler::class.java)
            val job = JobInfo.Builder(MANUAL_JOB_ID, ComponentName(context, ManualUploadJobService::class.java))
                .setUserInitiated(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setEstimatedNetworkBytes(JobInfo.NETWORK_BYTES_UNKNOWN, estimatedUploadBytes.coerceAtLeast(1))
                .build()
            val r = js.schedule(job)
            AppLog.i(TAG, "Scheduled user-initiated transfer job: result=$r")
        } else {
            val req = OneTimeWorkRequestBuilder<ManualUploadWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniqueWork(MANUAL_WORK, ExistingWorkPolicy.KEEP, req)
        }
    }

    fun cancelManual() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            context.getSystemService(JobScheduler::class.java).cancel(MANUAL_JOB_ID)
        }
        wm.cancelUniqueWork(MANUAL_WORK)
        ManualTransferState.cancelRequested = true
    }

    companion object {
        private const val TAG = "UploadScheduler"
        const val AUTO_WORK = "auto-upload"
        const val MANUAL_WORK = "manual-upload"
        const val MANUAL_JOB_ID = 4201
    }
}

/** In-process flags shared by the manual transfer implementations and the auto-upload gate. */
object ManualTransferState {
    @Volatile var running = false
    @Volatile var cancelRequested = false
}

private suspend fun SettingsRepository.backgroundSinceOrNull(): Long? = backgroundSince.first()
