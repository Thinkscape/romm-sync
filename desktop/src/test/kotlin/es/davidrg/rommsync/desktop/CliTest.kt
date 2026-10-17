package es.davidrg.rommsync.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CliTest {

    @Test
    fun `no arguments starts the gui`() {
        assertEquals(Command.Gui, Cli.parse(emptyArray()))
    }

    @Test
    fun `daemon and help`() {
        assertEquals(Command.Daemon, Cli.parse(arrayOf("--daemon")))
        assertEquals(Command.Help, Cli.parse(arrayOf("--help")))
        assertEquals(Command.Help, Cli.parse(arrayOf("-h")))
    }

    @Test
    fun `pair without options`() {
        assertEquals(Command.Pair(null, null), Cli.parse(arrayOf("--pair")))
    }

    @Test
    fun `pair with separate and inline values`() {
        assertEquals(
            Command.Pair("Steam Deck", "https://romm.example.com"),
            Cli.parse(arrayOf("--pair", "--name", "Steam Deck", "--url", "https://romm.example.com")),
        )
        assertEquals(
            Command.Pair("Deck", "https://r.example"),
            Cli.parse(arrayOf("--pair", "--url=https://r.example", "--name=Deck")),
        )
    }

    @Test
    fun `invalid input is reported`() {
        assertTrue(Cli.parse(arrayOf("--bogus")) is Command.Invalid)
        assertTrue(Cli.parse(arrayOf("--daemon", "extra")) is Command.Invalid)
        assertTrue(Cli.parse(arrayOf("--pair", "--name")) is Command.Invalid)
        assertTrue(Cli.parse(arrayOf("--pair", "--colour", "red")) is Command.Invalid)
    }
}
