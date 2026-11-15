package com.multicamstudio.core.common

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import java.io.File
import java.util.concurrent.Executor

object AppLog {
    private const val PREFIX = "MCS/"
    fun d(tag: String, msg: String) = Log.d(PREFIX + tag, msg)
    fun i(tag: String, msg: String) = Log.i(PREFIX + tag, msg)
    fun w(tag: String, msg: String, t: Throwable? = null) = Log.w(PREFIX + tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = Log.e(PREFIX + tag, msg, t)
}

/** A started HandlerThread with a Handler and an Executor bound to it. */
class LooperThread(name: String, priority: Int = Process.THREAD_PRIORITY_DEFAULT) {
    private val thread = HandlerThread(name, priority).apply { start() }
    val handler = Handler(thread.looper)
    val executor = Executor { handler.post(it) }

    fun quit() {
        thread.quitSafely()
    }
}

/** Atomic text write: tmp file + rename so a crash never leaves a half-written file. */
fun File.writeTextAtomically(text: String) {
    parentFile?.mkdirs()
    val tmp = File(parentFile, "$name.tmp")
    tmp.outputStream().use { out ->
        out.write(text.toByteArray(Charsets.UTF_8))
        out.fd.sync()
    }
    if (!tmp.renameTo(this)) {
        // renameTo fails on some filesystems if target exists.
        delete()
        check(tmp.renameTo(this)) { "Unable to replace $this" }
    }
}

fun Long.formatBytes(): String {
    val b = this.toDouble()
    return when {
        b >= 1e9 -> "%.2f GB".format(b / 1e9)
        b >= 1e6 -> "%.1f MB".format(b / 1e6)
        b >= 1e3 -> "%.0f KB".format(b / 1e3)
        else -> "$this B"
    }
}

fun Long.formatDurationMs(): String {
    val totalSec = this / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
