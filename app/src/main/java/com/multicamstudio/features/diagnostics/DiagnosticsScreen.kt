package com.multicamstudio.features.diagnostics

import android.content.Intent
import android.os.PowerManager
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.multicamstudio.AppContainer
import com.multicamstudio.core.camera.CameraDiagnostics
import com.multicamstudio.core.camera.CameraOverrides
import com.multicamstudio.core.media.EncoderStressTest
import com.multicamstudio.domain.model.VideoCodecChoice
import com.multicamstudio.domain.model.VideoResolution
import com.multicamstudio.domain.session.BitratePolicy
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiagnosticsScreen(container: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf("Run a test to generate a report.\n\nThe live test opens cameras, so recording must be stopped.") }
    var progress by remember { mutableStateOf("") }
    var running by remember { mutableStateOf<Job?>(null) }
    var lastFile by remember { mutableStateOf<File?>(null) }
    val recording = container.engine.isRecording()

    DisposableEffect(Unit) { onDispose { running?.cancel() } }

    fun save(text: String, prefix: String): File {
        val f = File(container.storage.diagnosticsDir, "${prefix}_${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))}.txt")
        f.writeText(text)
        lastFile = f
        return f
    }

    fun runReport(live: Boolean) {
        running = scope.launch {
            container.engine.stopPreview().join()
            val s = container.settings.current()
            report = ""
            val text = runCatching {
                CameraDiagnostics(context, container.storage).run(live, CameraOverrides(s.cameraOverrideFront, s.cameraOverrideMain, s.cameraOverrideWide)) { progress = it }
            }.getOrElse { "Diagnostic failed: ${it.message}" }
            report = text
            progress = "Saved ${save(text, "camera_report").name}"
            running = null
        }
    }

    fun runStress(streams: Int, mime: String, seconds: Int) {
        running = scope.launch {
            val s = container.settings.current()
            val codec = if (mime == "video/hevc") VideoCodecChoice.HEVC else VideoCodecChoice.AVC
            val bitrate = BitratePolicy.estimate(VideoResolution.P1080, 30, codec, s.quality)
            val r = EncoderStressTest(context.getSystemService(PowerManager::class.java)).run(mime, streams, 1920, 1080, bitrate, seconds) { progress = it }
            report = r.summary()
            progress = "Saved ${save(report, "encoder_stress").name}"
            running = null
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Diagnostics", style = MaterialTheme.typography.titleLarge)
        }
        if (recording) Text("Stop recording to run diagnostics.", color = MaterialTheme.colorScheme.error)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val enabled = running == null && !recording
            OutlinedButton(onClick = { runReport(live = false) }, enabled = enabled) { Text("Capability report") }
            OutlinedButton(onClick = { runReport(live = true) }, enabled = enabled) { Text("Report + live 3-camera test") }
            OutlinedButton(onClick = { runStress(3, "video/hevc", 60) }, enabled = enabled) { Text("3× HEVC encoder 60 s") }
            OutlinedButton(onClick = { runStress(3, "video/hevc", 900) }, enabled = enabled) { Text("3× HEVC encoder 15 min") }
            OutlinedButton(onClick = { runStress(3, "video/avc", 60) }, enabled = enabled) { Text("3× H.264 encoder 60 s") }
            if (running != null) OutlinedButton(onClick = { running?.cancel(); running = null; progress = "Cancelled" }) { Text("Cancel") }
            lastFile?.let { f ->
                OutlinedButton(onClick = {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                            "Share report",
                        )
                    )
                }) { Text("Share report") }
            }
        }
        if (progress.isNotEmpty()) Text(progress, style = MaterialTheme.typography.bodySmall)
        SelectionContainer(Modifier.weight(1f).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
            Text(report, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
    }
}
