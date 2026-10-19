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

    // ── Install record + provenance (collection sync) ───────────────────
    //   rom.<id>.files      = every file written by the install, one path per line
    //   rom.<id>.provenance = comma-separated markers (`manual`, `collection:<kind>:<id>`);
    //                         absent on a rom entry = `manual` (entries from before
    //                         collection sync and hand downloads are the user's).

    @Synchronized
    fun files(romId: Int): List<File> {
        reloadIfChanged()
        return props.getProperty("rom.$romId.files").orEmpty().split('\n').filter { it.isNotBlank() }.map(::File)
    }

    @Synchronized
    fun setFiles(romId: Int, files: List<File>) {
        reloadIfChanged()
        props["rom.$romId.files"] = files.joinToString("\n") { it.absolutePath }
        persist()
    }

    @Synchronized
    fun provenance(romId: Int): Set<String> {
        reloadIfChanged()
        return readProvenance(romId)
    }

    /** Stores [markers]; an empty set is kept explicitly on a rom entry (never read back as `manual`). */
    @Synchronized
    fun setProvenance(romId: Int, markers: Set<String>) {
        reloadIfChanged()
        val key = "rom.$romId.provenance"
        if (markers.isEmpty() && props.getProperty("rom.$romId.name") == null) {
            props.remove(key)
        } else {
            props[key] = markers.sorted().joinToString(",")
        }
        persist()
    }

    /** Adds `manual` (hand downloads from the GUI). */
    @Synchronized
    fun markManual(romId: Int) {
        val current = provenance(romId)
        if (MANUAL !in current) setProvenance(romId, current + MANUAL)
    }

    @Synchronized
    fun allProvenance(): Map<Int, Set<String>> {
        reloadIfChanged()
        val ids = props.stringPropertyNames()
            .filter { it.startsWith("rom.") && (it.endsWith(".name") || it.endsWith(".provenance")) }
            .mapNotNull { it.substringAfter("rom.").substringBefore('.').toIntOrNull() }
            .toSet()
        return ids.associateWith { readProvenance(it) }.filterValues { it.isNotEmpty() }
    }

    private fun readProvenance(romId: Int): Set<String> {
        val raw = props.getProperty("rom.$romId.provenance")
            ?: return if (props.getProperty("rom.$romId.name") != null) setOf(MANUAL) else emptySet()
        return raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
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

    companion object {
        const val MANUAL = es.davidrg.rommsync.core.collections.Provenance.MANUAL
    }
}

/**
 * Records roms installed by RomM pushes and collection sync
 * ([es.davidrg.rommsync.core.install.InstallClient]) in [DesktopLibrary], so the save
 * sync covers them and the GUI shows them as downloaded. Per-rom user settings (saves
 * override, sync exclusion) survive a re-push. Also the provenance store of the daemon.
 */
class DesktopInstalledRomStore(
    private val library: DesktopLibrary,
) : es.davidrg.rommsync.core.install.InstalledRomStore, es.davidrg.rommsync.core.collections.RomProvenanceStore {
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
        library.setFiles(rom.id, files)
    }

    override fun installed(romId: Int): es.davidrg.rommsync.core.install.InstalledRom? {
        val entry = library.rom(romId) ?: return null
        val primary = entry.localPath?.let(::File) ?: return null
        return es.davidrg.rommsync.core.install.InstalledRom(romId, entry.platformSlug, primary, library.files(romId))
    }

    override fun forgetInstalled(romId: Int) = library.removeRom(romId)

    override fun markers(romId: Int): Set<String> = library.provenance(romId)

    override fun setMarkers(romId: Int, markers: Set<String>) = library.setProvenance(romId, markers)

    override fun allMarkers(): Map<Int, Set<String>> = library.allProvenance()
}

/** [es.davidrg.rommsync.core.collections.CollectionStateStore] in `collections.properties`. */
class DesktopCollectionState(private val file: File) : es.davidrg.rommsync.core.collections.CollectionStateStore {
    private val props = java.util.Properties()

    init {
        if (file.isFile) runCatching { file.inputStream().use { props.load(it) } }
    }

    @Synchronized
    override fun known(): Map<String, es.davidrg.rommsync.core.collections.KnownCollection> =
        props.stringPropertyNames().filter { it.endsWith(".name") }.associate { nameKey ->
            val key = nameKey.removeSuffix(".name")
            key to es.davidrg.rommsync.core.collections.KnownCollection(
                props.getProperty(nameKey),
                props.getProperty("$key.signature").orEmpty(),
            )
        }

    @Synchronized
    override fun put(key: String, value: es.davidrg.rommsync.core.collections.KnownCollection) {
        props["$key.name"] = value.name
        props["$key.signature"] = value.signature
        persist()
    }

    @Synchronized
    override fun remove(key: String) {
        props.remove("$key.name")
        props.remove("$key.signature")
        persist()
    }

    private fun persist() {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, ".${file.name}.tmp")
        tmp.outputStream().use { props.store(it, "RomM Sync: collections synced to this device") }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }
}
