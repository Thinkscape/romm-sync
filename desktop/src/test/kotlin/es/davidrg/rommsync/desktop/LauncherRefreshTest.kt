package es.davidrg.rommsync.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherRefreshTest {

    private val steam = ProcInfo(5278, 4935, "steam", 1_000)
    private val plasma = ProcInfo(4611, 1394, "plasmashell", 1_000)
    private val appImage = ProcInfo(50246, 1394, "ES-DE.AppImage", 10_000, listOf("/home/deck/Applications/ES-DE.AppImage"))
    private val squashfuse = ProcInfo(50249, 50246, "squashfuse", 10_000)
    private val esde = ProcInfo(50254, 50246, "es-de", 10_000, listOf("/tmp/.mount_ESDE123/usr/bin/es-de", "--no-splash"))
    private val desktopEnv = mapOf(
        "APPIMAGE" to "/home/deck/Applications/ES-DE.AppImage",
        "WAYLAND_DISPLAY" to "wayland-0",
        "DISPLAY" to ":0",
        "HOME" to "/home/deck",
    )

    private val reaper = ProcInfo(51349, 5278, "reaper", 10_000, listOf("/steam/reaper", "SteamLaunch", "AppId=2160832993", "--", "es-de.sh"))
    private val esdeSh = ProcInfo(51351, 51349, "bash", 10_000, listOf("bash", "es-de.sh"))
    private val steamAppImage = ProcInfo(51700, 51351, "ES-DE.AppImage", 10_000)
    private val steamEsde = ProcInfo(51706, 51700, "es-de", 10_000, listOf("/tmp/.mount_ESDE123/usr/bin/es-de"))
    private val steamEnv = desktopEnv + ("SteamGameId" to "9280707037086351360")

    private val desktopTree = listOf(steam, plasma, appImage, squashfuse, esde)
    private val steamTree = listOf(steam, plasma, reaper, esdeSh, steamAppImage, steamEsde)

    // ── EsdeProcessPolicy ───────────────────────────────────────────────

    @Test
    fun `no es-de process is not running`() {
        assertEquals(EsdeState.NotRunning, EsdeProcessPolicy.inspect(listOf(steam, plasma)))
    }

    @Test
    fun `es-de without children is idle`() {
        assertEquals(EsdeState.Idle(esde, null), EsdeProcessPolicy.inspect(desktopTree))
    }

    @Test
    fun `squashfuse next to es-de does not count as a game`() {
        val state = EsdeProcessPolicy.inspect(desktopTree)
        assertTrue(state is EsdeState.Idle)
    }

    @Test
    fun `an emulator below es-de makes it busy`() {
        val sh = ProcInfo(60000, esde.pid, "sh", 20_000)
        val retroarch = ProcInfo(60001, sh.pid, "retroarch", 20_000)
        assertEquals(EsdeState.Busy("game running: sh"), EsdeProcessPolicy.inspect(desktopTree + sh + retroarch))
    }

    @Test
    fun `steam launched es-de reports its own reaper and stays idle`() {
        assertEquals(EsdeState.Idle(steamEsde, reaper), EsdeProcessPolicy.inspect(steamTree))
    }

    @Test
    fun `another steam game makes es-de busy`() {
        val game = ProcInfo(70000, steam.pid, "reaper", 20_000, listOf("/steam/reaper", "SteamLaunch", "AppId=1145360", "--", "game"))
        assertEquals(EsdeState.Busy("steam game running: 1145360"), EsdeProcessPolicy.inspect(desktopTree + game))
        assertEquals(EsdeState.Busy("steam game running: 1145360"), EsdeProcessPolicy.inspect(steamTree + game))
    }

    @Test
    fun `newest es-de wins when several exist`() {
        val newer = esde.copy(pid = 90000, startedAtMs = 99_000)
        assertEquals(EsdeState.Idle(newer, null), EsdeProcessPolicy.inspect(desktopTree + newer))
    }

    // ── LauncherRefresher ───────────────────────────────────────────────

    @Test
    fun `desktop restart relaunches the appimage with the original args and display env`() {
        val sys = FakeSystem(desktopTree, mapOf(esde.pid to desktopEnv))
        val outcome = LauncherRefresher(sys).refresh(lastInstallAtMs = 20_000)
        assertEquals(RefreshOutcome.Refreshed("restart"), outcome)
        assertEquals(listOf(esde.pid), sys.terminated)
        val (command, env) = sys.spawned.single()
        assertEquals(listOf("/home/deck/Applications/ES-DE.AppImage", "--no-splash"), command)
        assertEquals(mapOf("WAYLAND_DISPLAY" to "wayland-0", "DISPLAY" to ":0"), env)
    }

    @Test
    fun `steam restart waits for the steam game to end then uses rungameid`() {
        val sys = FakeSystem(steamTree, mapOf(steamEsde.pid to steamEnv))
        // EmuDeck's es-de.sh outlives ES-DE: reaper ends 5 s after the SIGTERM.
        sys.onTerminate = { pid -> sys.dieAt[pid] = sys.now + 1_000; sys.dieAt[reaper.pid] = sys.now + 5_000 }
        val outcome = LauncherRefresher(sys).refresh(lastInstallAtMs = 20_000)
        assertEquals(RefreshOutcome.Refreshed("restart via steam://rungameid/9280707037086351360"), outcome)
        assertEquals(listOf("steam", "steam://rungameid/9280707037086351360"), sys.spawned.single().first)
        assertTrue("relaunch only after the reaper exited", sys.spawnedAt.single() >= sys.startNow + 5_000)
    }

    @Test
    fun `busy es-de is deferred and never signalled`() {
        val sh = ProcInfo(60000, esde.pid, "sh", 20_000)
        val sys = FakeSystem(desktopTree + sh, mapOf(esde.pid to desktopEnv))
        assertEquals(RefreshOutcome.Deferred("game running: sh"), LauncherRefresher(sys).refresh(30_000))
        assertTrue(sys.terminated.isEmpty())
        assertTrue(sys.spawned.isEmpty())
    }

    @Test
    fun `not running es-de is skipped`() {
        val sys = FakeSystem(listOf(steam), emptyMap())
        assertEquals(RefreshOutcome.Skipped("es-de not running"), LauncherRefresher(sys).refresh(30_000))
    }

    @Test
    fun `es-de started after the install is left alone`() {
        val sys = FakeSystem(desktopTree, mapOf(esde.pid to desktopEnv))
        assertEquals(RefreshOutcome.Skipped("es-de started after the install"), LauncherRefresher(sys).refresh(lastInstallAtMs = 5_000))
        assertTrue(sys.terminated.isEmpty())
    }

    @Test
    fun `es-de that ignores sigterm is not relaunched`() {
        val sys = FakeSystem(desktopTree, mapOf(esde.pid to desktopEnv))
        sys.onTerminate = {} // never dies
        val outcome = LauncherRefresher(sys, exitTimeoutMs = 3_000).refresh(30_000)
        assertEquals(RefreshOutcome.Skipped("es-de did not exit within 3 s of SIGTERM"), outcome)
        assertTrue(sys.spawned.isEmpty())
    }

    @Test
    fun `steam shortcut without a running steam is skipped`() {
        val sys = FakeSystem(steamTree - steam, mapOf(steamEsde.pid to steamEnv))
        assertEquals(RefreshOutcome.Skipped("steam not running"), LauncherRefresher(sys).refresh(30_000))
        assertTrue(sys.terminated.isEmpty())
    }

    @Test
    fun `relaunch command falls back to argv when not an appimage`() {
        val flatpakLike = esde.copy(cmdline = listOf("/usr/bin/es-de", "--home", "/x"))
        assertEquals(listOf("/usr/bin/es-de", "--home", "/x"), LauncherRefresher.relaunchCommand(flatpakLike, emptyMap()))
        assertNull(LauncherRefresher.relaunchCommand(esde.copy(cmdline = emptyList()), emptyMap()))
    }

    // ── ES-DE file edits ────────────────────────────────────────────────

    @Test
    fun `edit runs at once when es-de is not running`() {
        val sys = FakeSystem(listOf(steam), emptyMap())
        var edited = false
        val outcome = LauncherRefresher(sys).refresh(30_000, edit = { edited = true })
        assertTrue(edited)
        assertEquals(RefreshOutcome.Refreshed("edited while es-de not running"), outcome)
        assertTrue(sys.spawned.isEmpty())
    }

    @Test
    fun `edit waits while a game runs`() {
        val sh = ProcInfo(60000, esde.pid, "sh", 20_000)
        val sys = FakeSystem(desktopTree + sh, mapOf(esde.pid to desktopEnv))
        var edited = false
        val outcome = LauncherRefresher(sys).refresh(30_000, edit = { edited = true })
        assertEquals(RefreshOutcome.Deferred("game running: sh"), outcome)
        assertFalse(edited)
        assertTrue(sys.terminated.isEmpty())
    }

    @Test
    fun `edit runs between stopping and relaunching an idle es-de even if it started after the install`() {
        val sys = FakeSystem(desktopTree, mapOf(esde.pid to desktopEnv))
        var esdeAliveDuringEdit: Boolean? = null
        val outcome = LauncherRefresher(sys).refresh(lastInstallAtMs = 5_000, edit = { esdeAliveDuringEdit = sys.isAlive(esde.pid) })
        assertEquals(RefreshOutcome.Refreshed("restart"), outcome)
        assertEquals(false, esdeAliveDuringEdit)
        assertEquals(1, sys.spawned.size)
    }

    @Test
    fun `edit with refresh disabled waits for es-de to exit`() {
        val sys = FakeSystem(desktopTree, mapOf(esde.pid to desktopEnv))
        var edited = false
        val outcome = LauncherRefresher(sys).refresh(30_000, edit = { edited = true }, mayRestart = false)
        assertTrue(outcome is RefreshOutcome.Deferred)
        assertFalse(edited)
        assertTrue(sys.terminated.isEmpty())
    }

    // ── /proc parsing ───────────────────────────────────────────────────

    @Test
    fun `parses stat with spaces and parentheses in comm`() {
        val stat = "51706 (weird (name) x) S 51700 51706 51351 0 -1 4194560 1 0 0 0 5 3 0 0 20 0 9 0 12345 2314488 123 0"
        val info = LinuxProcessSystem.parseStat(51706, stat, bootTimeMs = 1_000_000)!!
        assertEquals(51700L, info.ppid)
        assertEquals("weird (name) x", info.comm)
        assertEquals(1_000_000 + 123_450L, info.startedAtMs)
    }

    @Test
    fun `splits nul separated cmdline`() {
        assertEquals(listOf("reaper", "SteamLaunch", "AppId=1"), LinuxProcessSystem.splitNul("reaper\u0000SteamLaunch\u0000AppId=1\u0000".toByteArray()))
    }

    /** Clock advances only through [sleep]; terminated processes die after 1 s unless [onTerminate] says otherwise. */
    private class FakeSystem(procs: List<ProcInfo>, private val envs: Map<Long, Map<String, String>>) : ProcessSystem {
        val startNow = 100_000L
        var now = startNow
        val alive = procs.toMutableList()
        val dieAt = mutableMapOf<Long, Long>()
        val terminated = mutableListOf<Long>()
        val spawned = mutableListOf<Pair<List<String>, Map<String, String>>>()
        val spawnedAt = mutableListOf<Long>()
        var onTerminate: (Long) -> Unit = { pid -> dieAt[pid] = now + 1_000 }

        override fun list() = alive.toList()
        override fun environ(pid: Long) = envs[pid].orEmpty()
        override fun isAlive(pid: Long) = alive.any { it.pid == pid }
        override fun terminate(pid: Long): Boolean {
            terminated += pid
            onTerminate(pid)
            return true
        }
        override fun spawnDetached(command: List<String>, env: Map<String, String>): Boolean {
            spawned += command to env
            spawnedAt += now
            return true
        }
        override fun nowMs() = now
        override fun sleep(ms: Long) {
            now += ms
            alive.removeAll { p -> dieAt[p.pid]?.let { it <= now } == true }
        }
    }
}
