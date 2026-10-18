package es.davidrg.rommsync.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.TimeUnit

/*
 * Makes a running ES-DE show games the daemon just installed.
 *
 * ES-DE (3.5) has no IPC, no file watcher and no reload signal: the ROM folders and
 * gamelists are read at startup and by Main menu → Utilities → Rescan ROM directory,
 * which rebuilds every view and returns to the start view. SDL turns SIGTERM into
 * SDL_QUIT, so ES-DE shuts down cleanly ("ES-DE cleanly shutting down") and a relaunch
 * is equivalent to a rescan. ES-DE runs games as blocking child processes
 * (RunInBackground=false), so "no child process" means it sits in its UI.
 *
 * Restart rules:
 *  - never while ES-DE has a child (a game/emulator) or any other Steam game runs;
 *    wait and retry instead;
 *  - launched by Steam (Game Mode, or a Steam shortcut in Desktop Mode: `SteamGameId`
 *    in its environment): let the Steam "game" end, then `steam steam://rungameid/<id>`;
 *  - otherwise relaunch the same AppImage/arguments detached via `systemd-run --user`
 *    with the display environment of the old process.
 */

/** One process as read from `/proc`. [startedAtMs] is epoch milliseconds. */
data class ProcInfo(
    val pid: Long,
    val ppid: Long,
    val comm: String,
    val startedAtMs: Long,
    val cmdline: List<String> = emptyList(),
)

/** OS access used by [LauncherRefresher]; [LinuxProcessSystem] in production, fakes in tests. */
interface ProcessSystem {
    fun list(): List<ProcInfo>
    fun environ(pid: Long): Map<String, String>
    fun isAlive(pid: Long): Boolean

    /** Sends SIGTERM; false if the signal could not be delivered. */
    fun terminate(pid: Long): Boolean

    /** Starts [command] outside the daemon's cgroup so a daemon restart does not kill it. */
    fun spawnDetached(command: List<String>, env: Map<String, String>): Boolean
    fun nowMs(): Long
    fun sleep(ms: Long)
}

sealed interface EsdeState {
    data object NotRunning : EsdeState

    data class Busy(val reason: String) : EsdeState

    /** [steamReaper] is the Steam `reaper` ancestor when ES-DE was started by Steam. */
    data class Idle(val esde: ProcInfo, val steamReaper: ProcInfo?) : EsdeState
}

sealed interface RefreshOutcome {
    data class Refreshed(val method: String) : RefreshOutcome

    data class Skipped(val reason: String) : RefreshOutcome

    /** Try again later (a game is running). */
    data class Deferred(val reason: String) : RefreshOutcome
}

object EsdeProcessPolicy {
    private val ESDE_NAMES = setOf("es-de", "emulationstatio") // comm is capped at 15 chars

    /** Classifies the current process table. */
    fun inspect(procs: List<ProcInfo>): EsdeState {
        val esde = procs.filter { it.comm in ESDE_NAMES }.maxByOrNull { it.startedAtMs }
            ?: return EsdeState.NotRunning
        val byPid = procs.associateBy { it.pid }
        val children = procs.groupBy { it.ppid }

        descendants(esde.pid, children).firstOrNull()?.let {
            return EsdeState.Busy("game running: ${it.comm}")
        }
        val ancestors = generateSequence(byPid[esde.ppid]) { byPid[it.ppid] }.take(64).toList()
        val ownReaper = ancestors.firstOrNull(::isSteamReaper)
        procs.firstOrNull { isSteamReaper(it) && it.pid != ownReaper?.pid }?.let {
            return EsdeState.Busy("steam game running: ${steamAppId(it) ?: it.pid}")
        }
        return EsdeState.Idle(esde, ownReaper)
    }

    private fun descendants(pid: Long, children: Map<Long, List<ProcInfo>>): List<ProcInfo> {
        val out = mutableListOf<ProcInfo>()
        val queue = ArrayDeque(children[pid].orEmpty())
        while (queue.isNotEmpty() && out.size < 1024) {
            val p = queue.removeFirst()
            out += p
            queue += children[p.pid].orEmpty()
        }
        return out
    }

    /** Steam starts every game (and non-Steam shortcut) under `reaper SteamLaunch AppId=<id> …`. */
    fun isSteamReaper(p: ProcInfo): Boolean = p.comm == "reaper" && p.cmdline.any { it == "SteamLaunch" }

    private fun steamAppId(p: ProcInfo): String? =
        p.cmdline.firstOrNull { it.startsWith("AppId=") }?.removePrefix("AppId=")
}

