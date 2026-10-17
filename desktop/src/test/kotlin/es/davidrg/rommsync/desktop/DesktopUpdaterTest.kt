package es.davidrg.rommsync.desktop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopUpdaterTest {

    @Test
    fun `fork prerelease suffix orders numerically`() {
        assertTrue(DesktopUpdater.isNewer("1.0.0-thinkscape.2", "1.0.0-thinkscape.1"))
        assertTrue(DesktopUpdater.isNewer("1.0.0-thinkscape.10", "1.0.0-thinkscape.2"))
        assertFalse(DesktopUpdater.isNewer("1.0.0-thinkscape.1", "1.0.0-thinkscape.1"))
        assertFalse(DesktopUpdater.isNewer("v1.0.0-thinkscape.1", "1.0.0-thinkscape.2"))
    }

    @Test
    fun `core version and release precedence`() {
        assertTrue(DesktopUpdater.isNewer("1.0.1-thinkscape.1", "1.0.0-thinkscape.9"))
        assertTrue(DesktopUpdater.isNewer("1.0.0", "1.0.0-thinkscape.3"))
        assertFalse(DesktopUpdater.isNewer("0.8.6", "1.0.0-thinkscape.1"))
    }
}
