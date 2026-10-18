package es.davidrg.rommsync.core.sync.platform

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class StandaloneSaveLayoutsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun gci(dir: File, name: String, header: String): File {
        dir.mkdirs()
        return File(dir, name).apply { writeBytes(header.toByteArray(Charsets.US_ASCII) + ByteArray(58) { it.toByte() }) }
    }

    private fun zip(output: File, entries: Map<String, String>): File {
        ZipOutputStream(FileOutputStream(output)).use { zos ->
            entries.forEach { (name, content) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
        return output
    }

    private fun zipEntryNames(file: File): List<String> = ZipFile(file).use { z -> z.entries().toList().map { it.name } }

    // ── Dolphin (GameCube GCI folder) ───────────────────────────────────

    @Test
    fun `dolphin finds GCIs by maker and game code in the disc region and restores them`() = runTest {
        val source = tmp.newFolder("srcGC")
        val usa = File(source, "USA/Card A")
        gci(usa, "01-GAFE-DobutsunoMori.gci", "GAFE01")
        gci(usa, "01-GAFE-DobutsunoMori_town.gci", "GAFE01")
        gci(usa, "8P-GAFE-OtherMaker.gci", "GAFE8P")
        gci(usa, "01-GALE-SuperSmashBros.gci", "GALE01")
        gci(File(source, "EUR/Card A"), "01-GAFP-DobutsunoMori.gci", "GAFP01")

        val handler = DolphinSaveHandler()
        val saves = handler.findSaves(1, "GAFE01 - Animal Crossing.iso", "ngc", source.path)
        assertEquals(1, saves.size)
        assertEquals("GAFE01_gc_saves.zip", saves.single().fileName)
        assertEquals(
            listOf("01-GAFE-DobutsunoMori.gci", "01-GAFE-DobutsunoMori_town.gci"),
            zipEntryNames(saves.single().file),
        )
        // Deterministic: same files ⇒ same hash.
        assertEquals(saves.single().sha1, handler.findSaves(1, "GAFE01 - Animal Crossing.iso", "ngc", source.path).single().sha1)

        // Restore on a device whose card folders do not exist yet; the ROM name
        // carries no id, the asset name does.
        val target = tmp.newFolder("dstGC")
        val stale = gci(File(target, "USA/Card A"), "01-GAFE-Stale.gci", "GAFE01")
        val keep = gci(File(target, "USA/Card A"), "01-GALE-SuperSmashBros.gci", "GALE01")
        assertTrue(
            handler.extractDownload(saves.single().file, "Animal Crossing (USA).rvz", "ngc", target.path, "GAFE01_gc_saves.zip"),
        )
        val card = File(target, "USA/Card A")
        assertEquals(
            setOf("01-GAFE-DobutsunoMori.gci", "01-GAFE-DobutsunoMori_town.gci", "01-GALE-SuperSmashBros.gci"),
            card.list()!!.toSet(),
        )
        assertFalse(stale.exists())
        assertTrue(keep.exists())

        // Round trip: the restored folder zips to the same asset.
        assertEquals(saves.single().sha1, handler.findSaves(1, "GAFE01.rvz", "ngc", target.path).single().sha1)
    }

    @Test
    fun `dolphin restores PAL saves into EUR and matches renamed GCIs by header`() = runTest {
        val target = tmp.newFolder("GC")
        File(target, "USA/Card A").mkdirs()
        val archive = zip(tmp.newFile("pal.zip"), mapOf("01-GAFP-DobutsunoMori.gci" to "GAFP01-payload"))
        assertTrue(DolphinSaveHandler().extractDownload(archive, "Animal Crossing (Europe).rvz", "gc", target.path, "GAFP01_gc_saves.zip"))
        assertTrue(File(target, "EUR/Card A/01-GAFP-DobutsunoMori.gci").isFile)

        val renamed = gci(File(target, "EUR/Card A"), "my backup.gci", "GAFP01")
        assertTrue(DolphinGciLayout.matches(renamed, "GAFP01"))
        assertFalse(DolphinGciLayout.matches(renamed, "GAFE01"))
    }

    @Test
    fun `dolphin extractDownload uses the game id of the local ROM`() = runTest {
        // Plain GC disc: id at 0x0000 (and the game code at 0x0440 for older readers), magic at 0x1C.
        val rom = tmp.newFile("Animal Crossing (USA).iso")
        RandomAccessFile(rom, "rw").use { raf ->
            raf.setLength(0x8000)
            raf.seek(0); raf.write("GAFE01".toByteArray())
            raf.seek(0x1C); raf.write(byteArrayOf(0xC2.toByte(), 0x33, 0x9F.toByte(), 0x3D))
            raf.seek(0x0440); raf.write("GAFE".toByteArray())
        }
        val archive = zip(tmp.newFile("dl.zip"), mapOf("01-GAFE-DobutsunoMori.gci" to "GAFE01-payload"))
        val target = tmp.newFolder("GC")
        val handler = DolphinSaveHandler()

        assertFalse(handler.extractDownload(archive, rom.name, "ngc", target.path, "save.zip"))
        assertTrue(handler.extractDownload(archive, rom.name, "ngc", target.path, "save.zip", rom.path))
        assertTrue(File(target, "USA/Card A/01-GAFE-DobutsunoMori.gci").isFile)
    }

    // ── PS2 (folder memory cards) ───────────────────────────────────────

    @Test
    fun `ps2 extractDownload reads the serial from the local ISO when the names carry none`() = runTest {
        val rom = tmp.newFile("Some Game (USA).iso")
        // Whole 2048-byte sectors (real images are sector-aligned; the reader
        // stops at a short sector). SYSTEM.CNF text sits in sector 17, found by
        // the reader's raw scan since there is no ISO9660 PVD.
        val cnf = "BOOT2 = cdrom0:\\SLUS_200.32;1\nVER = 1.00\n".toByteArray()
        rom.writeBytes(ByteArray(0x8800) + cnf + ByteArray(0x800 - cnf.size) + ByteArray(0x800))
        val memcards = tmp.newFolder("memcards")
        File(memcards, "Mcd001.ps2").mkdirs()
        val archive = zip(tmp.newFile("dl.zip"), mapOf("BASLUS-20032/save.bin" to "data"))
        val handler = Ps2SaveHandler()

        assertFalse(handler.extractDownload(archive, rom.name, "ps2", memcards.path, "save.zip"))
        assertTrue(handler.extractDownload(archive, rom.name, "ps2", memcards.path, "save.zip", rom.path))
        assertEquals("data", File(memcards, "Mcd001.ps2/BASLUS-20032/save.bin").readText())
    }

    // ── PPSSPP ──────────────────────────────────────────────────────────

    @Test
    fun `ppsspp restores SAVEDATA folders of the disc id from the asset name`() = runTest {
        val saveData = tmp.newFolder("SAVEDATA")
        File(saveData, "NPUZ00085DATA00").mkdirs()
        File(saveData, "NPUZ00085DATA00/OLD.BIN").writeText("old")
        File(saveData, "ULUS10041DATA").mkdirs()
        val archive = zip(tmp.newFile("dl.zip"), mapOf("NPUZ00085DATA00/DATA.BIN" to "new"))

        assertTrue(PpssppSaveHandler().extractDownload(archive, "Patapon 2.cso", "psp", saveData.path, "NPUZ00085_saves.zip"))
        assertEquals(setOf("DATA.BIN"), File(saveData, "NPUZ00085DATA00").list()!!.toSet())
        assertTrue(File(saveData, "ULUS10041DATA").isDirectory)
    }

    // ── Eden (Switch) ───────────────────────────────────────────────────

    @Test
    fun `eden finds saves under a 32-hex profile and restores into the profile that has saves`() = runTest {
        val base = tmp.newFolder("save")
        val user = File(base, "0000000000000000")
        File(user, "00000000000000000000000000000000").mkdirs()
        val main = File(user, "183884E8D54536AD3181506A16527D28")
        File(main, "0100000000010000/game.sav").apply { parentFile.mkdirs(); writeText("odyssey") }
        File(main, "01009D901BC56000").mkdirs()
        val other = File(user, "B727756762C9D8756553D6EFA692D9DB").apply { mkdirs() }
        other.setLastModified(System.currentTimeMillis() + 60_000)

        val handler = SwitchSaveHandler()
        val found = handler.findSaves(5, "[0100000000010000] Super Mario Odyssey.nsp", "switch", base.path)
        assertEquals("0100000000010000_switch_save.zip", found.single().fileName)

        val archive = zip(tmp.newFile("dl.zip"), mapOf("01006A800016E000/main" to "zelda"))
        assertTrue(handler.extractDownload(archive, "Zelda.nsp", "switch", base.path, "01006A800016E000_switch_save.zip"))
        assertEquals("zelda", File(main, "01006A800016E000/main").readText())
        assertFalse(File(other, "01006A800016E000").exists())
        assertFalse(File(user, "0000000000000001").exists())
    }

    @Test
    fun `eden restores into the single profile present when it has no saves yet`() = runTest {
        val base = tmp.newFolder("save")
        val profile = File(base, "0000000000000000/183884E8D54536AD3181506A16527D28").apply { mkdirs() }
        val archive = zip(tmp.newFile("dl.zip"), mapOf("0100000000010000/game.sav" to "odyssey"))

        assertTrue(SwitchSaveHandler().extractDownload(archive, "Odyssey.xci", "switch", base.path, "0100000000010000_switch_save.zip"))
        assertEquals("odyssey", File(profile, "0100000000010000/game.sav").readText())
    }

    // ── Profiles ────────────────────────────────────────────────────────

    @Test
    fun `emudeck profile prefers the Dolphin flatpak data dir`() {
        val home = tmp.newFolder("home")
        File(home, "${SavePaths.DOLPHIN_FLATPAK_USER_DIR}/GC").mkdirs()
        val deck = SavePaths.emudeck("/e/saves", home.path)
        val flatpak = File(home, SavePaths.DOLPHIN_FLATPAK_USER_DIR).path
        assertEquals("$flatpak/GC", deck.handlerBasePath("dolphin", "ngc"))
        assertEquals("$flatpak/Wii/title", deck.handlerBasePath("dolphin", "wii"))

        val noFlatpak = SavePaths.emudeck("/e/saves", tmp.newFolder("bare").path)
        assertEquals("/e/saves/dolphin/GC", noFlatpak.handlerBasePath("dolphin", "ngc"))
    }
}
