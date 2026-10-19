package es.davidrg.rommsync.data.install

import es.davidrg.rommsync.core.install.InstalledRom
import es.davidrg.rommsync.core.install.InstalledRomStore
import es.davidrg.rommsync.core.remote.dto.RomDetailDto
import es.davidrg.rommsync.data.local.dao.RomDao
import es.davidrg.rommsync.data.local.entity.DownloadedRomEntity
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Records ROMs pushed by RomM ("Install on device") in the same
 * `downloaded_roms` table manual downloads use, so the library shows them as
 * downloaded and save sync covers them.
 *
 * [InstallClient] calls [recordInstalled] from its own background thread, so
 * blocking on the Room write here is fine.
 */
class RoomInstalledRomStore(private val romDao: RomDao) : InstalledRomStore {

    override fun recordInstalled(rom: RomDetailDto, primaryFile: File, files: List<File>) {
        runBlocking { romDao.insertDownloadedRom(toEntity(rom, primaryFile, files, romDao.getDownloadedRom(rom.id))) }
    }

    /**
     * The recorded install: `localPath` is the rom file, or the game folder for
     * multi-file roms, so it alone covers every file the install wrote.
     */
    override fun installed(romId: Int): InstalledRom? = runBlocking { romDao.getDownloadedRom(romId) }?.let { row ->
        val primary = File(row.localPath)
        InstalledRom(romId = row.romId, platformSlug = row.platformSlug, primary = primary, files = listOf(primary))
    }

    override fun forgetInstalled(romId: Int) {
        runBlocking { romDao.deleteDownloadedRom(romId) }
    }

    companion object {
        /**
         * Entity for a pushed rom. Per-game sync settings of an existing row
         * (saves path override, exclusion) survive a re-push, as they do for
         * manual re-downloads in `DownloadWorker`.
         */
        internal fun toEntity(
            rom: RomDetailDto,
            primaryFile: File,
            files: List<File>,
            existing: DownloadedRomEntity?,
        ): DownloadedRomEntity = DownloadedRomEntity(
            romId = rom.id,
            name = rom.name?.takeIf { it.isNotBlank() } ?: rom.fsName.substringBeforeLast('.'),
            // Save handlers derive save names from this (e.g. RetroArch `<stem>.srm`):
            // the rom file for single-file games, the game folder otherwise.
            fileName = primaryFile.name,
            platformId = rom.platformId.takeIf { it != 0 } ?: existing?.platformId ?: 0,
            platformSlug = rom.platformSlug,
            localPath = primaryFile.absolutePath,
            fileSizeBytes = files.sumOf { it.length() },
            fileHash = rom.files.singleOrNull()?.sha1Hash,
            savesPathOverride = existing?.savesPathOverride,
            excludedFromSync = existing?.excludedFromSync ?: false,
        )
    }
}
