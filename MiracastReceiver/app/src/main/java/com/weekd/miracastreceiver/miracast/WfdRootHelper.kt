package com.weekd.miracastreceiver.miracast

import android.content.Context
import timber.log.Timber
import java.io.File

object WfdRootHelper {

    private const val BINARY_NAME = "libwfdctl.so"

    private val CTRL_SOCKET_PATHS = listOf(
        "/data/vendor/wifi/wpa/sockets/p2p0",
        "/data/misc/wifi/sockets/p2p0",
        "/data/vendor/wifi/wpa/sockets/wlan0",
        "/data/misc/wifi/sockets/wlan0"
    )

    private fun subelemHex(controlPort: Int, maxThroughputMbps: Int = 50): String =
        "0006" + "0011" + "%04x".format(controlPort) + "%04x".format(maxThroughputMbps)

    fun advertiseSink(context: Context, controlPort: Int = 7236): Boolean {
        val binary = File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)
        if (!binary.exists()) {
            Timber.w("WFD: $BINARY_NAME not found in nativeLibraryDir")
            return false
        }

        val socketPath = CTRL_SOCKET_PATHS.firstOrNull { runAsRoot("ls $it") }
        if (socketPath == null) {
            Timber.w("WFD: no wpa_supplicant control socket found (no root?)")
            return false
        }

        val cmd = "${binary.absolutePath} $socketPath " +
            "\"SET wifi_display 1\" " +
            "\"WFD_SUBELEM_SET 0 ${subelemHex(controlPort)}\""

        return if (runAsRoot(cmd)) {
            Timber.i("WFD: sink IE injected via $socketPath (control port $controlPort)")
            true
        } else {
            Timber.w("WFD: failed to inject sink IE — device may not be rooted")
            false
        }
    }

    fun stopAdvertising(context: Context): Boolean {
        val binary = File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)
        if (!binary.exists()) return false
        val socketPath = CTRL_SOCKET_PATHS.firstOrNull { runAsRoot("ls $it") } ?: return false
        return runAsRoot("${binary.absolutePath} $socketPath \"SET wifi_display 0\"")
    }

    fun isRootAvailable(): Boolean = runAsRoot("id")

    private fun runAsRoot(command: String): Boolean = try {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        val exit = process.waitFor()
        if (output.isNotEmpty()) Timber.d("WFD root: $output")
        exit == 0
    } catch (e: Exception) {
        Timber.d("WFD root unavailable: ${e.message}")
        false
    }
}
