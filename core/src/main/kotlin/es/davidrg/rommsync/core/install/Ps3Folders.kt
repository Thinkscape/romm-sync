package es.davidrg.rommsync.core.install

import es.davidrg.rommsync.core.remote.dto.RomDetailDto
import es.davidrg.rommsync.core.remote.dto.RomFileDto
import java.io.File

/**
 * Short game folder names for PS3 folder games.
 *
 * RPCS3 mounts a PSN (HG) game folder as `/dev_hdd0/game/<folder name>` and
 * `cellGameBootCheck` requires that name to be under 32 characters
 * (`CELL_GAME_DIRNAME_SIZE`): longer folders crash at boot ("Verification failed").
 * A `ps3` game folder whose RomM name is [MAX_DIR_NAME] + 1 characters or longer is
 * installed as `<TITLE_ID>.ps3` (title ID from the game's `PARAM.SFO`), or
 * `<TITLE_ID>_<rom id>.ps3` when another rom's install already holds that folder,
 * or `<fs_name stem, cut>_<rom id>.ps3` when the title ID is unreadable. The
 * `.ps3` suffix keeps it a directory ES-DE shows as one game; the gamelist name
 * stays RomM's game name. The library records the actual folder.
 */
object Ps3Folders {
    const val MAX_DIR_NAME = 31
    private const val SUFFIX = ".ps3"
    private val TITLE_ID = Regex("^[A-Z]{4}[0-9]{5}$")

    /** True when [plan] puts a `ps3` rom into a game folder whose name RPCS3 rejects. */
    fun needsShortName(rom: RomDetailDto, plan: InstallPlan): Boolean =
        rom.platformSlug.equals("ps3", ignoreCase = true) &&
            plan.targets.any { it.target != plan.primary } &&
            plan.primary.name.length > MAX_DIR_NAME

    /** The rom's `PARAM.SFO`: at the top of a PSN folder or in `PS3_GAME/` of a disc folder. */
    fun paramSfo(rom: RomDetailDto): RomFileDto? {
        val files = rom.files.filter { it.fileName.equals("PARAM.SFO", ignoreCase = true) }
        val bySub = files.associateBy { gameSubPath(rom, it).replace(File.separatorChar, '/') }
        return bySub[""] ?: bySub.entries.firstOrNull { it.key.equals("PS3_GAME", ignoreCase = true) }?.value
    }

    /** `TITLE_ID` of a PARAM.SFO (PSF: magic `\0PSF`, little-endian index), null when absent or malformed. */
    fun titleId(sfo: ByteArray): String? {
        if (sfo.size < 20 || sfo[0] != 0.toByte() || sfo[1] != 'P'.code.toByte() || sfo[2] != 'S'.code.toByte() || sfo[3] != 'F'.code.toByte()) {
            return null
        }
        fun u16(at: Int) = (sfo[at].toInt() and 0xff) or ((sfo[at + 1].toInt() and 0xff) shl 8)
        fun u32(at: Int) = u16(at) or (u16(at + 2) shl 16)
        val keyTable = u32(8)
        val dataTable = u32(12)
        val entries = u32(16)
        for (i in 0 until entries) {
            val entry = 20 + i * 16
            if (entry + 16 > sfo.size) return null
            val keyStart = keyTable + u16(entry)
            if (keyStart !in sfo.indices) return null
            var keyEnd = keyStart
            while (keyEnd < sfo.size && sfo[keyEnd] != 0.toByte()) keyEnd++
            if (String(sfo, keyStart, keyEnd - keyStart, Charsets.UTF_8) != "TITLE_ID") continue
            val dataStart = dataTable + u32(entry + 12)
            val dataLen = u32(entry + 4)
            if (dataStart < 0 || dataLen < 0 || dataStart + dataLen > sfo.size) return null
            val value = String(sfo, dataStart, dataLen, Charsets.UTF_8).trimEnd('\u0000').trim()
            return value.takeIf { TITLE_ID.matches(it) }
        }
        return null
    }

    /**
     * The folder name for [rom]: `<titleId>.ps3` unless [taken] (another rom's
     * install covers it), then `<titleId>_<rom id>.ps3`; without a title ID the
     * cut `fs_name` stem plus the rom id. [current] (the folder this rom is recorded
     * in) wins when it is one of those, so a re-push never moves the game.
     * Always [MAX_DIR_NAME] characters or fewer.
     */
    fun shortName(rom: RomDetailDto, titleId: String?, current: String? = null, taken: (String) -> Boolean): String {
        if (titleId != null) {
            val plain = "$titleId$SUFFIX"
            val tagged = "${titleId}_${rom.id}$SUFFIX"
            return when {
                current == plain || current == tagged -> current
                !taken(plain) -> plain
                else -> tagged
            }
        }
        val tag = "_${rom.id}"
        val stem = rom.fsName.removeSuffix(SUFFIX).removeSuffix(SUFFIX.uppercase())
            .take(MAX_DIR_NAME - SUFFIX.length - tag.length).trimEnd(' ', '.', '_', '-')
        return "$stem$tag$SUFFIX"
    }

    /**
     * [plan] again under the short folder when [needsShortName]; [fetch] reads
     * a rom file's bytes (null on failure), [taken] tells whether another rom's
     * install covers a folder name, [current] is the folder name this rom is
     * recorded in. Returns [plan] unchanged otherwise.
     */
    suspend fun replan(
        rom: RomDetailDto,
        fileIds: List<Int>,
        platformDir: File,
        plan: InstallPlan,
        current: String?,
        taken: (String) -> Boolean,
        fetch: suspend (RomFileDto) -> ByteArray?,
    ): InstallPlan {
        if (!needsShortName(rom, plan)) return plan
        val titleId = paramSfo(rom)?.let { fetch(it) }?.let(::titleId)
        return planInstall(rom, fileIds, platformDir, shortName(rom, titleId, current, taken))
    }
}
