package es.davidrg.rommsync.desktop

import es.davidrg.rommsync.core.remote.dto.SyncOperation
import es.davidrg.rommsync.core.sync.SyncSlots
import es.davidrg.rommsync.core.sync.platform.LocalSave
import es.davidrg.rommsync.core.sync.platform.SaveKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopSyncCoordinatorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun local(romId: Int, name: String, kind: SaveKind) = LocalSave(
        romId = romId,
        fileName = name,
        file = tmp.newFile(name).apply { writeText(name) },
        lastModified = 1_760_000_000_000L,
        sha1 = "sha1-$name",
        kind = kind,
    )

    @Test
    fun `negotiate request carries slot and emulator for every save and excludes states`() {
        val saves = mapOf(
            1 to listOf(
                local(1, "Super Mario World (USA).srm", SaveKind.SAVE),
                local(1, "Super Mario World (USA).state1", SaveKind.STATE),
            ),
            2 to listOf(local(2, "Commander Keen 1.pure.zip", SaveKind.SAVE)),
        )
        val emulators = mapOf(1 to "retroarch", 2 to "retroarch")

        val request = DesktopSyncCoordinator.buildNegotiateRequest("dev-1", listOf(1, 2, 3), saves) { emulators.getValue(it) }

        assertEquals("dev-1", request.deviceId)
        assertEquals(listOf(1, 2, 3), request.romIds)
        assertEquals(
            setOf("Super Mario World (USA).srm", "Commander Keen 1.pure.zip"),
            request.saves.map { it.fileName }.toSet(),
        )
        for (save in request.saves) {
            assertNotNull("slot for ${save.fileName}", save.slot)
            assertEquals(save.fileName, save.slot)
            assertEquals("retroarch", save.emulator)
            assertNotNull(save.contentHash)
        }
        assertTrue(request.saves.none { it.fileName.contains(".state") })
    }

    @Test
    fun `download of a datetime-tagged server save lands at the canonical local name`() {
        val op = SyncOperation(
            action = "download",
            romId = 1,
            saveId = 7,
            fileName = "Super Mario World (USA) [2026-10-18_10-00-00].srm",
            slot = "Super Mario World (USA).srm",
            emulator = "retroarch",
        )
        // The coordinator downloads to SyncSlots.localNameFor(op.fileName, op.slot).
        assertEquals("Super Mario World (USA).srm", SyncSlots.localNameFor(op.fileName, op.slot))
        assertEquals(
            "Super Mario World (USA).srm",
            SyncSlots.localNameFor("Super Mario World (USA) [2026-10-18_10-00-00].srm", null),
        )
    }
}