class LauncherRefresher(
    private val system: ProcessSystem,
    private val exitTimeoutMs: Long = 20_000,
    private val steamGameEndTimeoutMs: Long = 60_000,
    private val pollMs: Long = 250,
) {
    /** Restarts an idle ES-DE that was running before [lastInstallAtMs]. */
    fun refresh(lastInstallAtMs: Long): RefreshOutcome {
        val idle = when (val state = EsdeProcessPolicy.inspect(system.list())) {
            EsdeState.NotRunning -> return RefreshOutcome.Skipped("es-de not running")
            is EsdeState.Busy -> return RefreshOutcome.Deferred(state.reason)
            is EsdeState.Idle -> state
        }
        val esde = idle.esde
        if (esde.startedAtMs >= lastInstallAtMs) return RefreshOutcome.Skipped("es-de started after the install")

        val env = system.environ(esde.pid)
        val steamGameId = env["SteamGameId"]?.takeIf { it.isNotBlank() && it != "0" }
        val relaunch: () -> Boolean
        val method: String
        if (steamGameId != null) {
            if (system.list().none { it.comm == "steam" }) return RefreshOutcome.Skipped("steam not running")
            method = "restart via steam://rungameid/$steamGameId"
            relaunch = {
                // Steam ignores a launch while the previous run of the shortcut is alive;
                // EmuDeck's es-de.sh keeps it alive for a while after ES-DE exits.
                idle.steamReaper?.let { waitGone(it.pid, steamGameEndTimeoutMs) }
                system.spawnDetached(listOf("steam", "steam://rungameid/$steamGameId"), emptyMap())
            }
        } else {
            val command = relaunchCommand(esde, env) ?: return RefreshOutcome.Skipped("unknown es-de command line")
            method = "restart"
            relaunch = { system.spawnDetached(command, env.filterKeys { it in DISPLAY_ENV }) }
        }

        if (!system.terminate(esde.pid)) return RefreshOutcome.Skipped("cannot signal es-de (pid ${esde.pid})")
        if (!waitGone(esde.pid, exitTimeoutMs)) {
            return RefreshOutcome.Skipped("es-de did not exit within ${exitTimeoutMs / 1000} s of SIGTERM")
        }
        return if (relaunch()) RefreshOutcome.Refreshed(method) else RefreshOutcome.Skipped("relaunch failed ($method)")
    }

    private fun waitGone(pid: Long, timeoutMs: Long): Boolean {
        val deadline = system.nowMs() + timeoutMs
        while (system.isAlive(pid)) {
            if (system.nowMs() >= deadline) return false
            system.sleep(pollMs)
        }
        return true
    }

    companion object {
        /** Environment carried over to a Desktop Mode relaunch (display + session bus). */
        val DISPLAY_ENV = setOf(
            "DISPLAY", "WAYLAND_DISPLAY", "XAUTHORITY", "XDG_RUNTIME_DIR", "XDG_SESSION_TYPE",
            "XDG_CURRENT_DESKTOP", "DBUS_SESSION_BUS_ADDRESS",
        )

        /**
         * The AppImage the process came from (`APPIMAGE`; its own argv[0] points into a
         * temporary mount that disappears on exit) plus the original arguments.
         */
        fun relaunchCommand(esde: ProcInfo, env: Map<String, String>): List<String>? {
            val exe = env["APPIMAGE"]?.takeIf { it.isNotBlank() } ?: esde.cmdline.firstOrNull()?.takeIf { it.isNotBlank() }
            return exe?.let { listOf(it) + esde.cmdline.drop(1) }
        }
    }
}

/**
 * Debounces install notifications into one refresh per batch and retries while a game
 * runs. Logs `launcher: es-de refreshed (<method>)` or `launcher: skipped (<reason>)`.
 */
class LauncherRefreshScheduler(
    private val refresher: LauncherRefresher,
    private val enabled: () -> Boolean,
    private val log: (String) -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val debounceMs: Long = 5_000,
    private val retryMs: Long = 15_000,
    private val maxWaitMs: Long = 6 * 60 * 60_000L,
) {
    private val installs = Channel<Long>(Channel.UNLIMITED)

    /** Call after an install that added files under the ROM root. */
    fun installFinished() {
        installs.trySend(nowMs())
    }

    fun start(scope: CoroutineScope): Job = scope.launch {
        for (first in installs) {
            var lastInstall = first
            while (true) {
                lastInstall = withTimeoutOrNull(debounceMs) { installs.receive() } ?: break
            }
            if (!enabled()) {
                log("launcher: skipped (disabled: launcher.refresh_on_install=false)")
                continue
            }
            val deadline = nowMs() + maxWaitMs
            var waitingFor: String? = null
            while (true) {
                val outcome = runCatching { refresher.refresh(lastInstall) }
                    .getOrElse { RefreshOutcome.Skipped("${it.javaClass.simpleName}: ${it.message}") }
                when (outcome) {
                    is RefreshOutcome.Refreshed -> log("launcher: es-de refreshed (${outcome.method})")
                    is RefreshOutcome.Skipped -> log("launcher: skipped (${outcome.reason})")
                    is RefreshOutcome.Deferred -> {
                        if (nowMs() >= deadline) {
                            log("launcher: skipped (${outcome.reason} for ${maxWaitMs / 60_000} min)")
                        } else {
                            if (waitingFor != outcome.reason) log("launcher: waiting (${outcome.reason})")
                            waitingFor = outcome.reason
                            delay(retryMs)
                            // Installs that landed meanwhile join this refresh.
                            while (true) {
                                lastInstall = installs.tryReceive().getOrNull() ?: break
                            }
                            continue
                        }
                    }
                }
                break
            }
        }
    }
}

