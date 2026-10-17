package es.davidrg.rommsync.desktop

import kotlin.system.exitProcess

/**
 * Entry point. Only the GUI branch touches Compose/AWT (in `GuiMain.kt`);
 * `--daemon` and `--pair` run headless (systemd, SSH).
 */
fun main(args: Array<String>) {
    when (val command = Cli.parse(args)) {
        Command.Gui -> runGui()
        Command.Daemon -> exitProcess(DaemonMain.run())
        is Command.Pair -> exitProcess(DaemonMain.pair(command.name, command.url))
        Command.Help -> println(Cli.USAGE)
        is Command.Invalid -> {
            System.err.println("romm-sync: ${command.message}")
            System.err.println(Cli.USAGE)
            exitProcess(64)
        }
    }
}
