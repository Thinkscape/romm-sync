package es.davidrg.rommsync.core.sync

import es.davidrg.rommsync.core.remote.dto.ServerAssetDto
import es.davidrg.rommsync.core.remote.dto.SyncOperation
import es.davidrg.rommsync.core.sync.SyncSlots.StateAction
import es.davidrg.rommsync.core.sync.platform.LocalSave
import es.davidrg.rommsync.core.sync.platform.SaveKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.File

class SyncSlotsTest {

    private fun local(name: String, sha1: String = "aa", kind: SaveKind = SaveKind.SAVE) =
        LocalSave(romId = 1, fileName = name, file = File("/nonexistent/$name"), lastModified = 0L, sha1 = sha1, kind = kind)

    private fun remote(id: Int, name: String, emulator: String? = "retroarch", updatedAt: String = "2026-10-18T10:00:00Z", hash: String? = null) =
        ServerAssetDto(id = id, romId = 1, fileName = name, emulator = emulator, updatedAt = updatedAt, contentHash = hash, fileSizeBytes = 10)

    @Test
    fun `client save states carry slot and emulator and exclude states`() {
        val states = SyncSlots.clientSaveStates(
            mapOf(
                1 to listOf(local("Game.srm"), local("Game.state1", kind = SaveKind.STATE)),
                2 to listOf(local("Other.sav")),
            ),
            emulatorFor = { romId -> if (romId == 1) "retroarch" else "melonds" },
            contentHashOf = { "romm-${it.fileName}" },
        )

        assertEquals(listOf("Game.srm", "Other.sav"), states.map { it.fileName })
        assertEquals(listOf("Game.srm", "Other.sav"), states.map { it.slot })
        assertEquals(listOf("retroarch", "melonds"), states.map { it.emulator })
    }

    @Test
    fun `datetime tagged server name maps back to the canonical local name`() {
        assertEquals("Game (USA).srm", SyncSlots.localNameFor("Game (USA) [2026-10-18_10-00-00].srm", "Game (USA).srm"))
        assertEquals("Game (USA).srm", SyncSlots.localNameFor("Game (USA) [2026-10-18_10-00-00].srm", null))
        assertEquals("Game.state.auto", SyncSlots.localNameFor("Game.state [2026-10-18_10-00-00].auto", null))
        // Foreign slot names are not file names: fall back to the untagged server name.
        assertEquals("Game.srm", SyncSlots.localNameFor("Game [2026-10-18_10-00-00].srm", "autosave"))
        assertEquals("Game.srm", SyncSlots.localNameFor("Game [2026-10-18_10-00-00].srm", "../evil.srm"))
    }

    @Test
    fun `operations match local saves by slot`() {
        val saves = listOf(local("Game.srm"), local("Game.state", kind = SaveKind.STATE))
        val op = SyncOperation(action = "download", romId = 1, saveId = 9, fileName = "Game [2026-10-18_10-00-00].srm", slot = "Game.srm")
        assertSame(saves[0], SyncSlots.localSaveFor(op, saves))
        assertNull(SyncSlots.localSaveFor(op.copy(slot = null, fileName = "Other.srm"), saves))
    }

    @Test
    fun `state planning follows the last synced hash rule`() {
        val lastLocal = mapOf("A.state" to "a0", "B.state" to "b0", "C.state" to "c1")
        val r1 = remote(1, "A.state")
        val r2 = remote(2, "B.state")
        val r3 = remote(3, "C.state")
        val lastRemote = mapOf(
            "A.state" to SyncSlots.remoteMarker(r1),
            "B.state" to "old",
            "C.state" to "old",
        )
        val plan = SyncSlots.planStates(
            localStates = listOf(
                local("A.state", "a1", SaveKind.STATE), // only local changed
                local("B.state", "b0", SaveKind.STATE), // only remote changed
                local("C.state", "c2", SaveKind.STATE), // both changed
                local("D.state", "d", SaveKind.STATE), // local only
            ),
            remoteStates = listOf(r1, r2, r3, remote(4, "E.state"), remote(5, "F.state", emulator = "duckstation")),
            emulator = "retroarch",
            lastLocalHash = { lastLocal[it] },
            lastRemoteMarker = { lastRemote[it] },
        ).associate { it.name to it.action }

        assertEquals(
            mapOf(
                "A.state" to StateAction.UPLOAD,
                "B.state" to StateAction.DOWNLOAD,
                "C.state" to StateAction.CONFLICT,
                "D.state" to StateAction.UPLOAD,
                "E.state" to StateAction.DOWNLOAD,
            ),
            plan,
        )
    }

