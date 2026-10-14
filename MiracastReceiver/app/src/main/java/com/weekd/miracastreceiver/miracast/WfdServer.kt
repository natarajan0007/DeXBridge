package com.weekd.miracastreceiver.miracast

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.*
import timber.log.Timber
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

class WfdServer(
    private val context: Context,
    private val port: Int = 7236,
    private val rtpPort: Int = 19000
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var isRunning = false

    private var sessionHandler: WfdSessionHandler? = null
    private var rtpReceiver: RtpReceiver? = null

    var onConnectionRequested: ((clientName: String, clientAddress: String) -> Unit)? = null
    var onConnectionEstablished: ((sessionId: String) -> Unit)? = null
    var onStreamStarted: ((rtpPort: Int) -> Unit)? = null
    var onStreamStopped: (() -> Unit)? = null

    companion object {
        private const val SCAN_INTERVAL_MS = 3_000L
        private const val CONNECT_TIMEOUT_MS = 400
        private const val SCAN_CHUNK = 32
    }

    fun start() {
        if (isRunning) {
            Timber.w("WFD session starter already running")
            return
        }
        isRunning = true
        Timber.i("WFD session starter running (will dial source:$port, RTP on $rtpPort)")

        scope.launch {
            while (isRunning) {
                try {
                    val socket = dialSource()
                    if (socket != null) {
                        runSession(socket)
                    }
                } catch (e: Exception) {
                    if (isRunning) Timber.e(e, "WFD session loop error")
                }
                if (isRunning) delay(SCAN_INTERVAL_MS)
            }
        }
    }

    private suspend fun dialSource(): Socket? = coroutineScope {
        val localAddress = p2pLocalIpv4Address() ?: return@coroutineScope null
        val prefix = localAddress.substringBeforeLast('.') + "."
        val p2pNetwork = p2pNetwork(localAddress)

        if (p2pNetwork == null) {
            Timber.w("WFD: no Android Network matched P2P address $localAddress; probes may use the default route")
        } else {
            Timber.d("WFD: binding source probes to P2P network for $localAddress")
        }

        for (range in p2pHostCandidates(prefix, localAddress).chunked(SCAN_CHUNK)) {
            if (!isRunning) return@coroutineScope null
            val connected = range.map { address ->
                async { tryConnect(address, p2pNetwork) }
            }.awaitAll().filterNotNull()

            if (connected.isNotEmpty()) {
                val session = connected.first()
                connected.drop(1).forEach { runCatching { it.close() } }
                Timber.i("WFD: connected to source at ${session.inetAddress.hostAddress}:$port")
                return@coroutineScope session
            }
        }
        null
    }

    private fun tryConnect(ip: String, network: Network?): Socket? = try {
        Socket().also {
            // Android devices commonly have both infrastructure Wi-Fi and Wi-Fi
            // Direct routes. Binding is required or connect() may select the
            // default route, making the P2P source appear unreachable.
            network?.bindSocket(it)
            it.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS)
        }
    } catch (e: Exception) {
        null
    }

    /** Find the framework Network whose link is the Wi-Fi Direct interface. */
    private fun p2pNetwork(localAddress: String): Network? = try {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        connectivity.allNetworks.firstOrNull { network ->
            val properties = connectivity.getLinkProperties(network)
            val interfaceName = properties?.interfaceName
            interfaceName?.startsWith("p2p") == true ||
                properties?.linkAddresses?.any { it.address.hostAddress == localAddress } == true
        }
    } catch (e: Exception) {
        Timber.w(e, "WFD: failed to resolve the P2P Android Network")
        null
    }

    /** Get the TV's IPv4 address on its active Wi-Fi Direct interface. */
    private fun p2pLocalIpv4Address(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .firstOrNull { it.name.startsWith("p2p") && it.isUp }
            ?.inetAddresses?.toList()
            ?.firstOrNull { !it.isLoopbackAddress && it.address.size == 4 }
            ?.hostAddress
    } catch (e: Exception) {
        Timber.e(e, "Failed to resolve P2P subnet")
        null
    }

    private fun runSession(socket: Socket) {
        val sourceIp = socket.inetAddress.hostAddress ?: "unknown"
        onConnectionRequested?.invoke("Windows PC", sourceIp)

        val receiver = RtpReceiver(rtpPort, { com.weekd.miracastreceiver.ui.PlayerActivity.mirrorSurface }).apply {
            onError = { Timber.e("Miracast RTP error: $it") }
            start()
        }
        rtpReceiver = receiver

        val handler = WfdSessionHandler(context, socket, rtpPort).apply {
            onSessionEstablished = { onConnectionEstablished?.invoke(it) }
            onStreamStart = { onStreamStarted?.invoke(it) }
            onStreamStop = { onStreamStopped?.invoke() }
        }
        sessionHandler = handler

        try {
            handler.handleSession()
        } finally {
            receiver.stop()
            rtpReceiver = null
            sessionHandler = null
        }
    }

    fun stop() {
        isRunning = false
        sessionHandler?.close()
        sessionHandler = null
        rtpReceiver?.stop()
        rtpReceiver = null
        scope.cancel()
        Timber.i("WFD session starter stopped")
    }

    fun isRunning(): Boolean = isRunning
}

/** Include the usual group-owner address (.1); never probe the TV's own P2P address. */
internal fun p2pHostCandidates(prefix: String, localAddress: String): List<String> =
    (1..254).asSequence()
        .map { "$prefix$it" }
        .filterNot { it == localAddress }
        .toList()
