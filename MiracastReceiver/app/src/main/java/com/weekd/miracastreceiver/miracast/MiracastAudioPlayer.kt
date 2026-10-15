package com.weekd.miracastreceiver.miracast

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class MiracastAudioPlayer {

    private val queue = ArrayBlockingQueue<Pair<TsDemuxer.AudioCodec, ByteArray>>(QUEUE_CAPACITY)

    @Volatile
    private var running = false

    @Volatile
    private var released = false
    private var thread: Thread? = null

    @Volatile
    private var resyncRequested = false

    private var codec: MediaCodec? = null
    private var codecConfig = 0
    private var audioTrack: AudioTrack? = null
    private var trackSampleRate = 0
    private var trackChannels = 0
    private val adts = ByteArrayOutputStream(16 * 1024)
    private var firstPcm = true
    private val receivedPes = AtomicLong()
    private val droppedPes = AtomicLong()
    private var processedPes = 0L
    private var decodedAacFrames = 0L
    private var codecInputMisses = 0L
    private var pcmWriteCount = 0L
    private var pcmWriteTimeNs = 0L
    private var maxPcmWriteNs = 0L

    companion object {

        private const val QUEUE_CAPACITY = 64

        // AAC is 1024 samples per PES at 48 kHz (~21 ms). Allow brief Wi-Fi
        // bursts to drain before skipping audio; retain ~170 ms after resync.
        private const val MAX_BACKLOG = 32
        private const val QUEUE_KEEP = 8

        private val SAMPLE_RATES = intArrayOf(
            96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350
        )
    }

    fun onAudioPes(codecType: TsDemuxer.AudioCodec, data: ByteArray) {
        if (released) return
        if (!running) start()
        receivedPes.incrementAndGet()
        val item = codecType to data
        if (!queue.offer(item)) {
            queue.poll()
            queue.offer(item)
            droppedPes.incrementAndGet()
        }
    }

    fun onDiscontinuity() {
        resyncRequested = true
    }

    @Synchronized
    private fun start() {
        if (running || released) return
        running = true
        thread = Thread(::runPlayback, "MiracastAudio").apply { start() }
    }

    @Synchronized
    fun release() {
        released = true
        running = false
        thread?.interrupt()
        thread = null
        queue.clear()
    }

    private fun runPlayback() {
        Timber.i("Miracast audio: playback thread started")
        var lastStatsNs = System.nanoTime()
        try {
            while (running) {
                var item = try {
                    queue.poll(200, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    null
                } ?: continue

                if (queue.size > MAX_BACKLOG) {
                    // The polled item is the oldest one. Discard it too; playing
                    // stale audio immediately before jumping forward is audible.
                    var dropped = 1
                    while (queue.size > QUEUE_KEEP) {
                        queue.poll()
                        dropped++
                    }
                    droppedPes.addAndGet(dropped.toLong())
                    resyncRequested = true
                    Timber.w("Miracast audio: behind real time, dropped $dropped PES " +
                        "(total ${droppedPes.get()})")
                    item = queue.poll() ?: continue
                }

                try {
                    val (type, data) = item
                    when (type) {
                        TsDemuxer.AudioCodec.AAC_ADTS -> handleAac(data)
                        TsDemuxer.AudioCodec.LPCM -> handleLpcm(data)
                    }
                    processedPes++
                } catch (e: Exception) {
                    if (running) Timber.e(e, "Miracast audio: decode error")
                    releaseCodec()
                }

                val nowNs = System.nanoTime()
                if (nowNs - lastStatsNs >= TimeUnit.SECONDS.toNanos(5)) {
                    Timber.i("Miracast audio stats: received=${receivedPes.get()}, " +
                        "processed=$processedPes, dropped=${droppedPes.get()}, queue=${queue.size}, " +
                        "decodedFrames=$decodedAacFrames, inputMisses=$codecInputMisses, " +
                        "pcmWrites=$pcmWriteCount, avgWriteMs=${if (pcmWriteCount > 0) pcmWriteTimeNs / pcmWriteCount / 1_000_000 else 0}, " +
                        "maxWriteMs=${maxPcmWriteNs / 1_000_000}")
                    lastStatsNs = nowNs
                }
            }
        } finally {
            releaseCodec()
            releaseTrack()
            Timber.i("Miracast audio: playback thread stopped, dropped ${droppedPes.get()} PES")
        }
    }

    // ─── AAC ────────────────────────────────────────────────────────────────

    private fun handleAac(data: ByteArray) {
        if (resyncRequested) {
            resyncRequested = false
            adts.reset()
        }
        adts.write(data)
        val buf = adts.toByteArray()
        var pos = 0

        while (pos + 7 <= buf.size) {

            if ((buf[pos].toInt() and 0xFF) != 0xFF || (buf[pos + 1].toInt() and 0xF0) != 0xF0) {
                pos++
                continue
            }
            val b1 = buf[pos + 1].toInt() and 0xFF
            val b2 = buf[pos + 2].toInt() and 0xFF
            val b3 = buf[pos + 3].toInt() and 0xFF
            val b4 = buf[pos + 4].toInt() and 0xFF
            val b5 = buf[pos + 5].toInt() and 0xFF

            val headerLength = if ((b1 and 0x01) != 0) 7 else 9
            val objectType = ((b2 shr 6) and 0x03) + 1               // ADTS profile + 1 = AudioObjectType
            val srIndex = (b2 shr 2) and 0x0F
            val channelConfig = ((b2 and 0x01) shl 2) or ((b3 shr 6) and 0x03)
            val frameLength = ((b3 and 0x03) shl 11) or (b4 shl 3) or ((b5 shr 5) and 0x07)

            if (srIndex >= SAMPLE_RATES.size || channelConfig == 0 || frameLength <= headerLength) {
                pos++
                continue
            }
            if (pos + frameLength > buf.size) break

            ensureAacDecoder(objectType, srIndex, channelConfig)
            decodeAacFrame(buf, pos + headerLength, frameLength - headerLength)
            pos += frameLength
        }

        adts.reset()
        if (pos < buf.size) adts.write(buf, pos, buf.size - pos)
    }

    private fun ensureAacDecoder(objectType: Int, srIndex: Int, channelConfig: Int) {

        val asc = (objectType shl 11) or (srIndex shl 7) or (channelConfig shl 3)
        if (codec != null && asc == codecConfig) return

        releaseCodec()
        val sampleRate = SAMPLE_RATES[srIndex]
        val channels = if (channelConfig == 7) 8 else channelConfig
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            if (objectType == 2) setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf((asc shr 8).toByte(), asc.toByte())))
        }
        codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(format, null, null, 0)
            start()
        }
        codecConfig = asc
        ensureTrack(sampleRate, channels)
        Timber.i("Miracast audio: AAC decoder ready, objectType=$objectType ${sampleRate}Hz x$channels")
    }

    private fun decodeAacFrame(buf: ByteArray, offset: Int, length: Int) {
        val mc = codec ?: return
        val inIdx = mc.dequeueInputBuffer(10_000)
        if (inIdx >= 0) {
            val inBuf = mc.getInputBuffer(inIdx)!!
            inBuf.clear()
            inBuf.put(buf, offset, length)
            mc.queueInputBuffer(inIdx, 0, length, 0, 0)
        } else {
            codecInputMisses++
        }

        val info = MediaCodec.BufferInfo()
        while (true) {
            val outIdx = mc.dequeueOutputBuffer(info, 0)
            when {
                outIdx >= 0 -> {
                    val outBuf = mc.getOutputBuffer(outIdx)!!
                    val pcm = ByteArray(info.size)
                    outBuf.position(info.offset)
                    outBuf.get(pcm)
                    mc.releaseOutputBuffer(outIdx, false)
                    decodedAacFrames++
                    writePcm(pcm, pcm.size)
                }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {

                    val fmt = mc.outputFormat
                    ensureTrack(
                        fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                        fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    )
                }
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> return

            }
        }
    }

    // ─── LPCM ───────────────────────────────────────────────────────────────

    private fun handleLpcm(data: ByteArray) {
        var offset = 0
        var sampleRate = 48000
        var channels = 2
        if (data.size >= 4 && (data[0].toInt() and 0xFF) == 0xA0) {
            val info = data[3].toInt() and 0xFF
            sampleRate = if (((info shr 3) and 0x07) == 1) 44100 else 48000
            channels = if ((info and 0x07) == 0) 1 else 2
            offset = 4
        }
        val length = (data.size - offset) and 1.inv()
        if (length <= 0) return

        val pcm = ByteArray(length)
        var i = 0
        while (i < length) {
            pcm[i] = data[offset + i + 1]
            pcm[i + 1] = data[offset + i]
            i += 2
        }
        ensureTrack(sampleRate, channels)
        writePcm(pcm, length)
    }

    // ─── AudioTrack ─────────────────────────────────────────────────────────

    private fun ensureTrack(sampleRate: Int, channels: Int) {
        if (audioTrack != null && sampleRate == trackSampleRate && channels == trackChannels) return
        releaseTrack()

        val channelMask = if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)

        audioTrack = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setBufferSizeInBytes(minBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } else {

            @Suppress("DEPRECATION")
            AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf,
                AudioTrack.MODE_STREAM
            )
        }.also { it.play() }
        trackSampleRate = sampleRate
        trackChannels = channels
        Timber.i("Miracast audio: AudioTrack ${sampleRate}Hz x$channels, buffer=${minBuf}B " +
            "(~${minBuf * 1000 / (sampleRate * channels * 2)}ms)")
    }

    private fun writePcm(pcm: ByteArray, length: Int) {
        val track = audioTrack ?: return
        if (firstPcm) {
            firstPcm = false
            Timber.i("Miracast audio: first PCM (${length}B) → AudioTrack")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val startNs = System.nanoTime()
            track.write(pcm, 0, length, AudioTrack.WRITE_BLOCKING)
            val writeNs = System.nanoTime() - startNs
            pcmWriteCount++
            pcmWriteTimeNs += writeNs
            maxPcmWriteNs = maxOf(maxPcmWriteNs, writeNs)
        } else {
            @Suppress("DEPRECATION")
            track.write(pcm, 0, length)
        }
    }

    private fun releaseCodec() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        codecConfig = 0
    }

    private fun releaseTrack() {
        runCatching { audioTrack?.stop() }
        runCatching { audioTrack?.release() }
        audioTrack = null
        trackSampleRate = 0
        trackChannels = 0
    }
}
