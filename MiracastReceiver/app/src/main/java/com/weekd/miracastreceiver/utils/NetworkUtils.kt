package com.weekd.miracastreceiver.utils

import android.content.Context
import android.os.Build
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import timber.log.Timber
import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {

    fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(network) ?: return false
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

        @Suppress("DEPRECATION")
        return cm.activeNetworkInfo?.isConnected == true
    }

    fun isWifiConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(network) ?: return false
            return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        }

        @Suppress("DEPRECATION")
        return cm.activeNetworkInfo?.type == ConnectivityManager.TYPE_WIFI
    }

    fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses

                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()

                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        val ip = address.hostAddress
                        Timber.d("Found IP address: $ip")
                        return ip
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error getting local IP address")
        }
        return null
    }

    fun getWifiSSID(context: Context): String? {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo = wifiManager.connectionInfo
            return wifiInfo.ssid?.replace("\"", "")
        } catch (e: Exception) {
            Timber.e(e, "Error getting WiFi SSID")
        }
        return null
    }

    fun generateConnectionCode(): String {
        val chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        return (1..6)
            .map { chars.random() }
            .joinToString("")
    }

    fun getMacAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()

                if (networkInterface.isLoopback) continue

                val mac = networkInterface.hardwareAddress
                if (mac != null && mac.isNotEmpty()) {
                    val macAddress = mac.joinToString(":") {
                        String.format("%02X", it)
                    }
                    Timber.d("Found MAC address: $macAddress")
                    return macAddress
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error getting MAC address")
        }

        return generateRandomMacAddress()
    }

    private fun generateRandomMacAddress(): String {
        val mac = ByteArray(6) { (0..255).random().toByte() }

        mac[0] = (mac[0].toInt() or 0x02).toByte()
        return mac.joinToString(":") { String.format("%02X", it) }
    }
}
