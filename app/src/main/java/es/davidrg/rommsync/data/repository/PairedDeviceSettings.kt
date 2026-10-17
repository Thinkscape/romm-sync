package es.davidrg.rommsync.data.repository

/**
 * A RomM device pairing persisted on this install.
 *
 * [token] is the device-bound `rmm_…` credential used as Bearer for every API
 * call while paired; [deviceId] is the RomM Device row it is bound to (used for
 * sync negotiate and install claims instead of registering a new device).
 */
data class PairedDeviceSettings(
    val baseUrl: String,
    val token: String,
    val deviceId: String,
    val deviceName: String,
)
