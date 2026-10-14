package com.weekd.miracastreceiver.miracast

import android.content.Context
import android.content.Intent
import timber.log.Timber
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.charset.StandardCharsets

class WfdSessionHandler(
    private val context: Context,
    private val socket: Socket,
    private val rtpPort: Int
) {
    private val input: InputStream = socket.getInputStream()
    private val output: OutputStream = socket.getOutputStream()

    private var outCseq = 0
    private var sessionId = ""
    private var presentationUrl = ""
    private var playRequested = false

    var onSessionEstablished: ((sessionId: String) -> Unit)? = null
    var onStreamStart: ((rtpPort: Int) -> Unit)? = null
    var onStreamStop: (() -> Unit)? = null

    companion object {

        private const val VIDEO_FORMATS =
            "00 00 02 10 000001C0 00000000 00000000 00 0000 0000 00 none none"
        private const val AUDIO_CODECS = "AAC 00000001 00"
    }

    fun handleSession() {
        try {
            Timber.i("WFD session started with source ${socket.inetAddress.hostAddress}")
            while (!socket.isClosed) {
                val msg = readMessage() ?: break
                if (msg.startsWith("RTSP/1.0")) handleResponse(msg) else handleRequest(msg)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error in WFD session")
        } finally {
            Timber.i("WFD session ended")
            onStreamStop?.invoke()
            close()
        }
    }

    private fun readMessage(): String? {
        val buf = StringBuilder()
        val one = ByteArray(1)

        while (!buf.endsWith("\r\n\r\n")) {
            val n = input.read(one)
            if (n <= 0) return null
            buf.append(one[0].toInt().toChar())
            if (buf.length > 64 * 1024) {
                Timber.w("WFD: header too large, dropping session")
                return null
            }
        }

        val contentLength = Regex("(?i)Content-Length:\\s*(\\d+)")
            .find(buf)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        if (contentLength > 0) {
            val body = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = input.read(body, read, contentLength - read)
                if (n <= 0) return null
                read += n
            }
            buf.append(String(body, StandardCharsets.UTF_8))
        }

        val msg = buf.toString()
        Timber.d("WFD >> ${msg.lineSequence().first()}")
        Timber.v("WFD >> full:\n$msg")
        return msg
    }

    private fun send(msg: String) {
        Timber.d("WFD << ${msg.lineSequence().first()}")
        Timber.v("WFD << full:\n$msg")
        output.write(msg.toByteArray(StandardCharsets.UTF_8))
        output.flush()
    }

    private fun handleRequest(msg: String) {
        val method = msg.substringBefore(' ')
        val cseq = header(msg, "CSeq") ?: "0"

        when (method) {
            "OPTIONS" -> {
                sendOk(cseq, "Public: org.wfa.wfd1.0, GET_PARAMETER, SET_PARAMETER\r\n")
                sendOptions()                                    // M2
            }
            "GET_PARAMETER" -> {
                if (msg.contains("wfd_")) sendCapabilities(cseq)  // M3
                else sendOk(cseq)                                 // keep-alive
            }
            "SET_PARAMETER" -> {

                param(msg, "wfd_presentation_URL")
                    ?.substringBefore(' ')
                    ?.takeIf { it.startsWith("rtsp://") }
                    ?.let {
                        presentationUrl = it
                        Timber.i("WFD: presentation URL = $it")
                    }

                param(msg, "wfd_video_formats")?.let { selected ->
                    Timber.i("WFD: source selected video format = $selected")
                    val ceaBit = selected.split(' ').getOrNull(4)?.toLongOrNull(16) ?: 0L
                    val mode = when (ceaBit) {
                        0x100L -> "1920x1080p60"
                        0x080L -> "1920x1080p30"
                        0x040L -> "1280x720p60"
                        0x020L -> "1280x720p30"
                        else -> "CEA 0x%08X".format(ceaBit)
                    }
                    Timber.i("WFD: negotiated mode = $mode")
                }
                param(msg, "wfd_audio_codecs")?.let {
                    Timber.i("WFD: source selected audio codec = $it")
                }
                sendOk(cseq)
                if (msg.contains("wfd_trigger_method: SETUP")) sendSetup()      // M5 → M6
                if (msg.contains("wfd_trigger_method: TEARDOWN")) close()
            }
            "TEARDOWN" -> {
                sendOk(cseq)
                onStreamStop?.invoke()
                close()
            }
            else -> sendOk(cseq)
        }
    }

    private fun handleResponse(msg: String) {
        val session = header(msg, "Session")?.substringBefore(';')
        if (!session.isNullOrBlank() && sessionId.isEmpty()) {
            sessionId = session
            Timber.i("WFD: session id = $sessionId")
            onSessionEstablished?.invoke(sessionId)
            sendPlay()                                           // M7
        } else if (playRequested) {
            Timber.i("WFD: PLAY acknowledged, RTP should start on $rtpPort")
            onStreamStart?.invoke(rtpPort)
            startPlayerActivity()
            playRequested = false
        }
    }

    private fun sendOptions() = send(
        "OPTIONS * RTSP/1.0\r\n" +
            "CSeq: ${++outCseq}\r\n" +
            "Require: org.wfa.wfd1.0\r\n\r\n"
    )

    private fun sendSetup() {
        if (presentationUrl.isEmpty()) {
            presentationUrl = "rtsp://${socket.inetAddress.hostAddress}/wfd1.0/streamid=0"
        }
        send(
            "SETUP $presentationUrl RTSP/1.0\r\n" +
                "CSeq: ${++outCseq}\r\n" +
                "Transport: RTP/AVP/UDP;unicast;client_port=$rtpPort\r\n\r\n"
        )
    }

    private fun sendPlay() {
        playRequested = true
        send(
            "PLAY $presentationUrl RTSP/1.0\r\n" +
                "CSeq: ${++outCseq}\r\n" +
                "Session: $sessionId\r\n\r\n"
        )
    }

    private fun sendOk(cseq: String, extraHeaders: String = "") =
        send("RTSP/1.0 200 OK\r\nCSeq: $cseq\r\n$extraHeaders\r\n")

    private fun sendCapabilities(cseq: String) {
        val body = buildString {
            append("wfd_video_formats: $VIDEO_FORMATS\r\n")
            append("wfd_audio_codecs: $AUDIO_CODECS\r\n")

            append("wfd_client_rtp_ports: RTP/AVP/UDP;unicast $rtpPort 0 mode=play\r\n")
            append("wfd_content_protection: none\r\n")
            append("wfd_display_edid: none\r\n")
            append("wfd_uibc_capability: none\r\n")
            append("wfd_connector_type: 05\r\n")
        }
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        send(
            "RTSP/1.0 200 OK\r\n" +
                "CSeq: $cseq\r\n" +
                "Content-Type: text/parameters\r\n" +
                "Content-Length: ${bytes.size}\r\n\r\n" +
                body
        )
    }

    private fun header(msg: String, name: String): String? =
        Regex("(?i)^$name:\\s*(.+)$", RegexOption.MULTILINE)
            .find(msg)?.groupValues?.get(1)?.trim()

    private fun param(msg: String, name: String): String? =
        Regex("(?i)^$name:\\s*(.+)$", RegexOption.MULTILINE)
            .find(msg)?.groupValues?.get(1)?.trim()

    private fun startPlayerActivity() {
        val intent = Intent(context, com.weekd.miracastreceiver.ui.PlayerActivity::class.java).apply {
            putExtra(com.weekd.miracastreceiver.ui.PlayerActivity.EXTRA_SOURCE_TYPE, "MIRACAST")
            putExtra(com.weekd.miracastreceiver.ui.PlayerActivity.EXTRA_RTP_PORT, rtpPort)
            putExtra(com.weekd.miracastreceiver.ui.PlayerActivity.EXTRA_SESSION_ID, sessionId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        context.startActivity(intent)
    }

    fun close() {
        try {
            socket.close()
        } catch (e: Exception) {
            Timber.e(e, "Error closing WFD session socket")
        }
    }
}
