package es.davidrg.rommsync.core.sync.platform

import es.davidrg.rommsync.core.sync.platform.SaveHandlerRegistry.EmulatorId
import java.io.File

/** Operating system family a [SaveProfile] describes; selects per-platform default emulators. */
enum class SaveHost { ANDROID, LINUX }

/**
 * Where one emulator keeps its saves and (optionally) its save states on a device.
 * Both are the folders the emulator itself writes to; [SaveProfile.handlerBasePath]
 * derives the path a [SaveHandler] expects from them.
 */
data class EmulatorPaths(val saves: String, val states: String? = null)

/**
 * Default save locations for one device layout, keyed by [EmulatorId.id].
 * The settings UI pre-fills its per-platform paths from a profile; every path
 * stays user-editable. Emulators without a row have no default here.
 */
data class SaveProfile(
    val name: String,
    val host: SaveHost,
    val paths: Map<String, EmulatorPaths>,
) {
    /**
     * Path to hand to [SaveHandler] methods as `savesBasePath` for [emulatorId]
     * on [platformSlug], or null when the profile has no row for it.
     *
     * - RetroArch's handler wants the folder holding `saves/` and `states/`.
     * - Dolphin's handler wants `GC` for GameCube and `Wii/title` for Wii; the
     *   profile stores `GC` and Wii is its sibling on every layout we know.
     */
    fun handlerBasePath(emulatorId: String, platformSlug: String): String? {
        val row = paths[emulatorId] ?: return null
        return when (emulatorId) {
            EmulatorId.RETROARCH.id -> File(row.saves).parent ?: row.saves
            EmulatorId.DOLPHIN.id ->
                if (platformSlug.lowercase() in DolphinSaveHandler.GC_SLUGS) {
                    row.saves
                } else {
                    File(File(row.saves).parentFile, "Wii/title").path
                }
            else -> row.saves
        }
    }
}

/**
 * Named save-path profiles for the two device families this fork deploys to.
 */
object SavePaths {

    /** EmuDeck's saves root on the Steam Deck's SD card (`<ROMDirectory>/../saves`). */
    const val EMUDECK_SAVES_ROOT = "/run/media/deck/arcade/Emulation/saves"

    /** Dolphin flatpak user directory, relative to `$HOME` (holds `GC/` and `Wii/`). */
    const val DOLPHIN_FLATPAK_USER_DIR = ".var/app/org.DolphinEmu.dolphin-emu/data/dolphin-emu"

    /** EmuDeck layout on the Steam Deck. */
    val EMUDECK: SaveProfile = emudeck()

    /**
     * EmuDeck (Linux) layout under [savesRoot] = `Emulation/saves`. EmuDeck
     * makes every `<emu>/saves` a real folder or a symlink into the emulator's
     * own tree; verified on the Deck:
     * - `retroarch/{saves,states}` → RetroArch flatpak config dirs, flat
     *   (`sort_savefiles_enable=false`, `sort_savestates_enable=false`);
     * - `ppsspp/{saves,states}` → `PSP/SAVEDATA`, `PSP/PPSSPP_STATE`;
     * - Dolphin: the flatpak's own `~/.var/app/org.DolphinEmu.dolphin-emu/data/dolphin-emu/{GC,Wii}`
     *   (GCI folders `GC/<USA|EUR|JAP>/Card A/`); EmuDeck's `dolphin/{GC,Wii}` are
     *   separate folders Dolphin does not write to, used only when the flatpak
     *   data dir does not exist;
     * - `pcsx2/{saves,states}` = PCSX2 `MemoryCards`/`Savestates` folders;
     * - `duckstation/{saves,states}` = DuckStation memory card / state folders
     *   (memory cards `PerGameTitle`);
     * - `melonds/{saves,states}`;
     * - `azahar/saves` → `storage/azahar/sdmc`, `azahar/states` → `~/.local/share/azahar-emu/states`;
     * - `eden/saves` → `storage/eden/nand/user/save`;
     * - `rpcs3/saves` → `storage/rpcs3/dev_hdd0/home/00000001/savedata`;
     * - `Vita3K/saves` → `storage/Vita3K/ux0/user/00/savedata`;
     * - `Cemu/saves` → `roms/wiiu/mlc01/usr/save`.
     */
    fun emudeck(
        savesRoot: String = EMUDECK_SAVES_ROOT,
        home: String = System.getProperty("user.home"),
    ): SaveProfile {
        fun p(relative: String) = File(savesRoot, relative).path
        val dolphinFlatpak = File(home, DOLPHIN_FLATPAK_USER_DIR)
        val dolphinGc = if (dolphinFlatpak.isDirectory) File(dolphinFlatpak, "GC").path else p("dolphin/GC")
        return SaveProfile(
            name = "EMUDECK",
            host = SaveHost.LINUX,
            paths = mapOf(
                EmulatorId.RETROARCH.id to EmulatorPaths(p("retroarch/saves"), p("retroarch/states")),
                EmulatorId.PPSSPP.id to EmulatorPaths(p("ppsspp/saves"), p("ppsspp/states")),
                EmulatorId.DOLPHIN.id to EmulatorPaths(dolphinGc),
                EmulatorId.PCSX2.id to EmulatorPaths(p("pcsx2/saves"), p("pcsx2/states")),
                EmulatorId.DUCKSTATION.id to EmulatorPaths(p("duckstation/saves"), p("duckstation/states")),
                EmulatorId.MELONDS.id to EmulatorPaths(p("melonds/saves"), p("melonds/states")),
                EmulatorId.AZAHAR.id to EmulatorPaths(p("azahar/saves/Nintendo 3DS"), p("azahar/states")),
                EmulatorId.EDEN.id to EmulatorPaths(p("eden/saves")),
                EmulatorId.RPCS3.id to EmulatorPaths(p("rpcs3/saves")),
                EmulatorId.VITA3K.id to EmulatorPaths(p("Vita3K/saves")),
                EmulatorId.CEMU.id to EmulatorPaths(p("Cemu/saves/00050000")),
            ),
        )
    }

