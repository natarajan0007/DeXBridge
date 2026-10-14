package com.weekd.miracastreceiver.miracast

import android.view.Surface
import com.weekd.miracastreceiver.airplay.VideoDecoder
import timber.log.Timber

class MiracastVideoRenderer(private val surfaceProvider: () -> Surface?) {

    private var decoder: VideoDecoder? = null
    private var configuredSurface: Surface? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var awaitingKeyframe = true
    private var droppedBeforeConfig = 0

    fun onDiscontinuity() {
        if (!awaitingKeyframe) {
            awaitingKeyframe = true
            Timber.w("Miracast: data loss detected, waiting for next keyframe")
        }
    }

    fun onAccessUnit(unit: ByteArray, ptsUs: Long) {
        captureParameterSets(unit)

        val surface = surfaceProvider()
        if (surface == null || !surface.isValid) {
            releaseDecoder()
            return
        }

        if (decoder == null || surface !== configuredSurface) {
            if (!rebuildDecoder(surface)) {
                droppedBeforeConfig++
                if (droppedBeforeConfig % 60 == 0) {
                    Timber.d("Miracast: waiting for SPS/PPS, dropped $droppedBeforeConfig units")
                }
                return
            }
        }

        if (awaitingKeyframe) {
            if (!containsIdr(unit)) return
            awaitingKeyframe = false
            Timber.i("Miracast: keyframe found, decoding started")
        }

        decoder?.decodeNalUnit(unit, ptsUs)
    }

    private fun rebuildDecoder(surface: Surface): Boolean {
        val spsBytes = sps ?: return false
        val ppsBytes = pps ?: return false

        releaseDecoder()
        val (width, height) = VideoDecoder.parseSpsResolution(spsBytes) ?: (1920 to 1080)
        decoder = VideoDecoder(surface).also { it.initialize(spsBytes, ppsBytes, width, height) }
        configuredSurface = surface
        awaitingKeyframe = true
        Timber.i("Miracast decoder initialized: ${width}x$height")
        return true
    }

    private fun captureParameterSets(unit: ByteArray) {
        if (sps != null && pps != null) return
        forEachNal(unit) { start, end ->
            val nalType = unit[start].toInt() and 0x1F
            if (nalType == 7 || nalType == 8) {
                val withStartCode = ByteArray(4 + (end - start))
                withStartCode[3] = 1
                System.arraycopy(unit, start, withStartCode, 4, end - start)
                if (nalType == 7 && sps == null) {
                    sps = withStartCode
                    Timber.i("Miracast: SPS captured (${end - start} bytes)")
                } else if (nalType == 8 && pps == null) {
                    pps = withStartCode
                    Timber.i("Miracast: PPS captured (${end - start} bytes)")
                }
            }
        }
    }

    private fun containsIdr(unit: ByteArray): Boolean {
        var found = false
        forEachNal(unit) { start, _ ->
            if ((unit[start].toInt() and 0x1F) == 5) found = true
        }
        return found
    }

    private inline fun forEachNal(data: ByteArray, action: (start: Int, end: Int) -> Unit) {
        var i = 0
        var nalStart = -1
        while (i + 3 <= data.size) {
            val isStartCode3 = data[i].toInt() == 0 && data[i + 1].toInt() == 0 &&
                data[i + 2].toInt() == 1
            val isStartCode4 = i + 4 <= data.size && data[i].toInt() == 0 &&
                data[i + 1].toInt() == 0 && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1
            if (isStartCode3 || isStartCode4) {
                if (nalStart >= 0) action(nalStart, i)
                i += if (isStartCode4) 4 else 3
                nalStart = i
            } else {
                i++
            }
        }
        if (nalStart in 0 until data.size) action(nalStart, data.size)
    }

    private fun releaseDecoder() {
        decoder?.release()
        decoder = null
        configuredSurface = null
    }

    fun release() {
        releaseDecoder()
        sps = null
        pps = null
        awaitingKeyframe = true
    }
}
