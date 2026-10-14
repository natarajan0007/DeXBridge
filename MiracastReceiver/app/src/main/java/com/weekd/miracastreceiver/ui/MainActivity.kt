package com.weekd.miracastreceiver.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.weekd.miracastreceiver.R
import com.weekd.miracastreceiver.discovery.DeviceInfoProvider
import com.weekd.miracastreceiver.discovery.MdnsAdvertiser
import com.weekd.miracastreceiver.service.CastReceiverService
import com.weekd.miracastreceiver.util.AppSettings
import com.weekd.miracastreceiver.utils.NetworkUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import android.widget.TextView

class MainActivity : AppCompatActivity() {

    private lateinit var mdnsAdvertiser: MdnsAdvertiser
    private lateinit var deviceInfoProvider: DeviceInfoProvider

    private lateinit var tvDeviceName: TextView
    private lateinit var tvDeviceIp: TextView
    private lateinit var tvConnectionCode: TextView
    private lateinit var tvStatus: TextView
    private lateinit var switchAutoStart: SwitchCompat

    private var connectionCode: String = ""

    private var wifiPermissionPending = false
    private var overlayPromptShown = false

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 1001

        private val WIFI_DIRECT_PERMISSIONS: Array<String>
            get() = if (Build.VERSION.SDK_INT >= 33) {
                arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
            } else {

                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initServices()
        initViews()
        requestWifiDirectPermissions()
        checkNetworkAndStart()
    }

    private fun requestWifiDirectPermissions() {
        val missing = WIFI_DIRECT_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            Timber.i("Requesting Wi-Fi Direct permissions: $missing")
            wifiPermissionPending = true
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_CODE_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CODE_PERMISSIONS) return
        wifiPermissionPending = false

        val granted = grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        if (granted) {

            Timber.i("Wi-Fi Direct permissions granted, restarting cast service")
            startCastService()
        } else {
            Timber.w("Wi-Fi Direct permissions denied — Miracast unavailable")
            tvStatus.text = "Location permission not granted. Windows wireless display is unavailable."
        }
    }

    override fun onResume() {
        super.onResume()
        if (!wifiPermissionPending) promptOverlayPermissionIfNeeded()
    }

    private fun promptOverlayPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (overlayPromptShown || Settings.canDrawOverlays(this)) return
        overlayPromptShown = true

        AlertDialog.Builder(this)
            .setTitle("Allow display over other apps")
            .setMessage(
                "When this app is not open (for example, after the TV starts or you return to the home screen), " +
                    "the system may prevent the cast from appearing.\n\nAllow this app to display over other apps on the next screen."
            )
            .setPositiveButton("Open settings") { _, _ -> openOverlaySettings() }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun openOverlaySettings() {
        val withPackage = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )

        val intents = listOf(withPackage, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (e: ActivityNotFoundException) {
                Timber.w("Overlay settings not available: ${intent.data}")
            }
        }
        Timber.w("No overlay permission settings page on this device")
        Toast.makeText(
            this,
            "This device has no settings page for this permission. Run this ADB command instead: appops set $packageName SYSTEM_ALERT_WINDOW allow",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun initServices() {
        deviceInfoProvider = DeviceInfoProvider(this)
        mdnsAdvertiser = MdnsAdvertiser(this)

        connectionCode = NetworkUtils.generateConnectionCode()
    }

    private fun initViews() {
        tvDeviceName = findViewById(R.id.tv_device_name)
        tvDeviceIp = findViewById(R.id.tv_device_ip)
        tvConnectionCode = findViewById(R.id.tv_connection_code)
        tvStatus = findViewById(R.id.tv_status)
        switchAutoStart = findViewById(R.id.switch_auto_start)

        switchAutoStart.isChecked = AppSettings.isAutoStartOnBoot(this)
        switchAutoStart.setOnCheckedChangeListener { _, isChecked ->
            AppSettings.setAutoStartOnBoot(this, isChecked)
            Timber.i("Auto start on boot set to $isChecked")
        }

        val deviceName = deviceInfoProvider.getDeviceName()
        tvDeviceName.text = deviceName

        tvConnectionCode.text = getString(R.string.connection_code, connectionCode)
    }

    private fun checkNetworkAndStart() {
        if (!NetworkUtils.isNetworkAvailable(this)) {
            tvStatus.text = "Network is unavailable"
            Timber.w("Network not available")
            return
        }

        if (!NetworkUtils.isWifiConnected(this)) {
            tvStatus.text = "Not connected to Wi-Fi"
            Timber.w("WiFi not connected")
        }

        val ipAddress = NetworkUtils.getLocalIpAddress()
        if (ipAddress != null) {
            tvDeviceIp.text = getString(R.string.device_ip, ipAddress)
            Timber.i("Local IP: $ipAddress")
        } else {
            tvDeviceIp.text = getString(R.string.device_ip, "Loading…")
        }

        startCastService()
        startAdvertising()

        updateStatus()
    }

    private fun startCastService() {
        val intent = Intent(this, CastReceiverService::class.java)
        startService(intent)
        Timber.i("Cast receiver service started")
    }

    private fun startAdvertising() {
        val deviceInfo = deviceInfoProvider.getDeviceInfo()
        val deviceName = deviceInfoProvider.getDeviceName()
        val macAddress = NetworkUtils.getMacAddress()

        // AirPlay is advertised by CastReceiverService/AirPlayReceiver only.
        // Do not register a second _airplay._tcp service here: duplicate AirPlay mDNS
        // records with different TXT capabilities can make iOS connect with the wrong
        // protocol path and never send the mirroring video SETUP.

        mdnsAdvertiser.startAdvertising(
            serviceName = deviceName,
            port = 8080,
            deviceInfo = deviceInfo + ("code" to connectionCode)
        )

        Timber.i("Started AirPlay and Miracast advertising: $deviceName")
    }

    private fun updateStatus() {
        lifecycleScope.launch {
            while (true) {
                if (mdnsAdvertiser.isAdvertising()) {
                    tvStatus.text = getString(R.string.waiting_connection)
                } else {
                    tvStatus.text = "Starting receiver…"
                }
                delay(2000)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mdnsAdvertiser.stopAdvertising()
        Timber.i("MainActivity destroyed")
    }
}
