package com.multicamstudio.features.sessions

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.multicamstudio.core.common.formatBytes
import com.multicamstudio.data.repo.DeletionPreview
import com.multicamstudio.domain.model.BackupState
import com.multicamstudio.ui.theme.OkGreen
import com.multicamstudio.ui.theme.RecordRed
import com.multicamstudio.ui.theme.WarnAmber
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun BackupBadge(state: BackupState) {
    val (label, color) = when (state) {
        BackupState.LOCAL_ONLY -> "Local only" to Color.Gray
        BackupState.QUEUED -> "Queued" to Color(0xFF607D8B)
        BackupState.UPLOADING -> "Uploading" to Color(0xFF1E88E5)
        BackupState.PARTIALLY_UPLOADED -> "Partially uploaded" to WarnAmber
        BackupState.BACKUP_VERIFICATION -> "Verifying" to Color(0xFF1E88E5)
        BackupState.BACKED_UP -> "Backed up" to OkGreen
        BackupState.UPLOAD_FAILED -> "Upload failed" to RecordRed
        BackupState.CLOUD_ONLY -> "Cloud only" to Color(0xFF7E57C2)
    }
    Text(
        label, color = Color.White, style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

private val dateTimeFmt = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm:ss")

fun formatWhen(utcMs: Long, zone: String): String =
    Instant.ofEpochMilli(utcMs).atZone(runCatching { ZoneId.of(zone) }.getOrDefault(ZoneId.systemDefault())).format(dateTimeFmt)

/** Opens an original read-only via FileProvider; the file itself is never modified. */
fun openLocalFile(context: Context, file: File, mime: String): String? {
    if (!file.exists()) return "File not found"
    return try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        null
    } catch (e: ActivityNotFoundException) {
        "No app can open this file"
    } catch (e: Exception) {
        e.message
    }
}

fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** Confirmation for "Delete local copies" – never ambiguous with deleting the cloud copy. */
@Composable
fun DeleteLocalDialog(previews: List<DeletionPreview>, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val eligible = previews.filter { it.eligible }
    val blocked = previews.filterNot { it.eligible }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (previews.size == 1) "Delete local copies?" else "Delete local copies of ${eligible.size} sessions?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                eligible.forEach { p ->
                    Text("${p.displayName}: ${p.fileCount} files, ${p.bytes.formatBytes()}", style = MaterialTheme.typography.bodyMedium)
                }
                if (eligible.isNotEmpty()) {
                    Text("Storage released: ${eligible.sumOf { it.bytes }.formatBytes()}", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Every file is verified in Google Drive (size and checksum), and will be re-checked before deleting. " +
                            "The Google Drive copies are NOT deleted. The session stays listed as \"Cloud only\".",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                blocked.forEach { p ->
                    Text("Not eligible – ${p.displayName}: ${p.problems.firstOrNull() ?: "not fully backed up"}", color = RecordRed, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = eligible.isNotEmpty()) { Text("Delete local copies", color = RecordRed) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
