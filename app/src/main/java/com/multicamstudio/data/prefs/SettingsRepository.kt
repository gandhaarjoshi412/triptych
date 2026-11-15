package com.multicamstudio.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.multicamstudio.domain.model.CameraRole
import com.multicamstudio.domain.model.QualityPreset
import com.multicamstudio.domain.model.ThermalStatus
import com.multicamstudio.domain.model.VideoCodecChoice
import com.multicamstudio.domain.thermal.CameraPriority
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class AppSettings(
    val codec: VideoCodecChoice = VideoCodecChoice.HEVC,
    val quality: QualityPreset = QualityPreset.AUTO,
    val cameraPriority: List<CameraRole> = CameraPriority.DEFAULT,
    val previewEnabled: Boolean = true,
    val previewFps: Int = 5,
    val previewWidth: Int = 320,
    val segmentMinutes: Int = 5,
    /** Seconds to wait for the user's camera choice before applying the priority policy. */
    val thermalDecisionTimeoutSec: Int = 90,
    val autoUploadEnabled: Boolean = true,
    val autoUploadDelayMin: Int = 5,
    val uploadPauseThermalStatus: Int = ThermalStatus.MODERATE,
    val uploadMinFreeMemoryPercent: Int = 10,
    val sessionNamePrefix: String = "",
    val driveAccountEmail: String? = null,
    val driveRootFolderName: String = "MultiCam Studio",
    val storageWarnGb: Int = 5,
    val storageStopGb: Int = 2,
    /** Optional manual camera id overrides, e.g. "0" or "0:2" (logical:physical). Empty = auto. */
    val cameraOverrideFront: String = "",
    val cameraOverrideMain: String = "",
    val cameraOverrideWide: String = "",
)

class SettingsRepository(private val context: Context) {
    private object K {
        val codec = stringPreferencesKey("codec")
        val quality = stringPreferencesKey("quality")
        val priority = stringPreferencesKey("camera_priority")
        val previewEnabled = booleanPreferencesKey("preview_enabled")
        val previewFps = intPreferencesKey("preview_fps")
        val previewWidth = intPreferencesKey("preview_width")
        val segmentMinutes = intPreferencesKey("segment_minutes")
        val thermalTimeout = intPreferencesKey("thermal_timeout")
        val autoUpload = booleanPreferencesKey("auto_upload")
        val autoUploadDelay = intPreferencesKey("auto_upload_delay")
        val uploadThermal = intPreferencesKey("upload_thermal")
        val uploadMem = intPreferencesKey("upload_mem")
        val namePrefix = stringPreferencesKey("name_prefix")
        val driveEmail = stringPreferencesKey("drive_email")
        val driveRootName = stringPreferencesKey("drive_root_name")
        val storageWarn = intPreferencesKey("storage_warn")
        val storageStop = intPreferencesKey("storage_stop")
        val ovFront = stringPreferencesKey("ov_front")
        val ovMain = stringPreferencesKey("ov_main")
        val ovWide = stringPreferencesKey("ov_wide")
        val backgroundSince = longPreferencesKey("background_since")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p -> readFrom(p, AppSettings()).sanitized() }

    private fun AppSettings.sanitized() = copy(
        previewFps = previewFps.coerceIn(1, 15),
        previewWidth = previewWidth.coerceIn(160, 640),
        segmentMinutes = segmentMinutes.coerceIn(1, 30),
        thermalDecisionTimeoutSec = thermalDecisionTimeoutSec.coerceIn(15, 600),
        autoUploadDelayMin = autoUploadDelayMin.coerceAtLeast(5),
        uploadPauseThermalStatus = uploadPauseThermalStatus.coerceIn(ThermalStatus.LIGHT, ThermalStatus.CRITICAL),
        uploadMinFreeMemoryPercent = uploadMinFreeMemoryPercent.coerceIn(0, 50),
        driveRootFolderName = driveRootFolderName.ifBlank { AppSettings().driveRootFolderName },
    )

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { p ->
            val now = transform(readFrom(p, AppSettings()))
            p[K.codec] = now.codec.name
            p[K.quality] = now.quality.name
            p[K.priority] = CameraPriority.serialize(now.cameraPriority)
            p[K.previewEnabled] = now.previewEnabled
            p[K.previewFps] = now.previewFps
            p[K.previewWidth] = now.previewWidth
            p[K.segmentMinutes] = now.segmentMinutes
            p[K.thermalTimeout] = now.thermalDecisionTimeoutSec
            p[K.autoUpload] = now.autoUploadEnabled
            p[K.autoUploadDelay] = now.autoUploadDelayMin
            p[K.uploadThermal] = now.uploadPauseThermalStatus
            p[K.uploadMem] = now.uploadMinFreeMemoryPercent
            p[K.namePrefix] = now.sessionNamePrefix
            if (now.driveAccountEmail != null) p[K.driveEmail] = now.driveAccountEmail else p.remove(K.driveEmail)
            p[K.driveRootName] = now.driveRootFolderName
            p[K.storageWarn] = now.storageWarnGb
            p[K.storageStop] = now.storageStopGb
            p[K.ovFront] = now.cameraOverrideFront
            p[K.ovMain] = now.cameraOverrideMain
            p[K.ovWide] = now.cameraOverrideWide
        }
    }

    private fun readFrom(p: Preferences, d: AppSettings) = AppSettings(
        codec = p[K.codec]?.let { runCatching { VideoCodecChoice.valueOf(it) }.getOrNull() } ?: d.codec,
        quality = p[K.quality]?.let { runCatching { QualityPreset.valueOf(it) }.getOrNull() } ?: d.quality,
        cameraPriority = CameraPriority.parse(p[K.priority]),
        previewEnabled = p[K.previewEnabled] ?: d.previewEnabled,
        previewFps = p[K.previewFps] ?: d.previewFps,
        previewWidth = p[K.previewWidth] ?: d.previewWidth,
        segmentMinutes = p[K.segmentMinutes] ?: d.segmentMinutes,
        thermalDecisionTimeoutSec = p[K.thermalTimeout] ?: d.thermalDecisionTimeoutSec,
        autoUploadEnabled = p[K.autoUpload] ?: d.autoUploadEnabled,
        autoUploadDelayMin = p[K.autoUploadDelay] ?: d.autoUploadDelayMin,
        uploadPauseThermalStatus = p[K.uploadThermal] ?: d.uploadPauseThermalStatus,
        uploadMinFreeMemoryPercent = p[K.uploadMem] ?: d.uploadMinFreeMemoryPercent,
        sessionNamePrefix = p[K.namePrefix] ?: d.sessionNamePrefix,
        driveAccountEmail = p[K.driveEmail],
        driveRootFolderName = p[K.driveRootName] ?: d.driveRootFolderName,
        storageWarnGb = p[K.storageWarn] ?: d.storageWarnGb,
        storageStopGb = p[K.storageStop] ?: d.storageStopGb,
        cameraOverrideFront = p[K.ovFront] ?: "",
        cameraOverrideMain = p[K.ovMain] ?: "",
        cameraOverrideWide = p[K.ovWide] ?: "",
    )

    /** Wall-clock ms when the app last moved to the background (persisted so a fresh worker process can read it). */
    val backgroundSince: Flow<Long?> = context.dataStore.data.map { it[K.backgroundSince] }

    suspend fun setBackgroundSince(ms: Long?) {
        context.dataStore.edit { if (ms == null) it.remove(K.backgroundSince) else it[K.backgroundSince] = ms }
    }
}
