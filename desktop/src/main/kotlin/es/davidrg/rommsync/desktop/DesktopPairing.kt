package es.davidrg.rommsync.desktop

import es.davidrg.rommsync.core.auth.DevicePairing
import es.davidrg.rommsync.core.auth.PairedDevice
import es.davidrg.rommsync.core.auth.PairingCode
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** Pairing shared by `--pair` and the GUI settings panel; persists the result in [DesktopConfig]. */
object DesktopPairing {

    const val PLATFORM = "linux"

    fun defaultDeviceName(): String =
        (System.getenv("HOSTNAME") ?: runCatching { InetAddress.getLocalHost().hostName }.getOrNull())
            ?.takeIf { it.isNotBlank() && it != "localhost" }
            ?: "RomM Sync Desktop"

    /**
     * Runs the RomM device authorization flow against [baseUrl] and stores the
     * bound token, device id, name and server. Throws
     * [es.davidrg.rommsync.core.auth.PairingExpiredException] /
     * [es.davidrg.rommsync.core.auth.PairingDeniedException] or I/O errors.
     */
    suspend fun pair(baseUrl: String, name: String, onCode: (PairingCode) -> Unit): PairedDevice {
        val url = baseUrl.trim().trimEnd('/')
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        val device = DevicePairing(client).pair(
            baseUrl = url,
            identifier = DesktopConfig.deviceIdentifier,
            name = name,
            platform = PLATFORM,
            clientVersion = DesktopConfig.appVersion,
            onCode = onCode,
        )
        DesktopConfig.savePairing(url, name, device.token, device.deviceId)
        return device
    }

    /**
     * Re-sends the device capabilities (remote install/uninstall, collection sync) for
     * the stored pairing, so a device paired by an older build gains the new ones.
     */
    suspend fun advertiseCapabilities() {
        val config = DesktopConfig
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        DevicePairing(client).advertiseCapabilities(
            baseUrl = config.pairedBaseUrl,
            device = PairedDevice(config.pairedToken, config.pairedDeviceId, null),
            platform = PLATFORM,
            clientVersion = DesktopConfig.appVersion,
        )
    }
}
