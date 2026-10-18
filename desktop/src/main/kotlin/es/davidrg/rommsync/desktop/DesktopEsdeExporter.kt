package es.davidrg.rommsync.desktop

import es.davidrg.rommsync.core.download.PlatformFolders
import es.davidrg.rommsync.core.i18n.tr
import es.davidrg.rommsync.core.metadata.EsdeGameEntry
import es.davidrg.rommsync.core.metadata.EsdeGamelist
import es.davidrg.rommsync.core.metadata.EsdeMediaExporter
import es.davidrg.rommsync.core.metadata.EsdeTarget
import es.davidrg.rommsync.core.remote.RomMApiService
import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * Manual "Export to ES-DE" for the local library: for every downloaded rom,
 * fetches its RomM detail and writes media + gamelist entry through the shared
 * [EsdeMediaExporter] (same layout as push installs). Gamelists are written
 * once per system with all its entries ([EsdeGamelist] upsert: other games and
 * ES-DE's own elements are preserved).
 */
class DesktopEsdeExporter(
    private val target: EsdeTarget,
    private val romsRoot: String,
    private val library: DesktopLibrary,
    private val api: RomMApiService,
    private val exporter: EsdeMediaExporter,
) {

    /** Exports and returns a readable summary. */
    suspend fun export(): String {
        val roms = library.roms()
        if (roms.isEmpty()) return tr("esde.no_roms")

        val bySystem = LinkedHashMap<String, MutableList<EsdeGameEntry>>()
        var mediaDownloaded = 0
        var failed = 0
        for (rom in roms) {
            val primary = locate(rom) ?: continue
            val systemFolder = primary.parentFile?.name ?: continue
            val detail = try {
                api.getRomDetail(rom.romId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed++
                continue
            }
            val result = exporter.exportFor(
                detail, primary, systemFolder, target.esdeDir, target.mediaDir,
                EsdeMediaExporter.Options(writeGamelist = false),
            )
            mediaDownloaded += result.written.size
            if (result.failures.isNotEmpty()) failed++
            result.entry?.let { bySystem.getOrPut(systemFolder) { mutableListOf() } += it }
        }

        val written = mutableListOf<String>()
        for ((system, entries) in bySystem) {
            EsdeGamelist.upsertFile(EsdeMediaExporter.gamelistFile(target.esdeDir, system), entries)
            written += "$system (${entries.size})"
        }
        return tr("esde.export_summary", bySystem.values.sumOf { it.size }, written.size, written.joinToString(", "), mediaDownloaded, failed)
    }

    /** The rom file or game folder on disk, directly inside its system folder; null when absent. */
    private fun locate(rom: DesktopLibrary.RomEntry): File? {
        rom.localPath?.let { path -> return File(path).takeIf { it.exists() } }
        val folder = library.platform(rom.platformSlug).romsFolderOverride
            ?: PlatformFolders.folderFor(rom.platformSlug)
            ?: return null
        return File(File(romsRoot, folder), rom.fileName).takeIf { it.exists() }
    }
}
