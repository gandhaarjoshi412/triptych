package com.multicamstudio.core.thermal

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import com.multicamstudio.core.common.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

data class SystemHealth(
    val thermalStatus: Int = PowerManager.THERMAL_STATUS_NONE,
    /** Forecast headroom for ~10s ahead; null when unsupported or rate-limited. */
    val thermalHeadroom: Float? = null,
    val availMemBytes: Long = 0,
    val totalMemBytes: Long = 0,
    val lowMemory: Boolean = false,
    val lastTrimLevel: Int? = null,
    val appPssKb: Long? = null,
    /** CPU time consumed by this process since start (ms). Android exposes no supported API for other apps' CPU use. */
    val appCpuTimeMs: Long = 0,
    val sampledAtElapsedMs: Long = 0,
) {
    val availableMemoryFraction: Double get() = if (totalMemBytes > 0) availMemBytes.toDouble() / totalMemBytes else 1.0
}

/**
 * Lightweight system-health sampling: thermal status via listener (push), everything else on demand.
 *
 * Platform limitation (documented in docs/HANDOFF.md): ordinary apps cannot attribute global CPU
 * usage to other apps. We use thermal status/headroom, system memory pressure and trim callbacks
 * as proxies for "the phone is busy".
 */
class SystemHealthMonitor(private val context: Context) : ComponentCallbacks2 {
    private val power = context.getSystemService(PowerManager::class.java)
    private val activityManager = context.getSystemService(ActivityManager::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val listenerExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "thermal-listener") }

    private val _health = MutableStateFlow(SystemHealth())
    val health: StateFlow<SystemHealth> = _health.asStateFlow()

    private var lastHeadroomCallMs = 0L
    private var lastHeadroom: Float? = null

    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        AppLog.i(TAG, "Thermal status -> $status")
        _health.value = _health.value.copy(thermalStatus = status)
    }

    fun start() {
        power.addThermalStatusListener(listenerExecutor, thermalListener)
        context.registerComponentCallbacks(this)
        sample()
    }

    /** Samples memory/thermal headroom. Cheap; call at ~1 Hz at most. */
    fun sample(): SystemHealth {
        val mi = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(mi)
        val now = SystemClock.elapsedRealtime()
        // getThermalHeadroom is rate limited (~1 call/sec, NaN if called too often).
        if (now - lastHeadroomCallMs >= 2_000) {
            lastHeadroomCallMs = now
            val h = runCatching { power.getThermalHeadroom(10) }.getOrNull()
            lastHeadroom = h?.takeIf { !it.isNaN() } ?: lastHeadroom
        }
        val updated = _health.value.copy(
            thermalStatus = power.currentThermalStatus,
            thermalHeadroom = lastHeadroom,
            availMemBytes = mi.availMem,
            totalMemBytes = mi.totalMem,
            lowMemory = mi.lowMemory,
            appCpuTimeMs = Process.getElapsedCpuTime(),
            sampledAtElapsedMs = now,
        )
        _health.value = updated
        return updated
    }

    /** Expensive (tens of ms); use only for diagnostics, not in the recording loop. */
    fun samplePss(): Long? = runCatching {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        info.totalPss.toLong()
    }.getOrNull()?.also { _health.value = _health.value.copy(appPssKb = it) }

    fun isNetworkAvailable(): Boolean {
        val n = connectivity.activeNetwork ?: return false
        val caps = connectivity.getNetworkCapabilities(n) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    override fun onTrimMemory(level: Int) {
        _health.value = _health.value.copy(lastTrimLevel = level)
    }

    @Deprecated("Deprecated in Java")
    override fun onLowMemory() {
        _health.value = _health.value.copy(lowMemory = true)
    }

    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    companion object {
        private const val TAG = "Health"
    }
}
