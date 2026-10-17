package es.davidrg.rommsync.data.sync

import es.davidrg.rommsync.core.sync.platform.SaveHandler
import es.davidrg.rommsync.core.sync.platform.SaveHandlerRegistry
import es.davidrg.rommsync.core.sync.platform.SaveProfile
import es.davidrg.rommsync.core.sync.platform.SavePaths
import es.davidrg.rommsync.core.sync.platform.SyncSupport
import es.davidrg.rommsync.data.local.SettingsDataStore
import es.davidrg.rommsync.data.local.entity.DownloadedRomEntity
import es.davidrg.rommsync.data.local.entity.PlatformEntity

/**
 * Resolves, for one platform on this device, the emulator, save handler and
 * save base path used by save sync. Shared by the coordinator, the sync
 * preview and the platforms screen so all three agree.
 *
 * Device layout: when the ROM root lives on a removable volume
 * (`/storage/<UUID>/…`, e.g. the AYN Thor's SD card with Cocoon's Games
 * folder) the [SavePaths.android] profile for that volume supplies the default
 * emulator and save paths; on internal storage the upstream defaults apply.
 * Per-game and per-platform path overrides always win.
 */
object SavePathResolver {

    private val REMOVABLE_VOLUME = Regex("^(/storage/[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4})(/.*)?$")

    /** Profile for the volume holding [romsRoot], or null for internal storage. */
    fun deviceProfile(romsRoot: String?): SaveProfile? {
        val root = romsRoot?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val volume = REMOVABLE_VOLUME.find(root)?.groupValues?.get(1) ?: return null
        return SavePaths.android(volume)
    }

    fun deviceProfile(settings: SettingsDataStore): SaveProfile? =
        deviceProfile(settings.getRomsRootPathBlocking())

    /** Emulator id used for [platformSlug] (platform setting, else the profile/upstream default). */
    fun emulatorFor(platformSlug: String, config: PlatformEntity?, profile: SaveProfile?): String =
        config?.emulatorId ?: SaveHandlerRegistry.getDefaultEmulator(platformSlug, profile).id

    fun handlerFor(platformSlug: String, config: PlatformEntity?, profile: SaveProfile?): SaveHandler =
        SaveHandlerRegistry.getHandler(platformSlug, config?.emulatorId, profile)

    fun syncSupport(platformSlug: String, config: PlatformEntity?, profile: SaveProfile?): SyncSupport =
        SaveHandlerRegistry.syncSupport(platformSlug, config?.emulatorId, profile)

    /** Base path for the platform as a whole (no per-game override). */
    fun platformBasePath(
        platformSlug: String,
        config: PlatformEntity?,
        retroArchBase: String,
        profile: SaveProfile?,
    ): String = platformBasePath(platformSlug, config?.emulatorId, config?.savesPathOverride, retroArchBase, profile)

    /**
     * Same as above from the raw platform settings: [emulatorId] null = default
     * emulator, [savesPathOverride] null/blank = the emulator's default path.
     */
    fun platformBasePath(
        platformSlug: String,
        emulatorId: String?,
        savesPathOverride: String?,
        retroArchBase: String,
        profile: SaveProfile?,
    ): String {
        savesPathOverride?.takeIf { it.isNotBlank() }?.let { return it }
        val emulator = emulatorId ?: SaveHandlerRegistry.getDefaultEmulator(platformSlug, profile).id
        // A RetroArch base the user changed away from the upstream default beats the profile row.
        val effectiveProfile = profile.takeUnless {
            emulator == SaveHandlerRegistry.EmulatorId.RETROARCH.id &&
                retroArchBase != SettingsDataStore.DEFAULT_RETROARCH_PATH
        }
        return SaveHandlerRegistry.getDefaultSavesPath(
            emulatorId = emulator,
            platformSlug = platformSlug,
            retroArchBase = retroArchBase,
            profile = effectiveProfile,
        )
    }

    fun romBasePath(
        rom: DownloadedRomEntity,
        config: PlatformEntity?,
        retroArchBase: String,
        profile: SaveProfile?,
    ): String = rom.savesPathOverride?.takeIf { it.isNotBlank() }
        ?: platformBasePath(rom.platformSlug, config, retroArchBase, profile)
}
