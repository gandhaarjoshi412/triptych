package com.multicamstudio.features.sessions

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.multicamstudio.AppContainer
import com.multicamstudio.core.common.formatBytes
import com.multicamstudio.core.common.formatDurationMs
import com.multicamstudio.data.local.SessionWithDetails
import com.multicamstudio.data.repo.DeletionPreview
import com.multicamstudio.domain.model.BackupState
import com.multicamstudio.domain.model.CameraRole
import com.multicamstudio.domain.model.LocalDeletionState
import kotlinx.coroutines.launch
import java.time.LocalDate

private enum class DateFilter(val label: String, val days: Long?) { ALL("All", null), TODAY("Today", 0), WEEK("7 days", 6), MONTH("30 days", 29) }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionsScreen(container: AppContainer, snackbar: SnackbarHostState, onOpen: (String) -> Unit) {
    val all by container.sessions.observeSessions().collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(DateFilter.ALL) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var deletePreviews by remember { mutableStateOf<List<DeletionPreview>?>(null) }

    val today = LocalDate.now()
    val visible = all.filter { d ->
        (query.isBlank() || d.session.displayName.contains(query, ignoreCase = true)) &&
            (filter.days == null || runCatching { !LocalDate.parse(d.session.localDate).isBefore(today.minusDays(filter.days!!)) }.getOrDefault(true))
    }

    fun upload(ids: List<String>) = scope.launch {
        if (container.settings.current().driveAccountEmail == null) {
            snackbar.showSnackbar("Connect Google Drive in Settings first")
            return@launch
        }
        if (container.engine.isRecording()) {
            snackbar.showSnackbar("Uploads are not started while recording")
            return@launch
        }
        val bytes = container.sessions.prepareManualUpload(ids)
        container.scheduler.scheduleManual(bytes)
        selected = emptySet()
        snackbar.showSnackbar("Uploading ${ids.size} session(s), ${bytes.formatBytes()} remaining")
    }

    fun askDelete(ids: List<String>) = scope.launch {
        deletePreviews = ids.map { container.sessions.deletionPreview(it) }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, null) }, placeholder = { Text("Search sessions") },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
            DateFilter.entries.forEach { f -> FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) }) }
        }
        if (selected.isNotEmpty()) {
            val selectedSessions = all.filter { it.session.sessionId in selected }
            val deletable = selectedSessions.filter { it.session.backupState == BackupState.BACKED_UP && it.session.localDeletionState == LocalDeletionState.PRESENT }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${selected.size} selected", Modifier.weight(1f))
                OutlinedButton(onClick = { upload(selected.toList()) }) { Text("Upload") }
                OutlinedButton(onClick = { askDelete(deletable.map { it.session.sessionId }) }, enabled = deletable.isNotEmpty()) {
                    Text("Delete local copies (${deletable.size})")
                }
                TextButton(onClick = { selected = emptySet() }) { Text("Clear") }
            }
        }
        if (visible.isEmpty()) {
            Text("No sessions yet.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
            items(visible, key = { it.session.sessionId }) { d ->
                SessionCard(
                    d = d,
                    selected = d.session.sessionId in selected,
                    selectionMode = selected.isNotEmpty(),
                    onClick = {
                        if (selected.isNotEmpty()) selected = selected.toggle(d.session.sessionId) else onOpen(d.session.sessionId)
                    },
                    onLongClick = { selected = selected.toggle(d.session.sessionId) },
                    onUpload = { upload(listOf(d.session.sessionId)) },
                    onDeleteLocal = { askDelete(listOf(d.session.sessionId)) },
                )
            }
        }
    }

    deletePreviews?.let { previews ->
        DeleteLocalDialog(
            previews = previews,
            onDismiss = { deletePreviews = null },
            onConfirm = {
                deletePreviews = null
                scope.launch {
                    val results = previews.filter { it.eligible }.map { container.sessions.deleteLocalCopies(it.sessionId) }
                    selected = emptySet()
                    val freed = results.sumOf { it.freedBytes }
                    val failed = results.filterNot { it.deleted }
                    snackbar.showSnackbar(
                        if (failed.isEmpty()) "Deleted local copies, released ${freed.formatBytes()}" else failed.joinToString("\n") { it.message }
                    )
                }
            },
        )
    }
}

private fun Set<String>.toggle(id: String) = if (id in this) this - id else this + id

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionCard(
    d: SessionWithDetails,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onUpload: () -> Unit,
    onDeleteLocal: () -> Unit,
) {
    val s = d.session
    Card(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = if (selected) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else CardDefaults.cardColors(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selectionMode) {
                Checkbox(checked = selected, onCheckedChange = { onClick() })
                Spacer(Modifier.width(4.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(s.displayName, fontWeight = FontWeight.SemiBold)
                Text(formatWhen(s.createdAtUtc, s.timezone) + " · " + s.durationMs.formatDurationMs(), style = MaterialTheme.typography.bodySmall)
                val recorded = s.camerasRecorded.split(",").filter { it.isNotBlank() }.toSet()
                Text(
                    CameraRole.entries.joinToString("  ") { r -> (if (r.name in recorded) "✓ " else "✗ ") + r.label } +
                        "  ${if (s.hasAudio) "✓" else "✗"} Audio",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    BackupBadge(s.backupState)
                    Text(if (s.localDeletionState == LocalDeletionState.DELETED) "no local files" else s.localStorageSize.formatBytes(), style = MaterialTheme.typography.bodySmall)
                    if (s.recordingState.name != "COMPLETED") Text(s.recordingState.name.lowercase(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            if (!selectionMode) {
                Column(horizontalAlignment = Alignment.End) {
                    if (s.localDeletionState == LocalDeletionState.PRESENT && s.backupState != BackupState.BACKED_UP) {
                        IconButton(onClick = onUpload) { Icon(Icons.Filled.CloudUpload, contentDescription = "Upload") }
                    }
                    if (s.backupState == BackupState.BACKED_UP && s.localDeletionState == LocalDeletionState.PRESENT) {
                        TextButton(onClick = onDeleteLocal) { Text("Delete local copies", style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
        }
    }
}
