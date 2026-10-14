package com.weekd.miracastreceiver.ui

import android.os.SystemClock

class StreamInfoTracker {

    data class Sample(val bitrateBps: Long, val bytesPerSec: Long, val fps: Int)

    private var lastSampleMs = 0L
    private var lastBytes = 0L
    private var lastFrames = 0L

    fun reset() {
        lastSampleMs = 0L
        lastBytes = 0L
        lastFrames = 0L
    }

    fun sample(totalBytes: Long, totalFrames: Long): Sample {
        val now = SystemClock.elapsedRealtime()
        val elapsedMs = now - lastSampleMs
        val isFirst = lastSampleMs == 0L
        val restarted = totalBytes < lastBytes || totalFrames < lastFrames

        val sample = if (isFirst || restarted || elapsedMs <= 0) {
            Sample(0L, 0L, 0)
        } else {
            val bytes = totalBytes - lastBytes
            val frames = totalFrames - lastFrames
            Sample(
                bitrateBps = bytes * 8 * 1000 / elapsedMs,
                bytesPerSec = bytes * 1000 / elapsedMs,
                fps = (frames * 1000 / elapsedMs).toInt()
            )
        }

        lastSampleMs = now
        lastBytes = totalBytes
        lastFrames = totalFrames
        return sample
    }

    companion object {

        fun formatBitrate(bps: Long): String = when {
            bps <= 0 -> "—"
            bps >= 1_000_000 -> String.format("%.1f Mbps", bps / 1_000_000.0)
            else -> "${bps / 1000} kbps"
        }

        fun formatSpeed(bytesPerSec: Long): String = when {
            bytesPerSec <= 0 -> "—"
            bytesPerSec >= 1024 * 1024 -> String.format("%.1f MB/s", bytesPerSec / (1024.0 * 1024.0))
            else -> "${bytesPerSec / 1024} KB/s"
        }

        fun formatResolution(width: Int, height: Int): String =
            if (width > 0 && height > 0) "${width}x$height" else "—"

        fun formatFps(fps: Float): String =
            if (fps > 0f) String.format("%.0f fps", fps) else "—"

        fun formatCodec(mimeType: String?): String = when (mimeType?.lowercase()) {
            null, "" -> "—"
            "video/avc" -> "H.264 (AVC)"
            "video/hevc" -> "H.265 (HEVC)"
            "video/av01" -> "AV1"
            "video/x-vnd.on2.vp9" -> "VP9"
            "video/x-vnd.on2.vp8" -> "VP8"
            "video/mpeg2" -> "MPEG-2"
            "video/mp4v-es" -> "MPEG-4"
            "video/3gpp" -> "H.263"
            "video/dolby-vision" -> "Dolby Vision"
            else -> mimeType.removePrefix("video/").uppercase()
        }

        fun padLabel(label: String, columns: Int = 12): String {
            val width = label.fold(0) { acc, c -> acc + if (c.code >= 0x2E80) 2 else 1 }
            return label + " ".repeat((columns - width).coerceAtLeast(1))
        }
    }
}
