package com.multicamstudio.core.storage

import android.content.Context
import android.os.StatFs
import com.multicamstudio.domain.model.CameraRole
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * App-specific persistent storage for original recordings: files/recordings/<date>/<session>/...
 * Originals are never moved, replaced or deleted except by the explicit "Delete local copies" action.
 */
class SessionStorage(context: Context) {
    val root: File = File(context.filesDir, "recordings").apply { mkdirs() }
    val diagnosticsDir: File = File(context.filesDir, "diagnostics").apply { mkdirs() }

    fun sessionDir(relativeDir: String): File = File(root, relativeDir)
    fun cameraDir(relativeDir: String, role: CameraRole): File = File(sessionDir(relativeDir), role.dirName)
    fun audioDir(relativeDir: String): File = File(sessionDir(relativeDir), "audio")
    fun metadataDir(relativeDir: String): File = File(sessionDir(relativeDir), "metadata")
    fun thumbnailsDir(relativeDir: String): File = File(sessionDir(relativeDir), "thumbnails")
    fun manifestFile(relativeDir: String): File = File(metadataDir(relativeDir), MANIFEST_NAME)

    fun createSessionDirs(relativeDir: String, roles: Collection<CameraRole>) {
        roles.forEach { cameraDir(relativeDir, it).mkdirs() }
        audioDir(relativeDir).mkdirs()
        metadataDir(relativeDir).mkdirs()
        thumbnailsDir(relativeDir).mkdirs()
    }

    fun freeBytes(): Long = StatFs(root.absolutePath).availableBytes
    fun totalBytes(): Long = StatFs(root.absolutePath).totalBytes

    fun sizeOf(dir: File): Long = if (!dir.exists()) 0 else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Media files that "Delete local copies" removes. Manifest is retained (it is tiny and documents the session). */
    fun deletableMedia(relativeDir: String): List<File> {
        val dir = sessionDir(relativeDir)
        if (!dir.exists()) return emptyList()
        return dir.walkTopDown().filter { f ->
            f.isFile && f.parentFile?.name != "metadata"
        }.toList()
    }

    companion object {
        const val MANIFEST_NAME = "session_manifest.json"
        const val IN_PROGRESS_SUFFIX = ".inprogress"
        const val AUDIO_NAME = "master_audio.m4a"

        fun md5Hex(file: File): String {
            val md = MessageDigest.getInstance("MD5")
            FileInputStream(file).use { input ->
                val buf = ByteArray(1 shl 20)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
