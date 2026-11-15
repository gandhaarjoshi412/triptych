package com.multicamstudio.features.uploads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.multicamstudio.AppContainer
import com.multicamstudio.core.common.formatBytes
import com.multicamstudio.data.local.UploadTaskEntity
import com.multicamstudio.domain.model.UploadState
import com.multicamstudio.ui.theme.RecordRed
import kotlinx.coroutines.launch

@Composable
fun UploadsScreen(container: AppContainer, snackbar: SnackbarHostState) {
    val tasks by container.sessions.observeUploadTasks().collectAsStateWithLifecycle(emptyList())
    val progress by container.uploadEngine.progress.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val active = tasks.filter { it.uploadState == UploadState.IN_PROGRESS || it.uploadState == UploadState.UPLOADED_UNVERIFIED }
    val pending = tasks.filter { it.uploadState == UploadState.PENDING }
    val paused = tasks.filter { it.uploadState == UploadState.PAUSED }
    val failed = tasks.filter { it.uploadState == UploadState.FAILED }
    val done = tasks.filter { it.uploadState == UploadState.VERIFIED }.take(50)
    val notQueued = tasks.count { it.uploadState == UploadState.NOT_QUEUED }

    fun startManual(ids: List<String>) = scope.launch {
        when {
            container.settings.current().driveAccountEmail == null -> snackbar.showSnackbar("Connect Google Drive in Settings first")
            container.engine.isRecording() -> snackbar.showSnackbar("Uploads are not started while recording")
            ids.isEmpty() -> snackbar.showSnackbar("Nothing to upload")
            else -> {
                val bytes = container.sessions.prepareManualUpload(ids)
                container.scheduler.scheduleManual(bytes)
                snackbar.showSnackbar("Uploading ${ids.size} session(s), ${bytes.formatBytes()}")
            }
        }
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (progress.running) "Uploading (${progress.mode?.name?.lowercase()})" else "Uploader idle", fontWeight = FontWeight.SemiBold)
                    Text(progress.status, style = MaterialTheme.typography.bodySmall)
                    progress.currentFile?.let { f ->
                        Text(f, style = MaterialTheme.typography.bodyMedium)
                        if (progress.currentTotal > 0) {
                            LinearProgressIndicator(progress = { progress.currentBytes.toFloat() / progress.currentTotal }, modifier = Modifier.fillMaxWidth())
                            Text("${progress.currentBytes.formatBytes()} / ${progress.currentTotal.formatBytes()}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text("This run: ${progress.completedThisRun} completed, ${progress.failedThisRun} failed", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Automatic backup starts after the app has been in the background for 5 minutes, never while recording, and pauses when you return, when the phone is warm or memory is low. Wi-Fi and mobile data are both used.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { scope.launch { startManual(container.sessions.pendingSessionIds()) } }) { Text("Upload all pending") }
                OutlinedButton(onClick = {
                    scope.launch {
                        container.sessions.retryFailed()
                        startManual(failed.map { it.sessionId }.distinct())
                    }
                }, enabled = failed.isNotEmpty()) { Text("Retry failed") }
                TextButton(onClick = { container.scheduler.cancelManual(); scope.launch { snackbar.showSnackbar("Manual upload cancelled; progress kept") } }) {
                    Text("Cancel")
                }
            }
            if (notQueued > 0) Text("$notQueued file(s) waiting for a manual upload (automatic upload is off).", style = MaterialTheme.typography.bodySmall)
        }
        section("Active", active)
        section("Pending", pending)
        section("Paused", paused)
        section("Failed", failed)
        section("Completed (verified)", done)
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(title: String, list: List<UploadTaskEntity>) {
    if (list.isEmpty()) return
    item { Text("$title (${list.size})", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp)) }
    items(list, key = { it.taskId }) { t -> TaskRow(t) }
}

@Composable
private fun TaskRow(t: UploadTaskEntity) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text("${t.sessionId} / ${t.remoteSubfolder}/${t.fileName}", style = MaterialTheme.typography.bodyMedium)
        val pct = if (t.totalBytes > 0) t.bytesUploaded * 100 / t.totalBytes else 0
        Text(
            "${t.uploadState.name.lowercase()} · ${t.bytesUploaded.formatBytes()} / ${t.totalBytes.formatBytes()} ($pct%)" +
                (if (t.retryCount > 0) " · retries ${t.retryCount}" else "") + (if (t.manualRequested) " · manual" else ""),
            style = MaterialTheme.typography.bodySmall,
        )
        t.lastError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = RecordRed) }
    }
}
