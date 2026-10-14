package com.weekd.miracastreceiver.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.content.BroadcastReceiver
import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.weekd.miracastreceiver.R
import com.weekd.miracastreceiver.airplay.AirPlayReceiver
import com.weekd.miracastreceiver.discovery.DeviceInfoProvider
import com.weekd.miracastreceiver.dlna.DlnaMediaRenderer
import com.weekd.miracastreceiver.dlna.SsdpServer
import com.weekd.miracastreceiver.dlna.UpnpHttpServer
import com.weekd.miracastreceiver.miracast.WfdServer
import com.weekd.miracastreceiver.miracast.WfdRootHelper
import com.weekd.miracastreceiver.miracast.WifiDirectManager
import com.weekd.miracastreceiver.utils.NetworkUtils
import timber.log.Timber
import java.util.UUID

class CastReceiverService : Service() {

    companion object {

        const val EXTRA_FROM_BOOT = "from_boot"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "cast_receiver_service"
        private const val CHANNEL_NAME = "Cast receiver service"
        private const val ACTION_UPDATE_POSITION = "com.weekd.miracastreceiver.ACTION_UPDATE_POSITION"
        private const val ACTION_PLAYBACK_STOPPED = "com.weekd.miracastreceiver.ACTION_PLAYBACK_STOPPED"

        private const val NETWORK_RETRY_INTERVAL_MS = 5_000L
        private const val NETWORK_MAX_WAIT_MS = 5 * 60_000L
    }

    private lateinit var airPlayReceiver: AirPlayReceiver
    private lateinit var dlnaRenderer: DlnaMediaRenderer
    private lateinit var ssdpServer: SsdpServer
    private lateinit var upnpHttpServer: UpnpHttpServer
    private lateinit var wfdServer: WfdServer
    private lateinit var wifiDirectManager: WifiDirectManager
    private lateinit var deviceUuid: String
    private var airPlayPlayerStarted = false

    private var initialized = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var networkWaitRunnable: Runnable? = null
    private var networkWaitElapsedMs = 0L

    private val playerStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_UPDATE_POSITION -> {
                    val position = intent.getLongExtra("position", 0L)
                    val duration = intent.getLongExtra("duration", 0L)
                    val isPlaying = intent.getBooleanExtra("is_playing", false)
                    dlnaRenderer.updatePosition(position, duration)
                    if (isPlaying) dlnaRenderer.setPlaying() else dlnaRenderer.setPaused()
                    Timber.d("Player position updated: $position / $duration")
                }
                ACTION_PLAYBACK_STOPPED -> {

                    dlnaRenderer.updatePosition(0L, 0L)
                    dlnaRenderer.setStopped()
                    Timber.i("Playback stopped locally, DLNA renderer reset to STOPPED")
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Timber.i("CastReceiverService created")
        createNotificationChannel()

    }

