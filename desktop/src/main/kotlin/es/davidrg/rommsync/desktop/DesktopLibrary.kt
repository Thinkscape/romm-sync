package es.davidrg.rommsync.desktop

import java.io.File
import java.util.Properties

/**
 * Registro de ROMs descargados en desktop y config por plataforma.
 * Formato Properties (cero dependencias): ~/.config/romm-sync/library.properties
 *
 *   rom.<id>.name = ...
 *   rom.<id>.file = ...
 *   rom.<id>.platform = ...
 *   platform.<slug>.emulator = ...
 *   platform.<slug>.savesPath = ...
 */
class DesktopLibrary(private val file: File) {

    private val props = Properties()

    /** (mtime, size) of the file as last loaded/written: the daemon and the GUI share it. */
    private var loadedStamp: Pair<Long, Long>? = null

    init {
        reloadIfChanged()
    }

    /** Re-reads the file when another process (daemon ↔ GUI) rewrote it since we last saw it. */
    @Synchronized
    private fun reloadIfChanged() {
        val stamp = if (file.isFile) file.lastModified() to file.length() else null
        if (stamp == loadedStamp) return
        props.clear()
        if (stamp != null) runCatching { file.inputStream().use { props.load(it) } }
        loadedStamp = stamp
    }

    @Synchronized
    private fun persist() {
        file.parentFile?.mkdirs()
        // Write-then-rename so the other process never reads a half-written file.
        val tmp = File(file.parentFile, ".${file.name}.tmp")
        tmp.outputStream().use { props.store(it, "RomM Sync desktop library") }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
        loadedStamp = file.lastModified() to file.length()
    }

    data class RomEntry(
        val romId: Int,
        val name: String,
        val fileName: String,
        val platformSlug: String,
        val localPath: String? = null,
        val savesPathOverride: String? = null,
        val excludedFromSync: Boolean = false,
    )

    data class PlatformEntry(
        val slug: String,
        val emulatorId: String? = null,
        val savesPathOverride: String? = null,
        val romsFolderOverride: String? = null,
    )

    @Synchronized
    fun roms(): List<RomEntry> {
        reloadIfChanged()
        val ids = props.stringPropertyNames()
            .filter { it.startsWith("rom.") && it.endsWith(".name") }
            .mapNotNull { it.substringAfter("rom.").substringBefore('.').toIntOrNull() }
        return ids.mapNotNull { id -> readRom(id) }
    }

    @Synchronized
    fun rom(id: Int): RomEntry? {
        reloadIfChanged()
        return readRom(id)
    }

    @Synchronized
    fun upsertRom(entry: RomEntry) {
        reloadIfChanged()
        val p = "rom.${entry.romId}"
        props["$p.name"] = entry.name
        props["$p.file"] = entry.fileName
        props["$p.platform"] = entry.platformSlug
        props["$p.path"] = entry.localPath ?: ""
        props["$p.savesPath"] = entry.savesPathOverride ?: ""
        props["$p.excluded"] = entry.excludedFromSync.toString()
        persist()
    }

    @Synchronized
    fun removeRom(romId: Int) {
        reloadIfChanged()
        val prefix = "rom.$romId."
        props.stringPropertyNames().filter { it.startsWith(prefix) }.forEach { props.remove(it) }
        persist()
    }

    @Synchronized
    fun platform(slug: String): PlatformEntry {
        reloadIfChanged()
        val emu = props.getProperty("platform.$slug.emulator")
        val saves = props.getProperty("platform.$slug.savesPath")?.takeIf { it.isNotBlank() }
        val folder = props.getProperty("platform.$slug.romsFolder")?.takeIf { it.isNotBlank() }
        return PlatformEntry(slug, emu?.takeIf { it.isNotBlank() }, saves, folder)
    }

    @Synchronized
    fun upsertPlatform(entry: PlatformEntry) {
        reloadIfChanged()
        props["platform.${entry.slug}.emulator"] = entry.emulatorId ?: ""
        props["platform.${entry.slug}.savesPath"] = entry.savesPathOverride ?: ""
        props["platform.${entry.slug}.romsFolder"] = entry.romsFolderOverride ?: ""
        persist()
    }

    private fun readRom(id: Int): RomEntry? {
        val name = props.getProperty("rom.$id.name") ?: return null
        return RomEntry(
            romId = id,
            name = name,
            fileName = props.getProperty("rom.$id.file") ?: name,
            platformSlug = props.getProperty("rom.$id.platform") ?: "unknown",
            localPath = props.getProperty("rom.$id.path")?.takeIf { it.isNotBlank() },
            savesPathOverride = props.getProperty("rom.$id.savesPath")?.takeIf { it.isNotBlank() },
            excludedFromSync = props.getProperty("rom.$id.excluded") == "true",
        )
    }
}

/**
 * Records roms installed by RomM pushes ([es.davidrg.rommsync.core.install.InstallClient])
 * in [DesktopLibrary], so the save sync covers them and the GUI shows them as
 * downloaded. Per-rom user settings (saves override, sync exclusion) survive a re-push.
 */
class DesktopInstalledRomStore(
    private val library: DesktopLibrary,
) : es.davidrg.rommsync.core.install.InstalledRomStore {
    override fun recordInstalled(
        rom: es.davidrg.rommsync.core.remote.dto.RomDetailDto,
        primaryFile: File,
        files: List<File>,
    ) {
        val existing = library.rom(rom.id)
        library.upsertRom(
            DesktopLibrary.RomEntry(
                romId = rom.id,
                name = rom.name?.takeIf { it.isNotBlank() } ?: rom.fsName,
                fileName = rom.fsName,
                platformSlug = rom.platformSlug,
                localPath = primaryFile.absolutePath,
                savesPathOverride = existing?.savesPathOverride,
                excludedFromSync = existing?.excludedFromSync ?: false,
            ),
        )
    }
}
