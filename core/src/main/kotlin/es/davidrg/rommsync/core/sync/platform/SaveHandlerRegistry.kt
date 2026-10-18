package es.davidrg.rommsync.core.sync.platform

import es.davidrg.rommsync.core.i18n.tr
import java.io.File

/** Whether saves of a platform/emulator pair can be synced, and why not. */
sealed interface SyncSupport {
    data object Supported : SyncSupport
    data class Unsupported(val reason: String) : SyncSupport
}

/**
 * Registry que selecciona el [SaveHandler] correcto según la plataforma
 * y el emulador configurado.
 *
 * Orden de resolución:
 * 1. Si la plataforma tiene un emulador específico configurado, se usa su handler.
 * 2. Si no, se aplica un mapeo por defecto según el slug de la plataforma.
 * 3. Fallback: RetroArchSaveHandler.
 *
 * Defaults depend on the device layout when a [SaveProfile] is passed: a
 * [SaveHost.LINUX] (EmuDeck) profile uses the standalone emulators EmuDeck
 * installs (DuckStation, PCSX2, RPCS3); an [SaveHost.ANDROID] profile keeps the
 * upstream Android defaults (psx on RetroArch: DuckStation for Android is
 * Play-Store only and not installed on the Thor).
 */
object SaveHandlerRegistry {

    private val retroArchHandler = RetroArchSaveHandler()
    private val melonDsHandler = MelonDsSaveHandler()
    private val ppssppHandler = PpssppSaveHandler()
    private val ps2Handler = Ps2SaveHandler()
    private val ps3Handler = Ps3SaveHandler()
    private val dolphinHandler = DolphinSaveHandler()
    private val switchHandler = SwitchSaveHandler()
    private val n3dsHandler = N3dsSaveHandler()
    private val cemuHandler = CemuSaveHandler()
    private val androidHandler = AndroidSaveHandler()
    private val duckStationHandler = DuckStationSaveHandler()
    private val vita3kHandler = Vita3KSaveHandler()

    /**
     * Emuladores soportados con sus IDs para configuración por plataforma.
     */
    enum class EmulatorId(val id: String, private val brand: String? = null) {
        RETROARCH("retroarch", "RetroArch"),
        MELONDS("melonds", "melonDS"),
        PPSSPP("ppsspp", "PPSSPP"),
        AETHERSX2("aethersx2", "AetherSX2/NetherSX2"),
        ARMSX2("armsx2", "ARMSX2"),
        ARMSX3("armsx3", "ARMSX3 (PS3)"),
        DOLPHIN("dolphin", "Dolphin"),
        EDEN("eden", "Eden"),
        AZAHAR("azahar", "Azahar"),
        CEMU("cemu", "Cemu"),
        ANDROID_NATIVE("android_native"),
        DUCKSTATION("duckstation", "DuckStation"),
        VITA3K("vita3k", "Vita3K"),

        /** PCSX2 (Linux) with folder memory cards; same layout as AetherSX2's. */
        PCSX2("pcsx2", "PCSX2"),
        RPCS3("rpcs3", "RPCS3"),
        FLYCAST("flycast", "Flycast"),
        REDREAM("redream", "Redream"),
        XEMU("xemu", "Xemu"),
        XENIA("xenia", "Xenia"),
        MAME("mame", "MAME"),
        FBNEO("fbneo", "FBNeo"),
        SCUMMVM("scummvm", "ScummVM"),
        ;

        val displayName: String get() = brand ?: tr("emulator.$id")
    }

