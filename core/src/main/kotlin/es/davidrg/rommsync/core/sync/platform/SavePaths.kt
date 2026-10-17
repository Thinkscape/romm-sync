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

    /** EmuDeck layout on the Steam Deck. */
    val EMUDECK: SaveProfile = emudeck()

    /**
     * EmuDeck (Linux) layout under [savesRoot] = `Emulation/saves`. EmuDeck
     * makes every `<emu>/saves` a real folder or a symlink into the emulator's
     * own tree; verified on the Deck:
     * - `retroarch/{saves,states}` → RetroArch flatpak config dirs, flat
     *   (`sort_savefiles_enable=false`, `sort_savestates_enable=false`);
     * - `ppsspp/{saves,states}` → `PSP/SAVEDATA`, `PSP/PPSSPP_STATE`;
     * - `dolphin/{GC,Wii}` real folders (`Wii/title/00010000/<id>/data`);
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
    fun emudeck(savesRoot: String = EMUDECK_SAVES_ROOT): SaveProfile {
        fun p(relative: String) = File(savesRoot, relative).path
        return SaveProfile(
            name = "EMUDECK",
            host = SaveHost.LINUX,
            paths = mapOf(
                EmulatorId.RETROARCH.id to EmulatorPaths(p("retroarch/saves"), p("retroarch/states")),
                EmulatorId.PPSSPP.id to EmulatorPaths(p("ppsspp/saves"), p("ppsspp/states")),
                EmulatorId.DOLPHIN.id to EmulatorPaths(p("dolphin/GC")),
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

    /**
     * Android layout with all emulation data on the volume [sdRoot] (the
     * Thor's SD card, e.g. `/storage/EBD7-FF2C`; `/storage/emulated/0` gives
     * upstream's internal-storage defaults). Emulators that let the user pick a
     * data folder use `<sdRoot>/<Emu>/`; the rest keep their app-specific
     * `Android/data/<pkg>/files` tree on that volume (reached through SAF on
     * an unrooted device).
     */
    fun android(sdRoot: String): SaveProfile {
        fun p(relative: String) = File(sdRoot, relative).path
        return SaveProfile(
            name = "ANDROID",
            host = SaveHost.ANDROID,
            paths = mapOf(
                EmulatorId.RETROARCH.id to EmulatorPaths(p("RetroArch/saves"), p("RetroArch/states")),
                EmulatorId.DUCKSTATION.id to EmulatorPaths(p("duckstation/memcards"), p("duckstation/savestates")),
                EmulatorId.AETHERSX2.id to EmulatorPaths(p("NetherSX2/memcards"), p("NetherSX2/sstates")),
                EmulatorId.MELONDS.id to EmulatorPaths(p("melonDS/saves"), p("melonDS/states")),
                EmulatorId.PPSSPP.id to EmulatorPaths(
                    p("Android/data/org.ppsspp.ppsspp/files/PSP/SAVEDATA"),
                    p("Android/data/org.ppsspp.ppsspp/files/PSP/PPSSPP_STATE"),
                ),
                EmulatorId.DOLPHIN.id to EmulatorPaths(p("Android/data/org.dolphinemu.dolphinemu/files/GC")),
                EmulatorId.AZAHAR.id to EmulatorPaths(p("Android/data/org.azahar_emu.azahar/files/sdmc/Nintendo 3DS")),
                EmulatorId.EDEN.id to EmulatorPaths(p("Android/data/dev.eden.eden_emulator/files/nand/user/save")),
                EmulatorId.VITA3K.id to EmulatorPaths(p("Android/data/org.vita3k.emulator/files/vita/ux0/user/00/savedata")),
            ),
        )
    }
}
