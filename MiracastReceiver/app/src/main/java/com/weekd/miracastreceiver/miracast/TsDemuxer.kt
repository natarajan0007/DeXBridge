package com.weekd.miracastreceiver.miracast

import timber.log.Timber
import java.io.ByteArrayOutputStream

class TsDemuxer(
    private val onAccessUnit: (data: ByteArray, ptsUs: Long) -> Unit,
    private val onDiscontinuity: () -> Unit = {},
    private val onAudioPes: (codec: AudioCodec, data: ByteArray) -> Unit = { _, _ -> },
    private val onAudioDiscontinuity: () -> Unit = {}
) {

    enum class AudioCodec { AAC_ADTS, LPCM }

    private var pmtPid = -1
    private var videoPid = -1
    private var audioPid = -1
    private var audioCodec = AudioCodec.AAC_ADTS

    private val videoPes = PesAssembler("video") { data, pts -> onAccessUnit(data, pts) }
    private val audioPes = PesAssembler("audio") { data, _ -> onAudioPes(audioCodec, data) }

    companion object {
        private const val TS_PACKET_SIZE = 188
        private const val SYNC_BYTE = 0x47.toByte()
        private const val STREAM_TYPE_H264 = 0x1B
        private const val STREAM_TYPE_HEVC = 0x24
        private const val STREAM_TYPE_AAC_ADTS = 0x0F
        private const val STREAM_TYPE_LPCM = 0x83
    }

    fun feed(data: ByteArray, offset: Int, length: Int) {
        var pos = offset
        val end = offset + length
        while (pos + TS_PACKET_SIZE <= end) {
            if (data[pos] != SYNC_BYTE) {
                pos++
                continue
            }
            handlePacket(data, pos)
            pos += TS_PACKET_SIZE
        }
    }

    private fun handlePacket(data: ByteArray, start: Int) {
        val b1 = data[start + 1].toInt() and 0xFF
        val b2 = data[start + 2].toInt() and 0xFF
        val b3 = data[start + 3].toInt() and 0xFF

        if ((b1 and 0x80) != 0) return                       // transport_error_indicator
        val payloadUnitStart = (b1 and 0x40) != 0
        val pid = ((b1 and 0x1F) shl 8) or b2
        val adaptationControl = (b3 shr 4) and 0x03

        if (adaptationControl == 0 || adaptationControl == 2) return

        var payloadStart = start + 4
        if (adaptationControl == 3) {
            val adaptationLength = data[start + 4].toInt() and 0xFF
            payloadStart = start + 5 + adaptationLength
        }
        val payloadEnd = start + TS_PACKET_SIZE
        if (payloadStart >= payloadEnd) return

        val continuity = b3 and 0x0F
        when (pid) {
            0 -> parsePat(data, payloadStart, payloadEnd, payloadUnitStart)
            pmtPid -> parsePmt(data, payloadStart, payloadEnd, payloadUnitStart)
            videoPid -> {

                if (videoPes.checkContinuity(continuity)) onDiscontinuity()
                videoPes.onPacket(data, payloadStart, payloadEnd, payloadUnitStart)
            }
            audioPid -> {
                if (audioPes.checkContinuity(continuity)) onAudioDiscontinuity()
                audioPes.onPacket(data, payloadStart, payloadEnd, payloadUnitStart)
            }
        }
    }

    private fun parsePat(data: ByteArray, start: Int, end: Int, payloadUnitStart: Boolean) {
        if (pmtPid >= 0) return
        var pos = start
        if (payloadUnitStart) pos += 1 + (data[pos].toInt() and 0xFF)   // pointer_field
        if (pos + 12 > end) return
        if ((data[pos].toInt() and 0xFF) != 0x00) return

        val sectionLength = ((data[pos + 1].toInt() and 0x0F) shl 8) or (data[pos + 2].toInt() and 0xFF)

        var entry = pos + 8
        val loopEnd = minOf(pos + 3 + sectionLength - 4, end)
        while (entry + 4 <= loopEnd) {
            val programNumber = ((data[entry].toInt() and 0xFF) shl 8) or (data[entry + 1].toInt() and 0xFF)
            val pid = ((data[entry + 2].toInt() and 0x1F) shl 8) or (data[entry + 3].toInt() and 0xFF)
            if (programNumber != 0) {
                pmtPid = pid
                Timber.i("TS: PMT pid = $pmtPid (program $programNumber)")
                return
            }
            entry += 4
        }
    }

    private fun parsePmt(data: ByteArray, start: Int, end: Int, payloadUnitStart: Boolean) {
        if (videoPid >= 0) return
        var pos = start
        if (payloadUnitStart) pos += 1 + (data[pos].toInt() and 0xFF)
        if (pos + 12 > end) return
        if ((data[pos].toInt() and 0xFF) != 0x02) return

        val sectionLength = ((data[pos + 1].toInt() and 0x0F) shl 8) or (data[pos + 2].toInt() and 0xFF)
        val programInfoLength = ((data[pos + 10].toInt() and 0x0F) shl 8) or (data[pos + 11].toInt() and 0xFF)

        var entry = pos + 12 + programInfoLength
        val loopEnd = minOf(pos + 3 + sectionLength - 4, end)
        while (entry + 5 <= loopEnd) {
            val streamType = data[entry].toInt() and 0xFF
            val pid = ((data[entry + 1].toInt() and 0x1F) shl 8) or (data[entry + 2].toInt() and 0xFF)
            val esInfoLength = ((data[entry + 3].toInt() and 0x0F) shl 8) or (data[entry + 4].toInt() and 0xFF)

            when (streamType) {
                STREAM_TYPE_H264, STREAM_TYPE_HEVC -> if (videoPid < 0) {
                    videoPid = pid
                    Timber.i("TS: video pid = $videoPid (stream_type 0x%02X)".format(streamType))
                }
                STREAM_TYPE_AAC_ADTS, STREAM_TYPE_LPCM -> if (audioPid < 0) {
                    audioPid = pid
                    audioCodec = if (streamType == STREAM_TYPE_LPCM) AudioCodec.LPCM else AudioCodec.AAC_ADTS
                    Timber.i("TS: audio pid = $audioPid (stream_type 0x%02X, $audioCodec)".format(streamType))
                }
            }
            entry += 5 + esInfoLength
        }
        if (audioPid < 0) Timber.w("TS: no audio stream in PMT")
    }

    fun reset() {
        pmtPid = -1
        videoPid = -1
        audioPid = -1
        onTransportDiscontinuity()
    }

    /** Discard incomplete PES after RTP loss while retaining negotiated stream PIDs. */
    fun onTransportDiscontinuity() {
        videoPes.reset()
        audioPes.reset()
    }

    private class PesAssembler(
        private val name: String,
        private val onUnit: (data: ByteArray, ptsUs: Long) -> Unit
    ) {
        private val buffer = ByteArrayOutputStream(64 * 1024)
        private var pendingPts = 0L
        private var hasPending = false

        private var expectedLength = 0

        private var lastContinuity = -1
        private var lossCount = 0

        fun checkContinuity(continuity: Int): Boolean {
            val gap = lastContinuity >= 0 && continuity != (lastContinuity + 1) % 16
            lastContinuity = continuity
            if (!gap) return false
            lossCount++
            Timber.w("TS: $name continuity gap, total $lossCount")
            discardPending()
            return true
        }

        fun onPacket(data: ByteArray, start: Int, end: Int, payloadUnitStart: Boolean) {
            var pos = start

            if (payloadUnitStart) {
                flush()

                if (pos + 9 > end) return

                if (data[pos].toInt() != 0x00 || data[pos + 1].toInt() != 0x00 ||
                    (data[pos + 2].toInt() and 0xFF) != 0x01
                ) return

                val packetLength = ((data[pos + 4].toInt() and 0xFF) shl 8) or (data[pos + 5].toInt() and 0xFF)
                val flags2 = data[pos + 7].toInt() and 0xFF
                val headerDataLength = data[pos + 8].toInt() and 0xFF
                val ptsPresent = (flags2 and 0x80) != 0

                if (ptsPresent && pos + 14 <= end) {
                    pendingPts = readPts(data, pos + 9)
                }

                expectedLength = if (packetLength > 0) packetLength - 3 - headerDataLength else 0
                hasPending = true
                pos += 9 + headerDataLength
                if (pos >= end) return
            }

            if (!hasPending) return
            buffer.write(data, pos, end - pos)
            if (expectedLength > 0 && buffer.size() >= expectedLength) flush()
        }

        private fun readPts(data: ByteArray, p: Int): Long {
            val b0 = data[p].toLong() and 0xFF
            val b1 = data[p + 1].toLong() and 0xFF
            val b2 = data[p + 2].toLong() and 0xFF
            val b3 = data[p + 3].toLong() and 0xFF
            val b4 = data[p + 4].toLong() and 0xFF
            val pts90k = ((b0 and 0x0E) shl 29) or
                (b1 shl 22) or ((b2 and 0xFE) shl 14) or
                (b3 shl 7) or ((b4 and 0xFE) shr 1)
            return pts90k * 1000 / 90
        }

        private fun flush() {
            if (!hasPending || buffer.size() == 0) {
                discardPending()
                return
            }
            val unit = buffer.toByteArray()
            discardPending()
            onUnit(unit, pendingPts)
        }

        private fun discardPending() {
            buffer.reset()
            hasPending = false
        }

        fun reset() {
            lastContinuity = -1
            discardPending()
        }
    }
}