    /**
     * Devuelve los emuladores disponibles para un slug de plataforma dado.
     * Se usa para poblar el selector en la UI de configuración por plataforma.
     */
    fun getAvailableEmulators(platformSlug: String): List<EmulatorId> {
        return when (platformSlug.lowercase()) {
            "nds", "ds" -> listOf(EmulatorId.MELONDS, EmulatorId.RETROARCH)
            "psp" -> listOf(EmulatorId.PPSSPP, EmulatorId.RETROARCH)
            in PSX_SLUGS -> listOf(EmulatorId.DUCKSTATION, EmulatorId.RETROARCH)
            "ps2" -> listOf(EmulatorId.AETHERSX2, EmulatorId.ARMSX2, EmulatorId.PCSX2, EmulatorId.RETROARCH)
            "ps3" -> listOf(EmulatorId.ARMSX3, EmulatorId.RPCS3, EmulatorId.RETROARCH)
            in VITA_SLUGS -> listOf(EmulatorId.VITA3K)
            "gc", "gamecube", "ngc" -> listOf(EmulatorId.DOLPHIN, EmulatorId.RETROARCH)
            "wii" -> listOf(EmulatorId.DOLPHIN, EmulatorId.RETROARCH)
            in N3DS_SLUGS -> listOf(EmulatorId.AZAHAR, EmulatorId.RETROARCH)
            in WIIU_SLUGS -> listOf(EmulatorId.CEMU)
            "switch" -> listOf(EmulatorId.EDEN)
            in DREAMCAST_SLUGS -> listOf(EmulatorId.FLYCAST, EmulatorId.REDREAM, EmulatorId.RETROARCH)
            in XBOX_SLUGS -> listOf(EmulatorId.XEMU)
            in XBOX360_SLUGS -> listOf(EmulatorId.XENIA)
            in ARCADE_SLUGS -> listOf(EmulatorId.MAME, EmulatorId.FBNEO, EmulatorId.RETROARCH)
            "scummvm" -> listOf(EmulatorId.SCUMMVM, EmulatorId.RETROARCH)
            in ANDROID_SLUGS -> listOf(EmulatorId.ANDROID_NATIVE)
            else -> listOf(EmulatorId.RETROARCH)
        }
    }

    /**
     * Devuelve el emulador por defecto para un slug de plataforma.
     *
     * @param profile device layout; null keeps the upstream Android defaults.
     */
    fun getDefaultEmulator(platformSlug: String, profile: SaveProfile? = null): EmulatorId {
        val slug = platformSlug.lowercase()
        when (profile?.host) {
            SaveHost.LINUX -> when (slug) {
                in PSX_SLUGS -> return EmulatorId.DUCKSTATION
                "ps2" -> return EmulatorId.PCSX2
                "ps3" -> return EmulatorId.RPCS3
            }
            SaveHost.ANDROID, null -> Unit
        }
        return when (slug) {
            "nds", "ds" -> EmulatorId.MELONDS
            "psp" -> EmulatorId.PPSSPP
            "ps2" -> EmulatorId.AETHERSX2
            "ps3" -> EmulatorId.ARMSX3
            in VITA_SLUGS -> EmulatorId.VITA3K
            "gc", "gamecube", "ngc", "wii" -> EmulatorId.DOLPHIN
            in N3DS_SLUGS -> EmulatorId.AZAHAR
            in WIIU_SLUGS -> EmulatorId.CEMU
            "switch" -> EmulatorId.EDEN
            in DREAMCAST_SLUGS -> EmulatorId.FLYCAST
            in XBOX_SLUGS -> EmulatorId.XEMU
            in XBOX360_SLUGS -> EmulatorId.XENIA
            in ARCADE_SLUGS -> EmulatorId.MAME
            "scummvm" -> EmulatorId.SCUMMVM
            in ANDROID_SLUGS -> EmulatorId.ANDROID_NATIVE
            else -> EmulatorId.RETROARCH
        }
    }