    @Test
    fun `newest remote state per name wins and equal hash is a no-op`() {
        val plan = SyncSlots.planStates(
            localStates = listOf(local("A.state", "abc", SaveKind.STATE)),
            remoteStates = listOf(
                remote(1, "A.state", updatedAt = "2026-10-17T10:00:00Z"),
                remote(2, "A.state", updatedAt = "2026-10-18T10:00:00Z", hash = "abc"),
            ),
            emulator = "retroarch",
            lastLocalHash = { null },
            lastRemoteMarker = { null },
            contentHashOf = { it.sha1 },
        ).single()
        assertEquals(2, plan.remote?.id)
        assertEquals(StateAction.NOOP, plan.action)
    }

    @Test
    fun `state uploaded then re-planned against the listing has no ops`() {
        val name = "Legend of Zelda, The (Europe) (Rev A).state.auto"
        val localState = local(name, "f41bef", SaveKind.STATE)
        val store = mutableMapOf<String, String>()

        val first = SyncSlots.planStates(listOf(localState), emptyList(), "retroarch", { null }, { null }).single()
        assertEquals(StateAction.UPLOAD, first.action)

        // POST /api/states response, recorded exactly as after a download.
        val uploaded = remote(1, name, updatedAt = "2026-10-17T23:40:53.418226Z").copy(fileSizeBytes = 8893)
        store["local"] = localState.sha1
        store["remote"] = SyncSlots.remoteMarker(uploaded)

        // GET /api/states renders the same row's time differently.
        val listed = uploaded.copy(updatedAt = "2026-10-17T23:40:53+00:00")
        val second = SyncSlots.planStates(listOf(localState), listOf(listed), "retroarch", { store["local"] }, { store["remote"] })
        assertEquals(listOf(StateAction.NOOP), second.map { it.action })
    }

    @Test
    fun `marker recorded before normalisation still matches`() {
        val r = remote(1, "A.state", updatedAt = "2026-10-17T23:40:53+00:00")
        assertTrue(SyncSlots.sameRemoteVersion("1:2026-10-17T23:40:53+00:00:10", r))
        assertTrue(SyncSlots.sameRemoteVersion("1:2026-10-17T23:40:53.5Z:10", r))
        assertFalse(SyncSlots.sameRemoteVersion("1:2026-10-17T23:41:00+00:00:10", r))
        assertFalse(SyncSlots.sameRemoteVersion(null, r))
    }

    @Test
    fun `unknown remote version with unchanged local of the same size is not downloaded`() {
        val file = File.createTempFile("state", ".auto").apply { writeBytes(ByteArray(10)); deleteOnExit() }
        val localState = LocalSave(1, "A.state.auto", file, 0L, "same", SaveKind.STATE)
        val op = SyncSlots.planStates(
            listOf(localState),
            listOf(remote(7, "A.state.auto")), // fileSizeBytes = 10
            "retroarch",
            lastLocalHash = { "same" },
            lastRemoteMarker = { "something-else" },
        ).single()
        assertEquals(StateAction.NOOP, op.action)
    }

    @Test
    fun `negotiated download is guarded by the local baseline`() {
        val srm = local("Zelda.srm", sha1 = "local-now")
        val romm = { _: LocalSave -> "romm-local" }

        // Changed here since the last sync while another device uploaded: conflict, file untouched.
        assertEquals(
            SyncSlots.DownloadGuard.CONFLICT,
            SyncSlots.guardDownload(srm, lastSyncedLocalHash = "local-before", serverContentHash = "romm-server", contentHashOf = romm),
        )
        // Never synced and different from the server: conflict too.
        assertEquals(
            SyncSlots.DownloadGuard.CONFLICT,
            SyncSlots.guardDownload(srm, lastSyncedLocalHash = null, serverContentHash = "romm-server", contentHashOf = romm),
        )
        // Unchanged since the last sync: take the server copy.
        assertEquals(
            SyncSlots.DownloadGuard.DOWNLOAD,
            SyncSlots.guardDownload(srm, lastSyncedLocalHash = "local-now", serverContentHash = "romm-server", contentHashOf = romm),
        )
        // Same content as the server: nothing to do.
        assertEquals(
            SyncSlots.DownloadGuard.NOOP,
            SyncSlots.guardDownload(srm, lastSyncedLocalHash = "local-before", serverContentHash = "ROMM-LOCAL", contentHashOf = romm),
        )
        // No local file: download.
        assertEquals(SyncSlots.DownloadGuard.DOWNLOAD, SyncSlots.guardDownload(null, null, "romm-server", romm))
    }
}
