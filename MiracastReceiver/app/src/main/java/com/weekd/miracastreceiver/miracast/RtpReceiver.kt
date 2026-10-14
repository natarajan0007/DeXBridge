package com.weekd.miracastreceiver.miracast

import android.view.Surface
import kotlinx.coroutines.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import timber.log.Timber
import java.net.DatagramPacket
import java.net.DatagramSocket

class RtpReceiver(
    private val port: Int,
    private val surfaceProvider: () -> Surface?
) {

    private var socket: DatagramSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var isRunning = false

    private val renderer = MiracastVideoRenderer(surfaceProvider)
    private val audioPlayer = MiracastAudioPlayer()
    private val demuxer = TsDemuxer(
        onAccessUnit = renderer::onAccessUnit,
        onDiscontinuity = renderer::onDiscontinuity,

        onAudioPes = { codec, data -> if (surfaceProvider() != null) audioPlayer.onAudioPes(codec, data) },
        onAudioDiscontinuity = audioPlayer::onDiscontinuity
    )

    private val payloadQueue = ArrayBlockingQueue<ByteArray>(256)

    @Volatile
    var packetsLost = 0L
        private set

    @Volatile
    var packetsReceived = 0L
        private set

    @Volatile
    var bytesReceived = 0L
        private set

    var onError: ((String) -> Unit)? = null

    companion object {

        @Volatile
        var active: RtpReceiver? = null
    }

    fun start() {
        if (isRunning) {
            Timber.w("RTP Receiver already running")
            return
        }

        active = this

        scope.launch { runDecoder() }

        scope.launch {
            try {
                socket = DatagramSocket(port).apply {

                    runCatching { receiveBufferSize = 1024 * 1024 }
                }
                isRunning = true
                Timber.i("RTP Receiver listening on UDP $port (expecting MPEG-2 TS, PT=33), " +
                    "recvBuf=${socket?.receiveBufferSize}")

                val buffer = ByteArray(65536)
                val packet = DatagramPacket(buffer, buffer.size)
                var loggedFirst = false
                var expectedSeq = -1

                while (isRunning) {
                    try {
                        packet.length = buffer.size
                        socket?.receive(packet)
                        if (packet.length <= 0) continue

                        packetsReceived++
                        bytesReceived += packet.length

                        val payloadOffset = rtpHeaderLength(packet.data, packet.length)
                        if (payloadOffset <= 0 || payloadOffset >= packet.length) continue

                        if (!loggedFirst) {
                            loggedFirst = true
                            val pt = packet.data[1].toInt() and 0x7F
                            Timber.i("First RTP packet: ${packet.length}B payloadType=$pt " +
                                "payload=${packet.length - payloadOffset}B")
                        }

                        val seq = ((packet.data[2].toInt() and 0xFF) shl 8) or
                            (packet.data[3].toInt() and 0xFF)
                        if (expectedSeq >= 0 && seq != expectedSeq) {
                            val lost = (seq - expectedSeq + 0x10000) and 0xFFFF
                            packetsLost += lost
                            Timber.w("RTP: lost $lost packets (seq $expectedSeq → $seq)")
                            renderer.onDiscontinuity()
                            audioPlayer.onDiscontinuity()
                        }
                        expectedSeq = (seq + 1) and 0xFFFF

                        val payload = packet.data.copyOfRange(payloadOffset, packet.length)
                        if (!payloadQueue.offer(payload)) {
                            payloadQueue.poll()
                            payloadQueue.offer(payload)
                            renderer.onDiscontinuity()
                            audioPlayer.onDiscontinuity()
                        }

                        if (packetsReceived % 1000L == 0L) {
                            Timber.i("RTP stats: $packetsReceived packets, ${bytesReceived / 1024}KB, " +
                                "lost=$packetsLost, queue=${payloadQueue.size}")
                        }
                    } catch (e: Exception) {
                        if (isRunning) Timber.e(e, "Error receiving RTP packet")
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to start RTP Receiver")
                onError?.invoke("Failed to start RTP receiver: ${e.message}")
                isRunning = false
            }
        }
    }

    private suspend fun runDecoder() {
        while (scope.isActive) {
            val payload = withContext(Dispatchers.IO) {
                payloadQueue.poll(200, TimeUnit.MILLISECONDS)
            } ?: continue
            try {
                demuxer.feed(payload, 0, payload.size)
            } catch (e: Exception) {
                Timber.e(e, "Error demuxing TS payload")
                renderer.onDiscontinuity()
            }
        }
    }

    private fun rtpHeaderLength(data: ByteArray, length: Int): Int {
        if (length < 12) return -1
        val version = (data[0].toInt() shr 6) and 0x03
        if (version != 2) return -1

        val csrcCount = data[0].toInt() and 0x0F
        val hasExtension = ((data[0].toInt() shr 4) and 0x01) == 1
        var headerLength = 12 + csrcCount * 4

        if (hasExtension) {
            if (length < headerLength + 4) return -1
            val extWords = ((data[headerLength + 2].toInt() and 0xFF) shl 8) or
                (data[headerLength + 3].toInt() and 0xFF)
            headerLength += 4 + extWords * 4
        }
        return if (headerLength < length) headerLength else -1
    }

    fun stop() {
        isRunning = false
        scope.cancel()
        renderer.release()
        audioPlayer.release()
        demuxer.reset()
        if (active === this) active = null

        try {
            socket?.close()
            socket = null
        } catch (e: Exception) {
            Timber.e(e, "Error closing RTP socket")
        }
        Timber.i("RTP Receiver stopped: $packetsReceived packets, ${bytesReceived / 1024}KB")
    }

    fun isRunning(): Boolean = isRunning
}