    /**
     * Whether saves for [platformSlug] run on [emulatorId] (null = the
     * default for [profile]) can be synced between devices. Unsupported pairs
     * keep their saves in a shape no per-game file can represent; callers
     * show the reason instead of syncing.
     */
    fun syncSupport(platformSlug: String, emulatorId: String?, profile: SaveProfile? = null): SyncSupport {
        val slug = platformSlug.lowercase()
        val effective = emulatorId ?: getDefaultEmulator(slug, profile).id
        UNSUPPORTED_EMULATORS[effective]?.let { return SyncSupport.Unsupported(it) }
        return when (slug) {
            in XBOX_SLUGS -> SyncSupport.Unsupported(UNSUPPORTED_EMULATORS.getValue(EmulatorId.XEMU.id))
            in XBOX360_SLUGS -> SyncSupport.Unsupported(UNSUPPORTED_EMULATORS.getValue(EmulatorId.XENIA.id))
            in ARCADE_SLUGS -> SyncSupport.Unsupported(UNSUPPORTED_EMULATORS.getValue(EmulatorId.MAME.id))
            in DREAMCAST_SLUGS -> SyncSupport.Unsupported(UNSUPPORTED_EMULATORS.getValue(EmulatorId.FLYCAST.id))
            "scummvm" -> SyncSupport.Unsupported(UNSUPPORTED_EMULATORS.getValue(EmulatorId.SCUMMVM.id))
            else -> SyncSupport.Supported
        }
    }

    /**
     * Resuelve el handler correcto para una plataforma con un emulador
     * (opcionalmente) configurado por el usuario.
     */
    fun getHandler(platformSlug: String, emulatorId: String?, profile: SaveProfile? = null): SaveHandler {
        if (syncSupport(platformSlug, emulatorId, profile) is SyncSupport.Unsupported) return UnsupportedSaveHandler
        val effective = emulatorId ?: getDefaultEmulator(platformSlug, profile).id
        return when (effective) {
            EmulatorId.MELONDS.id -> melonDsHandler
            EmulatorId.PPSSPP.id -> ppssppHandler
            EmulatorId.AETHERSX2.id -> ps2Handler
            EmulatorId.ARMSX2.id -> ps2Handler
            EmulatorId.PCSX2.id -> ps2Handler
            EmulatorId.ARMSX3.id -> ps3Handler
            EmulatorId.RPCS3.id -> ps3Handler
            EmulatorId.DOLPHIN.id -> dolphinHandler
            EmulatorId.EDEN.id -> switchHandler
            EmulatorId.AZAHAR.id -> n3dsHandler
            EmulatorId.CEMU.id -> cemuHandler
            EmulatorId.DUCKSTATION.id -> duckStationHandler
            EmulatorId.VITA3K.id -> vita3kHandler
            EmulatorId.ANDROID_NATIVE.id -> androidHandler
            EmulatorId.RETROARCH.id -> retroArchHandler
            else -> retroArchHandler
        }
    }

    /**
     * Devuelve la ruta de saves por defecto para un emulador y plataforma.
     *
     * La plataforma es necesaria porque algunos emuladores (Dolphin) usan
     * rutas distintas segun la plataforma: GameCube guarda en `.../files/GC`
     * mientras que Wii guarda en `.../files/Wii/title`.
     *
     * When [profile] has a row for the emulator, that row wins (see
     * [SaveProfile.handlerBasePath]).
     */
    fun getDefaultSavesPath(
        emulatorId: String,
        platformSlug: String,
        retroArchBase: String,
        profile: SaveProfile? = null,
    ): String {
        profile?.handlerBasePath(emulatorId, platformSlug)?.let { return it }
        return when (emulatorId) {
            EmulatorId.MELONDS.id -> MelonDsSaveHandler.DEFAULT_SAVES_PATH
            EmulatorId.PPSSPP.id -> PpssppSaveHandler.DEFAULT_SAVES_PATH
            EmulatorId.AETHERSX2.id -> Ps2SaveHandler.DEFAULT_SAVES_PATH
            EmulatorId.ARMSX2.id -> Ps2SaveHandler.ARMSX2_SAVES_PATH
            EmulatorId.PCSX2.id -> PCSX2_LINUX_MEMCARDS
            EmulatorId.ARMSX3.id -> Ps3SaveHandler.DEFAULT_SAVES_PATH
            EmulatorId.RPCS3.id -> RPCS3_LINUX_DEV_HDD0
            EmulatorId.DOLPHIN.id -> if (platformSlug.lowercase() in DolphinSaveHandler.GC_SLUGS) {
                DolphinSaveHandler.DEFAULT_GC_SAVES_PATH
            } else {
                DolphinSaveHandler.DEFAULT_WII_SAVES_PATH
            }
            EmulatorId.EDEN.id -> SwitchSaveHandler.DEFAULT_SAVES_PATH
            EmulatorId.AZAHAR.id -> N3dsSaveHandler.DEFAULT_SAVES_PATH
            EmulatorId.CEMU.id -> CemuSaveHandler.DEFAULT_SAVES_PATH
            EmulatorId.DUCKSTATION.id -> DuckStationSaveHandler.DEFAULT_SAVES_PATH
            EmulatorId.VITA3K.id -> Vita3KSaveHandler.DEFAULT_SAVES_PATH
            EmulatorId.ANDROID_NATIVE.id -> AndroidSaveHandler.DEFAULT_SAVES_PATH
            else -> retroArchBase
        }
    }