/** [ProcessSystem] over Linux `/proc`, `kill` and `systemd-run --user`. */
class LinuxProcessSystem(private val proc: File = File("/proc")) : ProcessSystem {
    private val bootTimeMs: Long by lazy {
        runCatching {
            File(proc, "stat").readLines().first { it.startsWith("btime ") }.substringAfter(' ').trim().toLong() * 1000
        }.getOrDefault(0L)
    }

    override fun list(): List<ProcInfo> = proc.listFiles().orEmpty().mapNotNull { dir ->
        val pid = dir.name.toLongOrNull() ?: return@mapNotNull null
        val stat = runCatching { File(dir, "stat").readText() }.getOrNull() ?: return@mapNotNull null
        val cmdline = runCatching { File(dir, "cmdline").readBytes() }.getOrDefault(ByteArray(0))
        parseStat(pid, stat, bootTimeMs)?.copy(cmdline = splitNul(cmdline))
    }

    override fun environ(pid: Long): Map<String, String> =
        runCatching { File(proc, "$pid/environ").readBytes() }.getOrDefault(ByteArray(0))
            .let(::splitNul)
            .mapNotNull { kv -> kv.indexOf('=').takeIf { it > 0 }?.let { kv.substring(0, it) to kv.substring(it + 1) } }
            .toMap()

    override fun isAlive(pid: Long): Boolean = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

    override fun terminate(pid: Long): Boolean = ProcessHandle.of(pid).map { it.destroy() }.orElse(false)

    override fun spawnDetached(command: List<String>, env: Map<String, String>): Boolean {
        val unit = "romm-sync-launcher-${System.currentTimeMillis()}"
        val viaSystemd = listOf("systemd-run", "--user", "--collect", "--quiet", "--unit=$unit") +
            env.map { (k, v) -> "--setenv=$k=$v" } + command
        if (run(viaSystemd, emptyMap())) return true
        // No user systemd: start it directly (dies with the daemon's cgroup on restart).
        return runCatching {
            ProcessBuilder(command).apply {
                environment().putAll(env)
                redirectOutput(ProcessBuilder.Redirect.DISCARD)
                redirectError(ProcessBuilder.Redirect.DISCARD)
            }.start()
            true
        }.getOrDefault(false)
    }

    private fun run(command: List<String>, env: Map<String, String>): Boolean = runCatching {
        val p = ProcessBuilder(command).apply {
            environment().putAll(env)
            redirectOutput(ProcessBuilder.Redirect.DISCARD)
            redirectError(ProcessBuilder.Redirect.DISCARD)
        }.start()
        p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0
    }.getOrDefault(false)

    override fun nowMs(): Long = System.currentTimeMillis()

    override fun sleep(ms: Long) = Thread.sleep(ms)

    companion object {
        private const val CLOCK_TICKS_PER_SECOND = 100L // USER_HZ on Linux x86_64/arm64

        /** Parses `/proc/<pid>/stat`; `comm` may contain spaces and parentheses. */
        internal fun parseStat(pid: Long, stat: String, bootTimeMs: Long): ProcInfo? {
            val open = stat.indexOf('(')
            val close = stat.lastIndexOf(')')
            if (open < 0 || close < open) return null
            val fields = stat.substring(close + 1).trim().split(' ')
            // fields[0] is field 3 (state): ppid is field 4, starttime field 22.
            val ppid = fields.getOrNull(1)?.toLongOrNull() ?: return null
            val ticks = fields.getOrNull(19)?.toLongOrNull() ?: return null
            return ProcInfo(pid, ppid, stat.substring(open + 1, close), bootTimeMs + ticks * 1000 / CLOCK_TICKS_PER_SECOND)
        }

        internal fun splitNul(bytes: ByteArray): List<String> =
            String(bytes, Charsets.UTF_8).split('\u0000').filter { it.isNotEmpty() }
    }
}
