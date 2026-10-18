package es.davidrg.rommsync.data.sync

import com.google.common.truth.Truth.assertThat
import es.davidrg.rommsync.core.remote.RomMApiService
import es.davidrg.rommsync.core.remote.dto.SyncOperation
import es.davidrg.rommsync.core.sync.SyncSlots
import es.davidrg.rommsync.core.sync.platform.LocalSave
import es.davidrg.rommsync.core.sync.platform.RetroArchSaveHandler
import es.davidrg.rommsync.core.sync.platform.SaveKind
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** RomM save-sync slot contract as implemented by the Android coordinator. */
class SyncSlotContractTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun save(romId: Int, file: File, kind: SaveKind = SaveKind.SAVE) =
        LocalSave(romId = romId, fileName = file.name, file = file, lastModified = 1_700_000_000_000, sha1 = "sha-${file.name}", kind = kind)

    @Test
    fun `negotiate requests carry slot and emulator for every save and exclude states`() {
        val dir = tmp.newFolder()
        val srm = File(dir, "Super Mario World (USA).srm").apply { writeText("srm") }
        val state = File(dir, "Super Mario World (USA).state1").apply { writeText("state") }
        val mcd = File(dir, "SLUS-00001.mcd").apply { writeText("mcd") }

        val requests = SyncCoordinator.buildNegotiateRequests(
            deviceId = "dev-1",
            scopeRomIds = listOf(10, 20, 30),
            savesByRom = mapOf(
                10 to listOf(save(10, srm), save(10, state, SaveKind.STATE)),
                20 to listOf(save(20, mcd)),
            ),
            emulatorFor = { romId -> if (romId == 20) "duckstation" else "retroarch" },
        )

        val request = requests.single()
        assertThat(request.deviceId).isEqualTo("dev-1")
        assertThat(request.romIds).containsExactly(10, 20, 30)
        assertThat(request.saves.map { it.fileName })
            .containsExactly("Super Mario World (USA).srm", "SLUS-00001.mcd")
        request.saves.forEach { client ->
            assertThat(client.slot).isNotNull()
            assertThat(client.slot).isEqualTo(client.fileName)
            assertThat(client.emulator).isNotNull()
            assertThat(client.contentHash).isNotNull()
        }
        assertThat(request.saves.map { it.emulator }).containsExactly("retroarch", "duckstation")
    }

    @Test
    fun `negotiate content_hash is RomM's hash - md5 for plain saves, composite for zips`() {
        val dir = tmp.newFolder()
        val srm = File(dir, "Game.srm").apply { writeText("abc") }
        val zip = File(dir, "0100ABCD.zip")
        java.util.zip.ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(java.util.zip.ZipEntry("z.bin")); out.write("abc".toByteArray()); out.closeEntry()
            out.putNextEntry(java.util.zip.ZipEntry("dir/")); out.closeEntry()
            out.putNextEntry(java.util.zip.ZipEntry("a.bin")); out.closeEntry()
        }
        fun md5(s: String) = java.security.MessageDigest.getInstance("MD5").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

        val saves = SyncCoordinator.buildNegotiateRequests(
            deviceId = "dev",
            scopeRomIds = listOf(1, 2),
            savesByRom = mapOf(1 to listOf(save(1, srm)), 2 to listOf(save(2, zip))),
            emulatorFor = { "retroarch" },
        ).single().saves.associate { it.fileName to it.contentHash }

        assertThat(saves["Game.srm"]).isEqualTo(md5("abc"))
        assertThat(saves["0100ABCD.zip"]).isEqualTo(
            md5("a.bin:${md5("")}\nz.bin:${md5("abc")}"),
        )
    }

    @Test
    fun `negotiate scope is chunked to the server rom-id cap`() {
        val ids = (1..(SyncSlots.MAX_ROM_IDS_PER_NEGOTIATE + 1)).toList()
        val requests = SyncCoordinator.buildNegotiateRequests("dev", ids, emptyMap()) { "retroarch" }
        assertThat(requests.map { it.romIds!!.size })
            .containsExactly(SyncSlots.MAX_ROM_IDS_PER_NEGOTIATE, 1).inOrder()
    }

    @Test
    fun `Thor SD profile keeps psx on RetroArch with SD RetroArch saves`() {
        val profile = SavePathResolver.deviceProfile("/storage/EBD7-FF2C/ROMs")
        assertThat(profile).isNotNull()
        assertThat(SavePathResolver.emulatorFor("psx", null, profile)).isEqualTo("retroarch")
        assertThat(
            SavePathResolver.platformBasePath("psx", null, null, "/storage/emulated/0/RetroArch", profile),
        ).isEqualTo("/storage/EBD7-FF2C/RetroArch")
        assertThat(SavePathResolver.deviceProfile("/storage/emulated/0/ROMs")).isNull()
    }

    @Test
    fun `download of a datetime tagged server save lands at the canonical local name`() = runTest {
        val base = tmp.newFolder("RetroArch")
        val api = mockk<RomMApiService>()
        coEvery { api.downloadSave(42, "dev-1") } returns "server bytes".toByteArray().toResponseBody()
        val transfer = SaveTransfer(api, tmp.newFolder("cache"))

        val op = SyncOperation(
            action = "download",
            romId = 7,
            saveId = 42,
            fileName = "Super Mario World (USA) [2026-10-18_09-30-00].srm",
            slot = "Super Mario World (USA).srm",
            emulator = "retroarch",
        )
        val target = SyncSlots.localNameFor(op.fileName, op.slot)
        val sha1 = transfer.downloadSave(
            saveId = op.saveId!!,
            deviceId = "dev-1",
            targetName = target,
            rom = SaveTransfer.RomRef(7, "Super Mario World (USA).sfc", "snes"),
            savesBasePath = base.path,
            handler = RetroArchSaveHandler(),
        )

        assertThat(sha1).isNotNull()
        val written = base.walkTopDown().filter { it.isFile }.toList()
        assertThat(written.map { it.name }).containsExactly("Super Mario World (USA).srm")
        assertThat(written.single().readText()).isEqualTo("server bytes")
    }

    @Test
    fun `negotiated upload passes slot, emulator, hash and device without overwrite`() = runTest {
        val dir = tmp.newFolder()
        val srm = File(dir, "Game.srm").apply { writeText("x") }
        val api = mockk<RomMApiService>()
        coEvery {
            api.uploadSave(any(), any(), any(), any(), any(), any(), any())
        } returns "{}".toByteArray().toResponseBody()
        val transfer = SaveTransfer(api, tmp.newFolder("cache"))

        val outcome = transfer.uploadSave(save(3, srm), 3, "dev-1", "retroarch", RetroArchSaveHandler(), overwrite = false)

        assertThat(outcome).isEqualTo(SaveTransfer.UploadOutcome.OK)
        coVerify {
            api.uploadSave(
                file = any(),
                romId = 3,
                emulator = "retroarch",
                deviceId = "dev-1",
                slot = "Game.srm",
                // md5("x"): RomM's content hash, not the local sha1.
                contentHash = "9dd4e461268c8034f5c8564e155c67a6",
                overwrite = false,
            )
        }
    }
}