    /** Handler for unsupported pairs: never finds saves, never writes. */
    private object UnsupportedSaveHandler : SaveHandler {
        override suspend fun findSaves(
            romId: Int,
            romFileName: String,
            platformSlug: String,
            savesBasePath: String,
            romLocalPath: String?,
        ): List<LocalSave> = emptyList()

        override suspend fun extractDownload(
            tempFile: File,
            romFileName: String,
            platformSlug: String,
            savesBasePath: String,
            targetFileName: String,
            romLocalPath: String?,
        ): Boolean = false
    }

    /** Human-readable reason per emulator whose saves are not per-game files. */
    private val UNSUPPORTED_EMULATORS = mapOf(
        EmulatorId.XEMU.id to "Xemu HDD image: all Xbox saves live in one disk image",
        EmulatorId.XENIA.id to "Xenia content folder: saves are per profile, not per game file",
        EmulatorId.FLYCAST.id to "no shared save format: Flycast keeps saves in shared VMU images",
        EmulatorId.REDREAM.id to "no shared save format: Redream keeps saves in shared VMU images",
        EmulatorId.MAME.id to "arcade nvram is per machine, not a per-game save file",
        EmulatorId.FBNEO.id to "arcade nvram is per machine, not a per-game save file",
        EmulatorId.SCUMMVM.id to "ScummVM saves are named after the engine target, not the game file",
    )

    /** Linux PCSX2 (non-flatpak) memory card folder; EmuDeck uses the EMUDECK profile row. */
    private val PCSX2_LINUX_MEMCARDS = "${System.getProperty("user.home")}/.config/PCSX2/memcards"

    /** Linux RPCS3 (AppImage) dev_hdd0; EmuDeck uses the EMUDECK profile row. */
    private val RPCS3_LINUX_DEV_HDD0 = "${System.getProperty("user.home")}/.config/rpcs3/dev_hdd0"

    /** Slugs de plataforma (ES-DE / RomM) que corresponden a Nintendo 3DS. */
    private val N3DS_SLUGS = setOf("3ds", "n3ds", "nintendo-3ds", "nintendo_3ds")

    /** Slugs de plataforma (ES-DE / RomM) que corresponden a Wii U. */
    private val WIIU_SLUGS = setOf("wiiu", "wii-u", "wii_u", "nintendo-wii-u")

    /** Slugs de plataforma que corresponden a juegos nativos Android. */
    private val ANDROID_SLUGS = setOf("android", "android-games")

    private val PSX_SLUGS = setOf("psx", "ps", "ps1", "playstation")
    private val VITA_SLUGS = setOf("psvita", "vita", "ps-vita")
    private val DREAMCAST_SLUGS = setOf("dc", "dreamcast", "naomi", "naomi2", "atomiswave")
    private val XBOX_SLUGS = setOf("xbox")
    private val XBOX360_SLUGS = setOf("xbox360", "xbox-360")
    private val ARCADE_SLUGS = setOf("arcade", "mame", "fbneo", "cps1", "cps2", "cps3")
}
