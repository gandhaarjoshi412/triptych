package com.multicamstudio.features.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.multicamstudio.AppContainer
import com.multicamstudio.BuildConfig
import com.multicamstudio.core.cloud.AuthStep
import com.multicamstudio.core.cloud.DriveClient
import com.multicamstudio.core.common.formatBytes
import com.multicamstudio.data.prefs.AppSettings
import com.multicamstudio.domain.model.QualityPreset
import com.multicamstudio.domain.model.ThermalStatus
import com.multicamstudio.domain.model.VideoCodecChoice
import com.multicamstudio.features.sessions.openUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(container: AppContainer, snackbar: SnackbarHostState, onDiagnostics: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by container.settings.settings.collectAsStateWithLifecycle(AppSettings())
    fun update(t: (AppSettings) -> AppSettings) = scope.launch { container.settings.update(t) }

    suspend fun completeAuth(step: AuthStep) {
        when (step) {
            is AuthStep.Authorized -> {
                val email = runCatching { container.drive.aboutEmail(step.accessToken) }.getOrNull()
                container.settings.update { it.copy(driveAccountEmail = email ?: "Connected account") }
                snackbar.showSnackbar("Google Drive connected")
            }
            is AuthStep.Failed -> snackbar.showSnackbar("Google sign-in failed: ${step.message}")
            is AuthStep.NeedsUserInteraction -> snackbar.showSnackbar("Additional consent required")
        }
    }

    val authLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        scope.launch { completeAuth(container.auth.resultFromIntent(result.data)) }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Section("Google Drive") {
            Text(s.driveAccountEmail?.let { "Connected: $it" } ?: "Not connected")
            Text("Access is limited to files and folders this app creates (drive.file scope).", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    scope.launch {
                        when (val step = container.auth.authorize()) {
                            is AuthStep.NeedsUserInteraction -> authLauncher.launch(IntentSenderRequest.Builder(step.pendingIntent.intentSender).build())
                            else -> completeAuth(step)
                        }
                    }
                }) { Text(if (s.driveAccountEmail == null) "Connect" else "Reconnect") }
                if (s.driveAccountEmail != null) {
                    TextButton(onClick = {
                        scope.launch {
                            container.scheduler.cancelAuto()
                            container.scheduler.cancelManual()
                            container.auth.accessTokenSilently()?.let { container.auth.invalidate(it) }
                            container.settings.update { it.copy(driveAccountEmail = null) }
                            snackbar.showSnackbar("Disconnected. To fully revoke access, remove MultiCam Studio at myaccount.google.com/permissions")
                        }
                    }) { Text("Disconnect") }
                }
            }
            TextButton(onClick = { openUrl(context, "https://myaccount.google.com/permissions") }) { Text("Manage Google account access") }
        }

        Section("Destination folder") {
            var name by remember(s.driveRootFolderName) { mutableStateOf(s.driveRootFolderName) }
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Root folder name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Created by the app in My Drive on first upload, then tracked by ID – you can rename or move it in Drive later. Layout: root / date / session / camera.", style = MaterialTheme.typography.bodySmall)
            val rootId by produceState<String?>(null) { value = withContext(Dispatchers.IO) { container.db.driveFolders().get("root")?.driveId } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { update { it.copy(driveRootFolderName = name.trim()) } }) { Text("Save name") }
                rootId?.let { id -> TextButton(onClick = { openUrl(context, DriveClient.folderUrl(id)) }) { Text("Open in Drive") } }
                TextButton(onClick = {
                    scope.launch {
                        container.db.driveFolders().delete("root")
                        snackbar.showSnackbar("Future uploads will create a new root folder")
                    }
                }) { Text("Use new root folder") }
            }
        }

        Section("Camera priority (thermal emergencies)") {
            s.cameraPriority.forEachIndexed { i, role ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}. ${role.label}", Modifier.weight(1f))
                    IconButton(onClick = { update { it.copy(cameraPriority = it.cameraPriority.swap(i, i - 1)) } }, enabled = i > 0) { Icon(Icons.Filled.KeyboardArrowUp, "Up") }
                    IconButton(onClick = { update { it.copy(cameraPriority = it.cameraPriority.swap(i, i + 1)) } }, enabled = i < s.cameraPriority.lastIndex) { Icon(Icons.Filled.KeyboardArrowDown, "Down") }
                }
            }
            Text("When the phone is still overloaded at 720p and you don't answer, the lowest-priority camera is stopped first.", style = MaterialTheme.typography.bodySmall)
        }

        Section("Video") {
            Text("Codec")
            Chips(VideoCodecChoice.entries, s.codec, { if (it == VideoCodecChoice.HEVC) "H.265 / HEVC (default)" else "H.264" }) { c -> update { it.copy(codec = c) } }
            Text("Quality")
            Chips(QualityPreset.entries, s.quality, { it.name.lowercase().replace('_', ' ') }) { q -> update { it.copy(quality = q) } }
            Text("1080p30 target; thermal fallback is 720p30 (never lower, frame rate never reduced). Hardware encoders only.", style = MaterialTheme.typography.bodySmall)
            Text("Segment length")
            Chips(listOf(1, 5, 10), s.segmentMinutes, { "$it min" }) { m -> update { it.copy(segmentMinutes = m) } }
        }

        Section("Thermal behaviour") {
            Text("Time to answer the camera dialog before the priority policy applies")
            Chips(listOf(30, 60, 90, 180), s.thermalDecisionTimeoutSec, { "${it}s" }) { v -> update { it.copy(thermalDecisionTimeoutSec = v) } }
        }

        Section("Preview") {
            Toggle("Show camera previews", s.previewEnabled) { v -> update { it.copy(previewEnabled = v) } }
            Text("Preview rate")
            Chips(listOf(2, 5, 10), s.previewFps, { "$it fps" }) { v -> update { it.copy(previewFps = v) } }
            Text("Preview size")
            Chips(listOf(240, 320, 480), s.previewWidth, { "${it}px" }) { v -> update { it.copy(previewWidth = v) }; container.engine.invalidateTopology() }
        }

        Section("Automatic upload") {
            Toggle("Back up automatically", s.autoUploadEnabled) { v -> update { it.copy(autoUploadEnabled = v) } }
            Text("Start after the app has been in the background for")
            Chips(listOf(5, 10, 15, 30), s.autoUploadDelayMin, { "$it min" }) { v -> update { it.copy(autoUploadDelayMin = v) } }
            Text("Pause automatic upload when the phone is at least")
            Chips(listOf(ThermalStatus.LIGHT, ThermalStatus.MODERATE, ThermalStatus.SEVERE), s.uploadPauseThermalStatus, { ThermalStatus.label(it) }) { v -> update { it.copy(uploadPauseThermalStatus = v) } }
            Text("Pause when available memory is below")
            Chips(listOf(5, 10, 15, 20), s.uploadMinFreeMemoryPercent, { "$it%" }) { v -> update { it.copy(uploadMinFreeMemoryPercent = v) } }
            Text(
                "Android does not let apps see how much CPU other apps use. Uploads therefore pause on measurable signals: device temperature status, system memory pressure and recording state.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Section("Session naming") {
            var prefix by remember(s.sessionNamePrefix) { mutableStateOf(s.sessionNamePrefix) }
            OutlinedTextField(value = prefix, onValueChange = { prefix = it }, label = { Text("Optional name prefix (e.g. Business Shoot)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { update { it.copy(sessionNamePrefix = prefix.trim()) } }) { Text("Save") }
            Text("Sessions are named YYYY-MM-DD_HH-mm-ss (with the prefix if set). Rename any session later.", style = MaterialTheme.typography.bodySmall)
        }

        Section("Storage") {
            val stats by produceState(Triple(0L, 0L, 0L)) {
                value = withContext(Dispatchers.IO) {
                    Triple(container.storage.freeBytes(), container.storage.totalBytes(), container.storage.sizeOf(container.storage.root))
                }
            }
            Text("Free: ${stats.first.formatBytes()} of ${stats.second.formatBytes()}")
            Text("Recordings on this phone: ${stats.third.formatBytes()}")
            Text("Warn below")
            Chips(listOf(3, 5, 10), s.storageWarnGb, { "$it GB" }) { v -> update { it.copy(storageWarnGb = v) } }
            Text("Stop recording safely below")
            Chips(listOf(1, 2, 3), s.storageStopGb, { "$it GB" }) { v -> update { it.copy(storageStopGb = v) } }
            Text("Originals are never deleted automatically.", style = MaterialTheme.typography.bodySmall)
        }

        Section("Camera mapping (advanced)") {
            var f by remember(s.cameraOverrideFront) { mutableStateOf(s.cameraOverrideFront) }
            var m by remember(s.cameraOverrideMain) { mutableStateOf(s.cameraOverrideMain) }
            var w by remember(s.cameraOverrideWide) { mutableStateOf(s.cameraOverrideWide) }
            Text("Leave empty for automatic detection. Use camera ids from the diagnostic report.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(f, { f = it }, label = { Text("Front camera id") }, singleLine = true)
            OutlinedTextField(m, { m = it }, label = { Text("Main camera id") }, singleLine = true)
            OutlinedTextField(w, { w = it }, label = { Text("Ultra-wide camera id") }, singleLine = true)
            OutlinedButton(onClick = {
                update { it.copy(cameraOverrideFront = f.trim(), cameraOverrideMain = m.trim(), cameraOverrideWide = w.trim()) }
                container.engine.invalidateTopology()
            }) { Text("Apply") }
        }

        Section("Diagnostics") {
            Text("Camera capability report, live three-camera test and encoder stress test.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onDiagnostics) { Text("Open diagnostics") }
        }

        Section("About") {
            Text("MultiCam Studio ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            Text("${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}) · ${android.os.Build.DISPLAY}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun <T> List<T>.swap(i: Int, j: Int): List<T> {
    if (i !in indices || j !in indices) return this
    val m = toMutableList()
    val t = m[i]; m[i] = m[j]; m[j] = t
    return m
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Chips(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o -> FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) }) }
    }
}
