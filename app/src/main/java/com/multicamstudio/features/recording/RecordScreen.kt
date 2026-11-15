package com.multicamstudio.features.recording

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.multicamstudio.AppContainer
import com.multicamstudio.core.common.formatBytes
import com.multicamstudio.core.common.formatDurationMs
import com.multicamstudio.core.recording.CameraTelemetry
import com.multicamstudio.domain.model.CameraRole
import com.multicamstudio.domain.model.SessionPhase
import com.multicamstudio.domain.model.StreamStatus
import com.multicamstudio.domain.model.ThermalStatus
import com.multicamstudio.domain.thermal.CameraDecisionOption
import com.multicamstudio.services.RecordingForegroundService
import com.multicamstudio.ui.theme.OkGreen
import com.multicamstudio.ui.theme.RecordRed
import com.multicamstudio.ui.theme.WarnAmber
import kotlinx.coroutines.delay

@Composable
fun RecordScreen(container: AppContainer, deviceRotation: () -> Int, permissionsVersion: Int, requestPermissions: () -> Unit) {
    val context = LocalContext.current
    val state by container.engine.state.collectAsStateWithLifecycle()
    val cameraGranted = remember(permissionsVersion) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    // Preview lives only while this screen is visible; recording does not depend on it.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, cameraGranted) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> {
                    container.preview.setRenderingEnabled(true)
                    if (cameraGranted) container.engine.startPreview()
                }
                Lifecycle.Event.ON_STOP -> {
                    container.preview.setRenderingEnabled(false)
                    container.engine.stopPreview()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            container.preview.setRenderingEnabled(false)
            container.engine.stopPreview()
        }
    }

    val active = state.phase.isActive
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PhaseChip(state.phase)
            Spacer(Modifier.weight(1f))
            Text(
                if (active) state.elapsedMs.formatDurationMs() else "00:00",
                fontSize = 32.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
            )
        }
        if (!cameraGranted) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Camera and microphone permissions are required.")
                    TextButton(onClick = requestPermissions) { Text("Grant permissions") }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(CameraRole.FRONT, CameraRole.REAR_MAIN, CameraRole.REAR_WIDE).forEach { role ->
                PreviewTile(container, role, state.cameras[role], Modifier.weight(1f))
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Metric("Thermal", ThermalStatus.label(state.thermalStatus) + (state.thermalHeadroom?.let { " · headroom %.2f".format(it) } ?: ""),
                    if (state.thermalStatus >= ThermalStatus.SEVERE) RecordRed else if (state.thermalStatus >= ThermalStatus.MODERATE) WarnAmber else null)
                state.thermalWarning?.let { Text(it, color = WarnAmber, style = MaterialTheme.typography.bodySmall) }
                FreeStorage(container, state.freeBytes, state.minutesLeft)
                state.storageWarning?.let { Text(it, color = WarnAmber, style = MaterialTheme.typography.bodySmall) }
                Metric("Audio", state.audioStatus, null)
                state.maxPhaseMs?.let { Metric("Max camera frame-phase offset", "%.1f ms".format(it), null) }
                state.architecture?.let { Metric("Capture mode", it, null) }
                state.topologyNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                container.preview.status.collectAsStateWithLifecycle().value.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (state.errors.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Recent errors", fontWeight = FontWeight.SemiBold, color = RecordRed)
                    state.errors.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        if (!active && state.lastStopReason != null) {
            Text("Last session: ${state.lastStopReason}", style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Button(
                onClick = {
                    if (active) container.engine.stopRecording()
                    else if (cameraGranted) RecordingForegroundService.start(context, deviceRotation())
                    else requestPermissions()
                },
                enabled = state.phase != SessionPhase.FINALIZING && state.phase != SessionPhase.INITIALIZING,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = if (active) Color.DarkGray else RecordRed),
                modifier = Modifier.size(96.dp),
            ) {
                Text(if (active) "STOP" else "REC", fontWeight = FontWeight.Bold)
            }
        }
    }

    state.decision?.let { d ->
        ThermalDecisionDialog(d.reason, d.options, d.deadlineElapsedMs) { container.engine.applyDecision(it) }
    }
}

