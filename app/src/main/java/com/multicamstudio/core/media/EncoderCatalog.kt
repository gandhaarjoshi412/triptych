package com.multicamstudio.core.media

import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.EncoderCapabilities
import android.media.MediaCodecList
import android.os.Build
import com.multicamstudio.domain.model.VideoResolution

data class EncoderInfo(
    val name: String,
    val mime: String,
    val hardwareAccelerated: Boolean,
    val softwareOnly: Boolean,
    val vendor: Boolean,
    val supports1080p30: Boolean,
    val supports720p30: Boolean,
    val bitrateMin: Int?,
    val bitrateMax: Int?,
    val vbr: Boolean,
    val cbr: Boolean,
    val cq: Boolean,
    val maxInstances: Int,
    val performancePoints: List<String>,
    /** A performance point covers 1080p30 (single instance). */
    val perfCovers1080p30: Boolean?,
    /** A performance point covers 1080p@90 – an *indication only* of throughput for three 1080p30 streams. */
    val perfCovers1080p90Aggregate: Boolean?,
    val hevcMainProfile: Boolean,
)

/**
 * Discovers encoders via MediaCodecList. Selection never falls back to a software encoder silently:
 * if no hardware encoder exists for the codec, [selectHardware] returns null and the caller must report it.
 */
object EncoderCatalog {
    fun list(mime: String): List<EncoderInfo> =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, ignoreCase = true) } }
            .map { describe(it, mime) }

    fun audioEncoders(): List<String> =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals("audio/mp4a-latm", true) } }
            .map { "${it.name} (hw=${it.isHardwareAccelerated})" }

    private fun describe(info: MediaCodecInfo, mime: String): EncoderInfo {
        val caps = info.getCapabilitiesForType(mime)
        val video = caps.videoCapabilities
        val enc = caps.encoderCapabilities
        val points = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) video?.supportedPerformancePoints else null
        val pp1080 = MediaCodecInfo.VideoCapabilities.PerformancePoint(1920, 1080, 30)
        val pp1080x3 = MediaCodecInfo.VideoCapabilities.PerformancePoint(1920, 1080, 90)
        return EncoderInfo(
            name = info.name,
            mime = mime,
            hardwareAccelerated = info.isHardwareAccelerated,
            softwareOnly = info.isSoftwareOnly,
            vendor = info.isVendor,
            supports1080p30 = video?.areSizeAndRateSupported(1920, 1080, 30.0) == true,
            supports720p30 = video?.areSizeAndRateSupported(1280, 720, 30.0) == true,
            bitrateMin = video?.bitrateRange?.lower,
            bitrateMax = video?.bitrateRange?.upper,
            vbr = enc?.isBitrateModeSupported(EncoderCapabilities.BITRATE_MODE_VBR) == true,
            cbr = enc?.isBitrateModeSupported(EncoderCapabilities.BITRATE_MODE_CBR) == true,
            cq = enc?.isBitrateModeSupported(EncoderCapabilities.BITRATE_MODE_CQ) == true,
            maxInstances = caps.maxSupportedInstances,
            performancePoints = points?.map { it.toString() }.orEmpty(),
            perfCovers1080p30 = points?.any { it.covers(pp1080) },
            perfCovers1080p90Aggregate = points?.any { it.covers(pp1080x3) },
            hevcMainProfile = caps.profileLevels.any { it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain },
        )
    }

    /** Hardware, non-software-only encoder supporting the resolution at 30 fps; vendor codecs preferred. */
    fun selectHardware(mime: String, resolution: VideoResolution, fps: Int = 30): EncoderInfo? =
        list(mime)
            .filter { it.hardwareAccelerated && !it.softwareOnly }
            .filter { e ->
                runCatching {
                    MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.first { it.name == e.name }
                        .getCapabilitiesForType(mime).videoCapabilities.areSizeAndRateSupported(resolution.width, resolution.height, fps.toDouble())
                }.getOrDefault(false)
            }
            .sortedByDescending { it.vendor }
            .firstOrNull()
}