    private fun initReceivers() {
        if (initialized) return
        initialized = true

        deviceUuid = generateDeviceUuid()

        val mirrorResolution = getBestDisplayResolution()
        val deviceName = DeviceInfoProvider(this).getDeviceName()
        Timber.i("AirPlay mirror advertised resolution: ${mirrorResolution.first}x${mirrorResolution.second}")

        airPlayReceiver = AirPlayReceiver(
            context = this,
            displayName = deviceName,
            mirrorWidth = mirrorResolution.first,
            mirrorHeight = mirrorResolution.second,
            audioEnabled = true,
            videoSurfaceProvider = { com.weekd.miracastreceiver.ui.PlayerActivity.mirrorSurface },
            onStateChanged = { state ->
                Timber.i("AirPlay state: $state")
                if (state == com.weekd.miracastreceiver.airplay.AirPlayState.CONNECTED && !airPlayPlayerStarted) {
                    airPlayPlayerStarted = true
                    val intent = Intent(this, com.weekd.miracastreceiver.ui.PlayerActivity::class.java).apply {
                        putExtra(com.weekd.miracastreceiver.ui.PlayerActivity.EXTRA_MEDIA_TITLE, "iPhone screen mirroring")
                        putExtra(com.weekd.miracastreceiver.ui.PlayerActivity.EXTRA_IS_AIRPLAY_MIRROR, true)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    startActivity(intent)
                } else if (state != com.weekd.miracastreceiver.airplay.AirPlayState.CONNECTED &&
                    airPlayPlayerStarted
                ) {

                    airPlayPlayerStarted = false
                    Timber.i("AirPlay disconnected ($state), closing player")
                    sendBroadcast(Intent(com.weekd.miracastreceiver.ui.PlayerActivity.ACTION_STOP).apply {
                        setPackage(packageName)
                    })
                }
            },
            onSenderNameChanged = { sender -> Timber.i("AirPlay sender: $sender") }
        )

        initWfdServer()

        initDlnaServices()
        val playerStateFilter = IntentFilter().apply {
            addAction(ACTION_UPDATE_POSITION)
            addAction(ACTION_PLAYBACK_STOPPED)
        }

        ContextCompat.registerReceiver(
            this, playerStateReceiver, playerStateFilter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun getBestDisplayResolution(): Pair<Int, Int> {

        var width = resources.displayMetrics.widthPixels
        var height = resources.displayMetrics.heightPixels

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val display = (getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
                ?.getDisplay(Display.DEFAULT_DISPLAY)
            val mode = display?.supportedModes
                ?.maxByOrNull { it.physicalWidth * it.physicalHeight }
                ?: display?.mode
            if (mode != null) {
                width = mode.physicalWidth
                height = mode.physicalHeight
            }
        }
        val longSide = maxOf(width, height).coerceIn(1280, 3840)
        val shortSide = minOf(width, height).coerceIn(720, 2160)
        return longSide to shortSide
    }

    private fun initWfdServer() {
        wfdServer = WfdServer(this, 7236).apply {
            onConnectionRequested = { clientName, clientAddress ->
                Timber.i("Miracast connection requested: $clientName from $clientAddress")
            }
            onConnectionEstablished = { sessionId ->
                Timber.i("Miracast session established: $sessionId")
            }
            onStreamStarted = { rtpPort ->
                Timber.i("Miracast stream started on RTP port: $rtpPort")
            }
            onStreamStopped = {
                Timber.i("Miracast stream stopped, closing player")

                sendBroadcast(Intent(com.weekd.miracastreceiver.ui.PlayerActivity.ACTION_STOP).apply {
                    setPackage(packageName)
                })
            }
        }

        wifiDirectManager = WifiDirectManager(this, DeviceInfoProvider(this).getDeviceName()).apply {
            onDeviceConnected = { device ->
                Timber.i("Miracast device connected: ${device.deviceName}")
            }
        }
    }

    private fun initDlnaServices() {
        val deviceInfoProvider = DeviceInfoProvider(this)
        val localIp = NetworkUtils.getLocalIpAddress() ?: "127.0.0.1"

        dlnaRenderer = DlnaMediaRenderer()

        dlnaRenderer.onSetUri = { uri, metadata ->
            Timber.i("DLNA SetURI: $uri")
            val state = dlnaRenderer.getState()

            val intent = Intent(this, com.weekd.miracastreceiver.ui.PlayerActivity::class.java).apply {
                if (state.playlist.size > 1) {
                    putStringArrayListExtra(
                        com.weekd.miracastreceiver.ui.PlayerActivity.EXTRA_MEDIA_URIS,
                        ArrayList(state.playlist)
                    )
                    putExtra(com.weekd.miracastreceiver.ui.PlayerActivity.EXTRA_START_INDEX, state.currentIndex)
                } else {
                    putExtra(com.weekd.miracastreceiver.ui.PlayerActivity.EXTRA_MEDIA_URI, uri)
                }
                putExtra(com.weekd.miracastreceiver.ui.PlayerActivity.EXTRA_MEDIA_TITLE, extractTitle(metadata))
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
        }

        dlnaRenderer.onPlay = {
            Timber.i("DLNA Play")

            val intent = Intent(com.weekd.miracastreceiver.ui.PlayerActivity.ACTION_PLAY).apply {
                setPackage(packageName)
            }
            sendBroadcast(intent)
        }

        dlnaRenderer.onPause = {
            Timber.i("DLNA Pause")

            val intent = Intent(com.weekd.miracastreceiver.ui.PlayerActivity.ACTION_PAUSE).apply {
                setPackage(packageName)
            }
            sendBroadcast(intent)
        }

        dlnaRenderer.onStop = {
            Timber.i("DLNA Stop")

            val intent = Intent(com.weekd.miracastreceiver.ui.PlayerActivity.ACTION_STOP).apply {
                setPackage(packageName)
            }
            sendBroadcast(intent)
        }

        dlnaRenderer.onSeek = { position ->
            Timber.i("DLNA Seek: $position")

            val intent = Intent(com.weekd.miracastreceiver.ui.PlayerActivity.ACTION_SEEK).apply {
                putExtra(com.weekd.miracastreceiver.ui.PlayerActivity.EXTRA_SEEK_POSITION, position)
                setPackage(packageName)
            }
            sendBroadcast(intent)
        }

        dlnaRenderer.onVolumeChanged = { volume ->
            Timber.i("DLNA Volume: $volume")

            val intent = Intent(com.weekd.miracastreceiver.ui.PlayerActivity.ACTION_SET_VOLUME).apply {
                putExtra(com.weekd.miracastreceiver.ui.PlayerActivity.EXTRA_VOLUME, volume)
                setPackage(packageName)
            }
            sendBroadcast(intent)
        }

        dlnaRenderer.onSpeedChanged = { speed ->
            Timber.i("DLNA Speed: $speed")

            val intent = Intent(com.weekd.miracastreceiver.ui.PlayerActivity.ACTION_SET_SPEED).apply {
                putExtra(com.weekd.miracastreceiver.ui.PlayerActivity.EXTRA_SPEED, speed)
                setPackage(packageName)
            }
            sendBroadcast(intent)
        }

        dlnaRenderer.onQualityUriChanged = { uri ->
            Timber.i("DLNA Quality URI: $uri")

            val intent = Intent(com.weekd.miracastreceiver.ui.PlayerActivity.ACTION_SET_QUALITY_URL).apply {
                putExtra(com.weekd.miracastreceiver.ui.PlayerActivity.EXTRA_QUALITY_URI, uri)
                setPackage(packageName)
            }
            sendBroadcast(intent)
        }

        upnpHttpServer = UpnpHttpServer(
            context = this,
            renderer = dlnaRenderer,
            deviceUuid = deviceUuid,
            deviceName = deviceInfoProvider.getDeviceName(),
            manufacturer = Build.MANUFACTURER,
            modelName = Build.MODEL,
            localIp = localIp,
            port = 8080
        )

        ssdpServer = SsdpServer(
            context = this,
            deviceUuid = deviceUuid,
            localIp = localIp,
            httpPort = 8080
        )
    }

    private fun extractTitle(metadata: String): String {

        val titlePattern = Regex("<dc:title>(.*?)</dc:title>", RegexOption.IGNORE_CASE)
        val match = titlePattern.find(metadata)
        return match?.groupValues?.getOrNull(1) ?: "DLNA cast"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val fromBoot = intent?.getBooleanExtra(EXTRA_FROM_BOOT, false) ?: false
        Timber.i("CastReceiverService started (fromBoot=$fromBoot)")

        startForegroundCompat(fromBoot)
        startWhenNetworkReady()

        return START_STICKY
    }

    private fun startForegroundCompat(fromBoot: Boolean) {
        val type = if (fromBoot) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        }

        ServiceCompat.startForeground(this, NOTIFICATION_ID, createNotification(), type)
    }

    private fun startWhenNetworkReady() {
        networkWaitRunnable?.let { mainHandler.removeCallbacks(it) }
        networkWaitRunnable = null

        if (NetworkUtils.getLocalIpAddress() != null) {
            networkWaitElapsedMs = 0L
            initReceivers()
            startAllServices()
            return
        }

        if (networkWaitElapsedMs >= NETWORK_MAX_WAIT_MS) {
            Timber.w("Network still not ready after ${NETWORK_MAX_WAIT_MS / 1000}s, starting anyway")
            networkWaitElapsedMs = 0L
            initReceivers()
            startAllServices()
            return
        }

        Timber.i("Network not ready yet, retry in ${NETWORK_RETRY_INTERVAL_MS}ms")
        val runnable = Runnable {
            networkWaitElapsedMs += NETWORK_RETRY_INTERVAL_MS
            startWhenNetworkReady()
        }
        networkWaitRunnable = runnable
        mainHandler.postDelayed(runnable, NETWORK_RETRY_INTERVAL_MS)
    }

    private fun startAllServices() {

        airPlayReceiver.start()

        upnpHttpServer.start()
        ssdpServer.start()

        // The sending phone forms the P2P group; this TV stays available as a sink.
        wifiDirectManager.start()
        // Keep the WFD sink advertisement owned by the receiver service. On rooted
        // devices this re-applies the supplicant IE after every service start, so
        // discovery does not depend on a short-lived external ADB helper.
        val advertised = WfdRootHelper.advertiseSink(this, 7236)
        Timber.i("Miracast WFD sink advertisement: ${if (advertised) "active" else "unavailable (root/system permission required)"}")
        wfdServer.start()

        Timber.i("All cast services started (AirPlay + DLNA + Miracast/WFD)")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        Timber.i("Task removed, tearing down cast sessions")
        if (initialized) shutdownMiracast()
        super.onTaskRemoved(rootIntent)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.i("CastReceiverService destroyed")

        networkWaitRunnable?.let { mainHandler.removeCallbacks(it) }
        networkWaitRunnable = null

        if (!initialized) return

        try {
            unregisterReceiver(playerStateReceiver)
        } catch (e: Exception) {
            Timber.e(e, "Error unregistering playerStateReceiver")
        }

        airPlayReceiver.stop()

        ssdpServer.stop()
        upnpHttpServer.stop()

        shutdownMiracast()
    }

    private fun shutdownMiracast() {
        runCatching { wfdServer.stop() }
        runCatching { wifiDirectManager.stop() }
    }

    private fun generateDeviceUuid(): String {

        val deviceId = "${Build.MANUFACTURER}-${Build.MODEL}-${Build.SERIAL}"
        return UUID.nameUUIDFromBytes(deviceId.toByteArray()).toString()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the casting receiver running"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Waiting for AirPlay, DLNA, or Miracast connections")
            .setSmallIcon(R.drawable.ic_stat_miracast)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
}