@Composable
private fun PhaseChip(phase: SessionPhase) {
    val (label, color) = when (phase) {
        SessionPhase.IDLE -> "Idle" to Color.Gray
        SessionPhase.PREVIEWING -> "Ready" to OkGreen
        SessionPhase.INITIALIZING -> "Initializing" to WarnAmber
        SessionPhase.RECORDING -> "● Recording" to RecordRed
        SessionPhase.RECORDING_DEGRADED -> "● Recording (degraded)" to WarnAmber
        SessionPhase.CAMERA_DISCONNECTED -> "Camera disconnected" to WarnAmber
        SessionPhase.WAITING_FOR_THERMAL_DECISION -> "Waiting for thermal decision" to RecordRed
        SessionPhase.FINALIZING -> "Finalizing" to WarnAmber
        SessionPhase.COMPLETED -> "Completed" to OkGreen
        SessionPhase.FAILED -> "Failed" to RecordRed
    }
    Text(
        label,
        color = Color.White,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color).padding(horizontal = 10.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelLarge,
    )
}

@Composable
private fun PreviewTile(container: AppContainer, role: CameraRole, t: CameraTelemetry?, modifier: Modifier) {
    // Each tile collects its own flow so a new preview frame recomposes only that tile.
    val frame by container.preview.frame(role).collectAsStateWithLifecycle()
    Column(modifier) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(9f / 16f).clip(RoundedCornerShape(8.dp)).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            val f = frame
            if (f != null) {
                Image(f.bitmap.asImageBitmap(), contentDescription = "${role.label} preview", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Text("No preview", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(role.label, fontWeight = FontWeight.SemiBold)
        val statusColor = when (t?.status) {
            StreamStatus.RECORDING -> OkGreen
            StreamStatus.DEGRADED, StreamStatus.DISCONNECTED, StreamStatus.STOPPED_THERMAL -> WarnAmber
            StreamStatus.FAILED -> RecordRed
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        Text(t?.status?.name?.lowercase()?.replace('_', ' ') ?: "—", color = statusColor, style = MaterialTheme.typography.bodySmall)
        Text(t?.resolution ?: "—", style = MaterialTheme.typography.bodySmall)
        Text(t?.fps?.let { "%.1f fps".format(it) } ?: "— fps", style = MaterialTheme.typography.bodySmall)
        Text("drops ${t?.dropped ?: 0}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Metric(label: String, value: String, color: Color?) {
    Row {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = color ?: MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun FreeStorage(container: AppContainer, recordingFree: Long, minutesLeft: Double?) {
    // Outside recording, refresh free space every 10 s (the engine updates it while recording).
    val free by produceState(recordingFree, recordingFree) {
        while (true) {
            value = if (recordingFree > 0) recordingFree else container.storage.freeBytes()
            delay(10_000)
        }
    }
    Metric("Free storage", free.formatBytes() + (minutesLeft?.let { " · ~${it.toInt()} min" } ?: ""), null)
}

@Composable
private fun ThermalDecisionDialog(reason: String, options: List<CameraDecisionOption>, deadline: Long, onChoose: (CameraDecisionOption) -> Unit) {
    val remaining by produceState(0L, deadline) {
        while (true) {
            value = ((deadline - android.os.SystemClock.elapsedRealtime()) / 1000).coerceAtLeast(0)
            delay(1_000)
        }
    }
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Phone is overheating") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("$reason. Recording continues at 720p30 while you decide. If you don't choose within ${remaining}s, your camera priority is applied.")
                options.forEach { o ->
                    TextButton(onClick = { onChoose(o) }, modifier = Modifier.fillMaxWidth()) {
                        Text(o.label, color = if (o is CameraDecisionOption.StopAll) RecordRed else MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
        confirmButton = {},
    )
}
