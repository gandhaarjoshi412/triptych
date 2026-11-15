package com.multicamstudio.core.camera

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.util.Range
import android.view.Surface
import com.multicamstudio.core.common.AppLog
import com.multicamstudio.core.common.LooperThread
import com.multicamstudio.domain.model.CameraRole
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class OutputKind { ENCODER, PREVIEW }

/** One surface the camera should write into. */
data class StreamOutput(val role: CameraRole, val physicalId: String?, val surface: Surface, val kind: OutputKind)

class CameraOpenException(message: String, val errorCode: Int? = null) : Exception(message)

/** Callbacks from an open camera device. All are delivered on the camera thread. */
interface CameraDeviceListener {
    fun onDisconnected(deviceId: String)
    fun onError(deviceId: String, error: Int)
    fun onBufferLost(deviceId: String, role: CameraRole?, frameNumber: Long) {}
    fun onCaptureFailed(deviceId: String, reason: Int) {}
}

/**
 * Owns one CameraDevice and its current capture session.
 * Opening and configuring are suspend functions; everything Camera2-related runs on [thread].
 */
class CameraDeviceHandle private constructor(
    val deviceId: String,
    val device: CameraDevice,
    private val thread: LooperThread,
) {
    var session: CameraCaptureSession? = null
        private set
    private var outputs: List<StreamOutput> = emptyList()
    var closed = false
        private set

    /**
     * Builds the SessionConfiguration for [outs]. Encoder outputs request the REALTIME (CLOCK_BOOTTIME)
     * timestamp base on API 33+ so every stream shares the session reference clock.
     */
    fun buildSessionConfig(outs: List<StreamOutput>, fps: Range<Int>, callback: CameraCaptureSession.StateCallback): SessionConfiguration {
        val configs = outs.map { o -> outputConfiguration(o) }
        val cfg = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, configs, thread.executor, callback)
        cfg.sessionParameters = buildRequest(outs, fps).build()
        return cfg
    }

    private fun buildRequest(outs: List<StreamOutput>, fps: Range<Int>): CaptureRequest.Builder {
        val template = if (outs.any { it.kind == OutputKind.ENCODER }) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
        return device.createCaptureRequest(template).apply {
            outs.forEach { addTarget(it.surface) }
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            // Stabilisation adds processing load and crops; off by default for a three-stream workload.
            set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        }
    }

    suspend fun configure(outs: List<StreamOutput>, fps: Range<Int>, listener: CameraDeviceListener): CameraCaptureSession {
        check(!closed) { "Device $deviceId closed" }
        val newSession = withTimeout(10_000) {
            suspendCancellableCoroutine { cont ->
                val cb = object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        if (cont.isActive) cont.resume(s)
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        if (cont.isActive) cont.resumeWithException(CameraOpenException("Session configuration failed on $deviceId for ${outs.size} outputs"))
                    }
                }
                try {
                    device.createCaptureSession(buildSessionConfig(outs, fps, cb))
                } catch (e: Exception) {
                    if (cont.isActive) cont.resumeWithException(CameraOpenException("createCaptureSession($deviceId): ${e.message}"))
                }
            }
        }
        session = newSession
        outputs = outs
        val request = buildRequest(outs, fps).build()
        newSession.setRepeatingRequest(request, captureCallback(listener), thread.handler)
        return newSession
    }

    private fun captureCallback(listener: CameraDeviceListener) = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureBufferLost(session: CameraCaptureSession, request: CaptureRequest, target: Surface, frameNumber: Long) {
            listener.onBufferLost(deviceId, outputs.firstOrNull { it.surface == target }?.role, frameNumber)
        }

        override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
            listener.onCaptureFailed(deviceId, failure.reason)
        }
    }

    /** Stops streaming into the current outputs without closing the device. */
    fun stopStreaming() {
        val s = session ?: return
        runCatching { s.stopRepeating() }
        runCatching { s.abortCaptures() }
        runCatching { s.close() }
        session = null
        outputs = emptyList()
    }

    fun close() {
        if (closed) return
        stopStreaming()
        closed = true
        runCatching { device.close() }
    }

    companion object {
        fun outputConfiguration(o: StreamOutput): OutputConfiguration = OutputConfiguration(o.surface).apply {
            o.physicalId?.let { setPhysicalCameraId(it) }
            if (o.kind == OutputKind.ENCODER && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                setTimestampBase(OutputConfiguration.TIMESTAMP_BASE_REALTIME)
            }
        }

        @SuppressLint("MissingPermission") // callers check CAMERA permission
        suspend fun open(manager: CameraManager, deviceId: String, thread: LooperThread, listener: CameraDeviceListener): CameraDeviceHandle =
            withTimeout(10_000) {
                suspendCancellableCoroutine { cont ->
                    var handle: CameraDeviceHandle? = null
                    val cb = object : CameraDevice.StateCallback() {
                        override fun onOpened(camera: CameraDevice) {
                            val h = CameraDeviceHandle(deviceId, camera, thread)
                            handle = h
                            if (cont.isActive) cont.resume(h) else camera.close()
                        }

                        override fun onDisconnected(camera: CameraDevice) {
                            AppLog.w(TAG, "Camera $deviceId disconnected")
                            if (cont.isActive) {
                                camera.close()
                                cont.resumeWithException(CameraOpenException("Camera $deviceId disconnected while opening (in use or not concurrently supported)"))
                            } else {
                                handle?.close()
                                listener.onDisconnected(deviceId)
                            }
                        }

                        override fun onError(camera: CameraDevice, error: Int) {
                            AppLog.e(TAG, "Camera $deviceId error $error")
                            if (cont.isActive) {
                                camera.close()
                                cont.resumeWithException(CameraOpenException("Camera $deviceId error ${errorName(error)}", error))
                            } else {
                                handle?.close()
                                listener.onError(deviceId, error)
                            }
                        }
                    }
                    try {
                        manager.openCamera(deviceId, thread.executor, cb)
                    } catch (e: CameraAccessException) {
                        cont.resumeWithException(CameraOpenException("openCamera($deviceId): ${e.message}", e.reason))
                    } catch (e: Exception) {
                        cont.resumeWithException(CameraOpenException("openCamera($deviceId): ${e.message}"))
                    }
                }
            }

        fun errorName(error: Int) = when (error) {
            CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "CAMERA_IN_USE"
            CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "MAX_CAMERAS_IN_USE"
            CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "CAMERA_DISABLED"
            CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "CAMERA_DEVICE"
            CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "CAMERA_SERVICE"
            else -> "UNKNOWN($error)"
        }

        private const val TAG = "CameraDevice"
    }
}

/**
 * Stand-in surfaces for querying/validating stream combinations without a live encoder:
 * PRIVATE ImageReaders flagged VIDEO_ENCODE behave like encoder surfaces for stream-combination purposes.
 */
object ProbeSurfaces {
    fun encoderLike(width: Int, height: Int, maxImages: Int = 4): ImageReader =
        ImageReader.newInstance(width, height, ImageFormat.PRIVATE, maxImages, HardwareBuffer.USAGE_VIDEO_ENCODE)

    fun previewLike(width: Int, height: Int): ImageReader =
        ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 2)
}
