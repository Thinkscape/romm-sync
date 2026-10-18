package es.davidrg.rommsync.core.sync.platform

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DeviceSyncSaveHandlersTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun write(dir: File, relativePath: String, content: String): File {
        val f = File(dir, relativePath)
        f.parentFile!!.mkdirs()
        f.writeText(content)
        return f
    }

    // ── DuckStation ─────────────────────────────────────────────────────

    @Test
    fun `duckstation matches serial-named per-game card`() = runTest {
        val cards = tmp.newFolder("memcards")
        write(cards, "SLUS-00594_1.mcd", "card")
        write(cards, "SCUS-94163_1.mcd", "other")
        write(cards, "Shared_1.mcd", "shared")

        val saves = DuckStationSaveHandler().findSaves(
            romId = 10,
            romFileName = "Metal Gear Solid [SLUS-00594].chd",
            platformSlug = "psx",
            savesBasePath = cards.absolutePath,
        )
        assertEquals(listOf("SLUS-00594_1.mcd"), saves.map { it.fileName })
        assertEquals(SaveKind.SAVE, saves.single().kind)
    }

    @Test
    fun `duckstation reads serial from SYSTEM CNF through cue`() = runTest {
        val cards = tmp.newFolder("memcards")
        write(cards, "SCUS-94163_1.mcd", "card")
        val romDir = tmp.newFolder("roms")
        val bin = File(romDir, "Game (USA).bin")
        bin.writeBytes(ByteArray(40_000) + "BOOT = cdrom:\\SCUS_941.63;1\r\nTCB = 4\r\n".toByteArray())
        val cue = write(romDir, "Game (USA).cue", "FILE \"Game (USA).bin\" BINARY\n  TRACK 01 MODE2/2352\n")

        val saves = DuckStationSaveHandler().findSaves(
            romId = 11,
            romFileName = "Game (USA).cue",
            platformSlug = "psx",
            savesBasePath = cards.absolutePath,
            romLocalPath = cue.absolutePath,
        )
        assertEquals(listOf("SCUS-94163_1.mcd"), saves.map { it.fileName })
    }

    @Test
    fun `duckstation matches title-named card ignoring region tags`() = runTest {
        val cards = tmp.newFolder("memcards")
        write(cards, "Crash Bandicoot_1.mcd", "a")
        write(cards, "Crash Bandicoot 2 - Cortex Strikes Back_1.mcd", "b")

        val saves = DuckStationSaveHandler().findSaves(
            romId = 12,
            romFileName = "Crash Bandicoot (USA).chd",
            platformSlug = "psx",
            savesBasePath = cards.absolutePath,
        )
        assertEquals(listOf("Crash Bandicoot_1.mcd"), saves.map { it.fileName })
    }

    @Test
    fun `duckstation download writes card and rejects path traversal`() = runTest {
        val cards = File(tmp.root, "fresh/memcards")
        val temp = tmp.newFile("dl.mcd").apply { writeText("remote") }
        val handler = DuckStationSaveHandler()

        assertTrue(handler.extractDownload(temp, "Crash Bandicoot (USA).chd", "psx", cards.absolutePath, "Crash Bandicoot_1.mcd"))
        assertEquals("remote", File(cards, "Crash Bandicoot_1.mcd").readText())
        assertFalse(handler.extractDownload(temp, "x.chd", "psx", cards.absolutePath, "../evil.mcd"))
        assertFalse(handler.extractDownload(temp, "x.chd", "psx", cards.absolutePath, "notes.txt"))
    }

    // ── Vita3K ──────────────────────────────────────────────────────────

    @Test
    fun `vita3k zips title folder deterministically and restores it`() = runTest {
        val savedata = tmp.newFolder("savedata")
        write(savedata, "PCSE00491/sce_sys/param.sfo", "sfo")
        write(savedata, "PCSE00491/SAVEDATA00", "progress")
        write(savedata, "PCSB00245/other", "x")
        val handler = Vita3KSaveHandler()
        val rom = "Persona 4 Golden [PCSE00491].vpk"

        val first = handler.findSaves(1, rom, "psvita", savedata.absolutePath)
        val second = handler.findSaves(1, rom, "psvita", savedata.absolutePath)
        assertEquals("PCSE00491_vita_save.zip", first.single().fileName)
        assertEquals(first.single().sha1, second.single().sha1)

        val target = tmp.newFolder("other_device")
        write(target, "PCSE00491/stale", "old")
        assertTrue(handler.extractDownload(first.single().file, rom, "psvita", target.absolutePath, first.single().fileName))
        assertEquals("progress", File(target, "PCSE00491/SAVEDATA00").readText())
        assertEquals("sfo", File(target, "PCSE00491/sce_sys/param.sfo").readText())
        assertFalse(File(target, "PCSE00491/stale").exists())

        val roundTrip = handler.findSaves(1, rom, "psvita", target.absolutePath)
        assertEquals(first.single().sha1, roundTrip.single().sha1)
    }

    @Test
    fun `vita3k reads title id from param sfo inside vpk`() = runTest {
        val savedata = tmp.newFolder("savedata")
        write(savedata, "PCSB00245/data.bin", "save")
        val vpk = File(tmp.root, "Some Game.vpk")
        ZipOutputStream(vpk.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("sce_sys/param.sfo"))
            zos.write(sfo(mapOf("CATEGORY" to "gd", "TITLE_ID" to "PCSB00245")))
            zos.closeEntry()
        }

        val saves = Vita3KSaveHandler().findSaves(2, vpk.name, "psvita", savedata.absolutePath, vpk.absolutePath)
        assertEquals("PCSB00245_vita_save.zip", saves.single().fileName)
    }

    /** Minimal PARAM.SFO with UTF-8 string entries. */
    private fun sfo(entries: Map<String, String>): ByteArray {
        val keys = ByteArrayOutputStream()
        val data = ByteArrayOutputStream()
        val index = ByteBuffer.allocate(entries.size * 16).order(ByteOrder.LITTLE_ENDIAN)
        for ((key, value) in entries) {
            val bytes = value.toByteArray() + 0.toByte()
            index.putShort(keys.size().toShort()).putShort(0x0204.toShort())
                .putInt(bytes.size).putInt(bytes.size).putInt(data.size())
            keys.write(key.toByteArray() + 0.toByte())
            data.write(bytes)
        }
        val keyTable = 0x14 + entries.size * 16
        val dataTable = keyTable + keys.size()
        val header = ByteBuffer.allocate(0x14).order(ByteOrder.LITTLE_ENDIAN)
            .put(byteArrayOf(0, 'P'.code.toByte(), 'S'.code.toByte(), 'F'.code.toByte()))
            .putInt(0x0101).putInt(keyTable).putInt(dataTable).putInt(entries.size)
        return header.array() + index.array() + keys.toByteArray() + data.toByteArray()
    }

    // ── RetroArch states ────────────────────────────────────────────────

    @Test
    fun `retroarch returns states as STATE and skips thumbnails`() = runTest {
        val base = tmp.newFolder("retroarch")
        write(base, "saves/Tetris DX (World).srm", "srm")
        write(base, "states/Tetris DX (World).state.auto", "auto")
        write(base, "states/Tetris DX (World).state2", "slot2")
        write(base, "states/Tetris DX (World).state.auto.png", "thumb")
        write(base, "states/Other.state", "other")

        val saves = RetroArchSaveHandler().findSaves(3, "Tetris DX (World).gbc", "gbc", base.absolutePath)
        val byName = saves.associate { it.fileName to it.kind }
        assertEquals(
            mapOf(
                "Tetris DX (World).srm" to SaveKind.SAVE,
                "Tetris DX (World).state.auto" to SaveKind.STATE,
                "Tetris DX (World).state2" to SaveKind.STATE,
            ),
            byName,
        )
    }

    @Test
    fun `retroarch finds dosbox pure save zip`() = runTest {
        val base = tmp.newFolder("retroarch")
        write(base, "saves/ugh.pure.zip", "pure")

        val saves = RetroArchSaveHandler().findSaves(4, "ugh.zip", "dos", base.absolutePath)
        assertEquals(listOf("ugh.pure.zip"), saves.map { it.fileName })
    }

    // ── Registry ────────────────────────────────────────────────────────

    @Test
    fun `syncSupport rejects xbox flycast and mame with reasons`() {
        val xbox = SaveHandlerRegistry.syncSupport("xbox", null)
        assertTrue(xbox is SyncSupport.Unsupported && xbox.reason.contains("Xemu HDD image"))

        val dc = SaveHandlerRegistry.syncSupport("dreamcast", "flycast")
        assertTrue(dc is SyncSupport.Unsupported && dc.reason.contains("no shared save format"))

        val arcade = SaveHandlerRegistry.syncSupport("arcade", "mame")
        assertTrue(arcade is SyncSupport.Unsupported)

        assertEquals(SyncSupport.Supported, SaveHandlerRegistry.syncSupport("snes", null))
        assertEquals(SyncSupport.Supported, SaveHandlerRegistry.syncSupport("psx", null, SavePaths.EMUDECK))
    }

    @Test
    fun `registry defaults follow the device profile`() {
        val deck = SavePaths.EMUDECK
        assertEquals(SaveHandlerRegistry.EmulatorId.DUCKSTATION, SaveHandlerRegistry.getDefaultEmulator("psx", deck))
        assertEquals(SaveHandlerRegistry.EmulatorId.RPCS3, SaveHandlerRegistry.getDefaultEmulator("ps3", deck))
        assertEquals(SaveHandlerRegistry.EmulatorId.PCSX2, SaveHandlerRegistry.getDefaultEmulator("ps2", deck))
        assertEquals(SaveHandlerRegistry.EmulatorId.VITA3K, SaveHandlerRegistry.getDefaultEmulator("psvita", deck))
        assertEquals(SaveHandlerRegistry.EmulatorId.VITA3K, SaveHandlerRegistry.getDefaultEmulator("psvita"))
        // Upstream defaults without a profile stay as they were.
        assertEquals(SaveHandlerRegistry.EmulatorId.RETROARCH, SaveHandlerRegistry.getDefaultEmulator("psx"))
        assertEquals(SaveHandlerRegistry.EmulatorId.ARMSX3, SaveHandlerRegistry.getDefaultEmulator("ps3"))

        assertTrue(SaveHandlerRegistry.getHandler("psx", null, deck) is DuckStationSaveHandler)
        assertTrue(SaveHandlerRegistry.getHandler("psvita", null) is Vita3KSaveHandler)
        assertTrue(SaveHandlerRegistry.getHandler("ps3", null, deck) is Ps3SaveHandler)
    }

    @Test
    fun `profiles resolve handler base paths`() {
        // A home without the Dolphin flatpak keeps EmuDeck's dolphin/{GC,Wii} folders.
        val deck = SavePaths.emudeck("/e/saves", home = tmp.newFolder("home").path)
        assertEquals("/e/saves/retroarch", deck.handlerBasePath("retroarch", "snes"))
        assertEquals("/e/saves/dolphin/GC", deck.handlerBasePath("dolphin", "ngc"))
        assertEquals("/e/saves/dolphin/Wii/title", deck.handlerBasePath("dolphin", "wii"))
        assertEquals("/e/saves/rpcs3/saves", deck.handlerBasePath("rpcs3", "ps3"))

        val thor = SavePaths.android("/storage/EBD7-FF2C")
        assertEquals("/storage/EBD7-FF2C/RetroArch", thor.handlerBasePath("retroarch", "gba"))
        assertEquals(
            "/storage/EBD7-FF2C/Android/data/dev.eden.eden_emulator/files/nand/user/save",
            SaveHandlerRegistry.getDefaultSavesPath("eden", "switch", "/unused", thor),
        )
    }

    @Test
    fun `ps3 handler accepts EmuDeck savedata symlink target as base`() = runTest {
        val savedata = tmp.newFolder("dev_hdd0", "home", "00000001", "savedata")
        write(savedata, "BCES00052_SAVE_1/GAME.SAV", "save")
        val link = File(tmp.root, "saves").toPath()
        java.nio.file.Files.createSymbolicLink(link, savedata.toPath())

        val saves = Ps3SaveHandler().findSaves(5, "BCES00052 - Game.pkg", "ps3", link.toString())
        assertEquals(1, saves.size)
    }
}
