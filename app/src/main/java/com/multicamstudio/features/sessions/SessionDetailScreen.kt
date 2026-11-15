package com.multicamstudio.features.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import com.multicamstudio.core.cloud.DriveClient
import com.multicamstudio.core.common.formatBytes
import com.multicamstudio.core.common.formatDurationMs
import com.multicamstudio.data.repo.DeletionPreview
import com.multicamstudio.domain.model.BackupState
import com.multicamstudio.domain.model.CameraRole
import com.multicamstudio.domain.model.FinalizationState
import com.multicamstudio.domain.model.LocalDeletionState
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun SessionDetailScreen(container: AppContainer, sessionId: String, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val detail by remember(sessionId) { container.sessions.observeSession(sessionId) }.collectAsStateWithLifecycle(null)
    val events by remember(sessionId) { container.sessions.observeEvents(sessionId) }.collectAsStateWithLifecycle(emptyList())
    var renaming by remember { mutableStateOf(false) }
    var deletePreview by remember { mutableStateOf<DeletionPreview?>(null) }

    val d = detail
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text(d?.session?.displayName ?: "Session", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (d != null) IconButton(onClick = { renaming = true }) { Icon(Icons.Filled.Edit, "Rename") }
        }
        if (d == null) {
            Text("Session not found")
            return@Column
        }
        val s = d.session
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("ID ${s.sessionId}", style = MaterialTheme.typography.bodySmall)
                Text(formatWhen(s.createdAtUtc, s.timezone) + " (${s.timezone})")
                Text("Duration ${s.durationMs.formatDurationMs()} · ${s.recordingState.name.lowercase()} · ${s.captureArchitecture}")
                s.stopReason?.let { Text("Stop reason: $it", style = MaterialTheme.typography.bodySmall) }
                Text("Local size: ${if (s.localDeletionState == LocalDeletionState.DELETED) "local copies deleted" else s.localStorageSize.formatBytes()}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Backup:")
                    BackupBadge(s.backupState)
                }
                s.remoteRenameError?.let { Text("$it (local name kept)", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                s.driveFolderId?.let { id -> TextButton(onClick = { openUrl(context, DriveClient.folderUrl(id)) }) { Text("Open Google Drive folder") } }
            }
        }

        CameraRole.entries.forEach { role ->
            val cam = d.cameras.firstOrNull { it.cameraType == role }
            val segs = d.segments.filter { it.recordingId.endsWith("_${role.dirName}") }.sortedBy { it.segmentIndex }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(role.label + " camera", fontWeight = FontWeight.SemiBold)
                    if (cam == null) {
                        Text("Not recorded", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text(
                            "${cam.status.name.lowercase()} · ${cam.resolution} · avg ${cam.actualFps?.let { "%.2f".format(it) } ?: "—"} fps · " +
                                "${cam.frameCount} frames · ${cam.droppedFrameCount} dropped",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text("Camera ${cam.cameraId}${cam.physicalCameraId?.let { ":$it" } ?: ""} · ${cam.codecName ?: cam.codec} · ${cam.bitrate / 1000} kbps · first frame at ${cam.syncOffsetMs?.let { "%.1f ms".format(it) } ?: "—"}", style = MaterialTheme.typography.bodySmall)
                    }
                    segs.forEach { seg ->
                        val upload = d.uploads.firstOrNull { it.assetId == seg.segmentId }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(File(seg.filePath).name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${seg.resolution} · ${seg.fileSize.formatBytes()} · ${seg.frameCount} fr · ${seg.droppedFrames} dropped · " +
                                        (if (seg.finalizationState == FinalizationState.FINALIZED) "" else seg.finalizationState.name.lowercase() + " · ") +
                                        (upload?.uploadState?.name?.lowercase() ?: "not queued"),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (seg.finalizationState == FinalizationState.FINALIZED && s.localDeletionState == LocalDeletionState.PRESENT) {
                                TextButton(onClick = {
                                    openLocalFile(context, File(seg.filePath), "video/mp4")?.let { msg -> scope.launch { snackbar.showSnackbar(msg) } }
                                }) { Text("Play") }
                            }
                        }
                    }
                }
            }
        }

        d.audio.firstOrNull()?.let { a ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Master audio", fontWeight = FontWeight.SemiBold)
                        Text("${a.codec} ${a.sampleRate / 1000} kHz ${if (a.channels == 1) "mono" else "${a.channels} ch"} · ${a.fileSize.formatBytes()} · ${a.finalizationState.name.lowercase()} · ${a.uploadState.name.lowercase()}", style = MaterialTheme.typography.bodySmall)
                        a.startTimestamp?.let { Text("First sample at %.1f ms".format(it / 1e6), style = MaterialTheme.typography.bodySmall) }
                    }
                    if (a.finalizationState == FinalizationState.FINALIZED && s.localDeletionState == LocalDeletionState.PRESENT) {
                        TextButton(onClick = { openLocalFile(context, File(a.filePath), "audio/mp4")?.let { msg -> scope.launch { snackbar.showSnackbar(msg) } } }) { Text("Play") }
                    }
                }
            }
        }

        if (events.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Events (resolution, thermal, cameras, sync)", fontWeight = FontWeight.SemiBold)
                    events.forEach { e ->
                        Text("%s  [%s] %s".format((e.sessionTimeMs.toLong().coerceAtLeast(0)).formatDurationMs(), e.type, e.message), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (s.localDeletionState == LocalDeletionState.PRESENT && s.backupState != BackupState.BACKED_UP) {
                OutlinedButton(onClick = {
                    scope.launch {
                        if (container.settings.current().driveAccountEmail == null) {
                            snackbar.showSnackbar("Connect Google Drive in Settings first"); return@launch
                        }
                        val bytes = container.sessions.prepareManualUpload(listOf(s.sessionId))
                        container.scheduler.scheduleManual(bytes)
                        snackbar.showSnackbar("Upload started")
                    }
                }) { Text("Upload now") }
            }
            if (s.backupState == BackupState.BACKED_UP && s.localDeletionState == LocalDeletionState.PRESENT) {
                OutlinedButton(onClick = { scope.launch { deletePreview = container.sessions.deletionPreview(s.sessionId) } }) {
                    Text("Delete local copies")
                }
            }
        }
    }

    if (renaming && d != null) {
        var name by remember { mutableStateOf(d.session.displayName) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename session") },
            text = {
                Column {
                    OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true)
                    Text("The session ID and local folder do not change. A backed-up session's Drive folder is renamed on the next upload run.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    renaming = false
                    scope.launch { container.sessions.rename(d.session.sessionId, name)?.let { snackbar.showSnackbar(it) } }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }

    deletePreview?.let { p ->
        DeleteLocalDialog(
            previews = listOf(p),
            onDismiss = { deletePreview = null },
            onConfirm = {
                deletePreview = null
                scope.launch { snackbar.showSnackbar(container.sessions.deleteLocalCopies(p.sessionId).message) }
            },
        )
    }
}

