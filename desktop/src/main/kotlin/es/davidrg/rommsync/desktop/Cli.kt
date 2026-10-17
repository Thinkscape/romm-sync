package es.davidrg.rommsync.desktop

/** What the process was asked to do on the command line. */
sealed interface Command {
    /** No arguments: the Compose GUI. */
    data object Gui : Command

    /** `--daemon`: headless install listener + periodic save sync (systemd user service). */
    data object Daemon : Command

    /** `--pair [--name NAME] [--url URL]`: device pairing on the console. */
    data class Pair(val name: String?, val url: String?) : Command

    data object Help : Command

    data class Invalid(val message: String) : Command
}

object Cli {

    val USAGE = """
        |Usage: romm-sync [--daemon | --pair [--name NAME] [--url URL] | --help]
        |
        |  (no arguments)   start the desktop app
        |  --daemon         run headless: receive RomM pushes and sync saves every 10 min
        |                   (kill -USR1 <pid> syncs now); requires a paired device
        |  --pair           pair this device with RomM: prints a code to approve in the
        |                   RomM web UI and waits until it is approved
        |    --name NAME    device name shown in RomM (default: host name)
        |    --url URL      RomM server URL (default: the configured server)
        |  --help           show this help
    """.trimMargin()

    fun parse(args: Array<String>): Command {
        if (args.isEmpty()) return Command.Gui
        val rest = ArrayDeque(args.toList())
        val mode = rest.removeFirst()
        return when (mode) {
            "--help", "-h" -> if (rest.isEmpty()) Command.Help else Command.Invalid("unexpected argument: ${rest.first()}")
            "--daemon" -> if (rest.isEmpty()) Command.Daemon else Command.Invalid("unexpected argument: ${rest.first()}")
            "--pair" -> parsePair(rest)
            else -> Command.Invalid("unknown argument: $mode")
        }
    }

    private fun parsePair(rest: ArrayDeque<String>): Command {
        var name: String? = null
        var url: String? = null
        while (rest.isNotEmpty()) {
            val arg = rest.removeFirst()
            val key = arg.substringBefore('=')
            val inline = if ('=' in arg) arg.substringAfter('=') else null
            val value = inline ?: rest.removeFirstOrNull()
            when (key) {
                "--name" -> name = value?.takeIf { it.isNotBlank() } ?: return Command.Invalid("--name needs a value")
                "--url" -> url = value?.takeIf { it.isNotBlank() } ?: return Command.Invalid("--url needs a value")
                else -> return Command.Invalid("unknown argument: $arg")
            }
        }
        return Command.Pair(name, url)
    }
}
