package com.weekd.miracastreceiver.miracast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import androidx.core.content.ContextCompat
import android.os.Looper
import timber.log.Timber

class WifiDirectManager(
    private val context: Context,
    private val deviceName: String
) {

    private val manager: WifiP2pManager? by lazy {
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    }

    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var isStarted = false

    var onDeviceConnected: ((WifiP2pDevice) -> Unit)? = null
    var onDeviceDisconnected: (() -> Unit)? = null

    fun start() {
        if (isStarted) {
            Timber.w("Wi-Fi Direct already started")
            return
        }

        val p2p = manager
        if (p2p == null) {
            Timber.w("This device does not support Wi-Fi Direct; Miracast is unavailable (AirPlay and DLNA are unaffected)")
            return
        }

        try {
            channel = p2p.initialize(context, Looper.getMainLooper(), null)

            if (channel == null) {
                Timber.e("Failed to initialize Wi-Fi P2P channel")
                return
            }

            registerReceiver()

            setWfdInfo()

            // The Miracast source (for example, a Samsung phone) must form the
            // P2P group. Creating a group or starting service discovery here
            // takes the radio out of sink-listening mode and can hide this TV.

            isStarted = true
            Timber.i("Wi-Fi Direct started for Miracast")
        } catch (e: Exception) {
            Timber.e(e, "Failed to start Wi-Fi Direct")
        }
    }

    private fun setWfdInfo() {
        val ch = channel ?: return

        try {

            val wfdInfoClass = Class.forName("android.net.wifi.p2p.WifiP2pWfdInfo")
            val wfdInfo = wfdInfoClass.newInstance()

            // setWfdEnabled(true)
            val setWfdEnabledMethod = wfdInfoClass.getMethod("setEnabled", Boolean::class.java)
            setWfdEnabledMethod.invoke(wfdInfo, true)

            val setDeviceTypeMethod = wfdInfoClass.getMethod("setDeviceType", Int::class.java)
            setDeviceTypeMethod.invoke(wfdInfo, 1)  // 1 = PRIMARY_SINK

            // setSessionAvailable(true)
            val setSessionAvailableMethod = wfdInfoClass.getMethod("setSessionAvailable", Boolean::class.java)
            setSessionAvailableMethod.invoke(wfdInfo, true)

            val setControlPortMethod = wfdInfoClass.getMethod("setControlPort", Int::class.java)
            setControlPortMethod.invoke(wfdInfo, 7236)

            // setMaxThroughput(50)  // 50 Mbps
            val setMaxThroughputMethod = wfdInfoClass.getMethod("setMaxThroughput", Int::class.java)
            setMaxThroughputMethod.invoke(wfdInfo, 50)

            val setWFDInfoMethod = WifiP2pManager::class.java.getMethod(
                "setWFDInfo",
                WifiP2pManager.Channel::class.java,
                wfdInfoClass,
                WifiP2pManager.ActionListener::class.java
            )

            setWFDInfoMethod.invoke(manager, ch, wfdInfo, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Timber.i("✅ WFD Info set successfully! Windows should be able to discover this device now.")
                }

                override fun onFailure(reason: Int) {
                    val reasonText = when (reason) {
                        WifiP2pManager.ERROR -> "ERROR"
                        WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
                        WifiP2pManager.BUSY -> "BUSY"
                        else -> "UNKNOWN($reason)"
                    }
                    Timber.e("❌ Failed to set WFD Info: $reasonText (system permission required)")
                }
            })

        } catch (e: SecurityException) {
            Timber.e(e, "❌ SecurityException: setWFDInfo requires system permission (CONFIGURE_WIFI_DISPLAY)")
            Timber.e("A system-signed build or root access is required for Windows to discover this device")
        } catch (e: ClassNotFoundException) {
            Timber.e(e, "❌ WifiP2pWfdInfo class not found")
        } catch (e: NoSuchMethodException) {
            Timber.e(e, "❌ setWFDInfo method not found")
        } catch (e: Exception) {
            Timber.e(e, "❌ Error setting WFD Info")
        }
    }

    private fun createGroup() {
        val ch = channel ?: return

        try {
            manager?.createGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Timber.i("Wi-Fi Direct group created successfully")

                    manager?.requestGroupInfo(ch) { group ->
                        if (group != null) {
                            Timber.i("Group created - SSID: ${group.networkName}, Owner: ${group.isGroupOwner}")

                            setDeviceName(deviceName)
                        }
                    }
                }

                override fun onFailure(reason: Int) {
                    val reasonText = when (reason) {
                        WifiP2pManager.ERROR -> "ERROR"
                        WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
                        WifiP2pManager.BUSY -> "BUSY"
                        else -> "UNKNOWN($reason)"
                    }
                    Timber.e("Failed to create Wi-Fi Direct group: $reasonText")

                    if (reason == WifiP2pManager.BUSY) {

                        manager?.requestGroupInfo(ch) { group ->
                            if (group != null) {
                                Timber.i("Existing group found: ${group.networkName}")
                            }
                        }
                    }
                }
            })
        } catch (e: SecurityException) {
            Timber.e(e, "Security exception when creating group - missing permissions?")
        } catch (e: Exception) {
            Timber.e(e, "Exception when creating group")
        }
    }

    private fun setDeviceName(name: String) {
        try {

            val setDeviceNameMethod = WifiP2pManager::class.java.getMethod(
                "setDeviceName",
                WifiP2pManager.Channel::class.java,
                String::class.java,
                WifiP2pManager.ActionListener::class.java
            )

            setDeviceNameMethod.invoke(manager, channel, name, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Timber.i("Device name set to: $name")
                }

                override fun onFailure(reason: Int) {
                    Timber.w("Failed to set device name: $reason")
                }
            })
        } catch (e: Exception) {
            Timber.w(e, "Could not set device name (requires system permissions)")
        }
    }

    private fun registerLocalService() {
        val ch = channel ?: return

        try {

            val record = mapOf(
                "version" to "1.0",
                "type" to "miracast-sink",
                "rtsp_port" to "7236"
            )

            val serviceInfo = WifiP2pDnsSdServiceInfo.newInstance(
                "_miracast", "_tcp", record
            )

            manager?.addLocalService(ch, serviceInfo, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Timber.i("Local Miracast service registered")
                }

                override fun onFailure(reason: Int) {
                    Timber.e("Failed to register local service: $reason")
                }
            })

            manager?.discoverServices(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Timber.i("Service discovery started")
                }

                override fun onFailure(reason: Int) {
                    Timber.e("Failed to start service discovery: $reason")
                }
            })
        } catch (e: SecurityException) {

            Timber.w("P2P local service registration denied (location permission missing); Miracast is unavailable")
        } catch (e: Exception) {
            Timber.e(e, "Error registering local service")
        }
    }

    private fun registerReceiver() {
        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }

        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        val enabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                        Timber.d("Wi-Fi P2P state changed: ${if (enabled) "ENABLED" else "DISABLED"}")
                    }

                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        Timber.d("Wi-Fi P2P peers changed")

                        channel?.let { ch ->
                            try {
                                manager?.requestPeers(ch) { peerList ->
                                    Timber.d("Peers discovered: ${peerList.deviceList.size}")
                                }
                            } catch (e: SecurityException) {
                                Timber.w("requestPeers denied (location permission missing)")
                            }
                        }
                    }

                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        Timber.d("Wi-Fi P2P connection changed")
                        channel?.let { ch ->
                            manager?.requestConnectionInfo(ch) { info ->
                                if (info.groupFormed) {
                                    Timber.i("P2P Group formed - Group Owner: ${info.isGroupOwner}")

                                    if (info.isGroupOwner) {
                                        Timber.i("This device is Group Owner, IP: ${info.groupOwnerAddress?.hostAddress}")
                                    }
                                }
                            }
                        }
                    }

                    WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                        val device = intent.getParcelableExtra<WifiP2pDevice>(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                        Timber.d("This device changed: ${device?.deviceName}, Status: ${device?.status}")
                    }
                }
            }
        }

        try {

            ContextCompat.registerReceiver(
                context, receiver, intentFilter, ContextCompat.RECEIVER_NOT_EXPORTED
            )
            Timber.i("Wi-Fi P2P broadcast receiver registered")
        } catch (e: Exception) {
            Timber.e(e, "Failed to register Wi-Fi P2P receiver")
        }
    }

    fun stop() {
        if (!isStarted) return

        try {

            channel?.let { ch ->
                manager?.clearLocalServices(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Timber.i("Local services cleared")
                    }

                    override fun onFailure(reason: Int) {
                        Timber.w("Failed to clear local services: $reason")
                    }
                })

                manager?.removeGroup(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Timber.i("Wi-Fi Direct group removed")
                    }

                    override fun onFailure(reason: Int) {
                        Timber.w("Failed to remove group: $reason")
                    }
                })
            }

            receiver?.let {
                context.unregisterReceiver(it)
                receiver = null
            }

            channel = null
            isStarted = false
            Timber.i("Wi-Fi Direct stopped")
        } catch (e: Exception) {
            Timber.e(e, "Error stopping Wi-Fi Direct")
        }
    }

    fun isRunning(): Boolean = isStarted
}