    /** Android primary (internal) shared storage. */
    const val ANDROID_INTERNAL_ROOT = "/storage/emulated/0"

    /**
     * Android layout for a device whose emulation data lives on the volume
     * [sdRoot] (the Thor's SD card, e.g. `/storage/EBD7-FF2C`). Verified on the
     * Thor with `adb ls`:
     * - emulators whose data folder was chosen on the card keep it there:
     *   RetroArch `RetroArch/`, melonDS `melonDS/`, PPSSPP memstick `PPSSPP/`,
     *   Azahar user dir `Azahar/`, Vita3K `Vita3K/`, DuckStation `duckstation/`,
     *   ARMSX2 (the PS2 default) `ARMSX2/` with folder cards in `memcards/`;
     * - Dolphin, Eden and NetherSX2 keep their app-specific
     *   `Android/data/<pkg>/files` tree on internal storage [internalRoot]
     *   (unreadable on Android 13 without root).
     */
    fun android(sdRoot: String, internalRoot: String = ANDROID_INTERNAL_ROOT): SaveProfile {
        fun sd(relative: String) = File(sdRoot, relative).path
        fun internal(relative: String) = File(internalRoot, relative).path
        return SaveProfile(
            name = "ANDROID",
            host = SaveHost.ANDROID,
            paths = mapOf(
                EmulatorId.RETROARCH.id to EmulatorPaths(sd("RetroArch/saves"), sd("RetroArch/states")),
                EmulatorId.DUCKSTATION.id to EmulatorPaths(sd("duckstation/memcards"), sd("duckstation/savestates")),
                EmulatorId.AETHERSX2.id to EmulatorPaths(
                    internal("Android/data/xyz.aethersx2.android/files/memcards"),
                    internal("Android/data/xyz.aethersx2.android/files/sstates"),
                ),
                EmulatorId.ARMSX2.id to EmulatorPaths(sd("ARMSX2/memcards"), sd("ARMSX2/sstates")),
                EmulatorId.MELONDS.id to EmulatorPaths(sd("melonDS/saves"), sd("melonDS/states")),
                EmulatorId.PPSSPP.id to EmulatorPaths(sd("PPSSPP/PSP/SAVEDATA"), sd("PPSSPP/PSP/PPSSPP_STATE")),
                EmulatorId.DOLPHIN.id to EmulatorPaths(internal("Android/data/org.dolphinemu.dolphinemu/files/GC")),
                EmulatorId.AZAHAR.id to EmulatorPaths(sd("Azahar/sdmc/Nintendo 3DS")),
                EmulatorId.EDEN.id to EmulatorPaths(internal("Android/data/dev.eden.eden_emulator/files/nand/user/save")),
                EmulatorId.VITA3K.id to EmulatorPaths(sd("Vita3K/ux0/user/00/savedata")),
            ),
        )
    }
}
