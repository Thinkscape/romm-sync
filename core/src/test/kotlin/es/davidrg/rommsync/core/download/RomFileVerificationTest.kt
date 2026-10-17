package es.davidrg.rommsync.core.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RomFileVerificationTest {

    @Test
    fun `archive hashes are not comparable with served bytes`() {
        for (name in listOf("a.zip", "A.ZIP", "a.7z", "a.rar", "a.tar", "a.tar.gz", "a.tgz", "a.tar.bz2", "a.tbz2", "a.tar.xz", "a.txz")) {
            assertNull(name, RomFileVerification.expectedSha1(name, "abc"))
            assertNull(name, RomFileVerification.expectedMd5(name, "abc"))
        }
    }

    @Test
    fun `raw files keep their hash`() {
        assertEquals("abc", RomFileVerification.expectedSha1("Mario.nes", "abc"))
        assertEquals("abc", RomFileVerification.expectedMd5("Game.chd", "abc"))
        assertNull(RomFileVerification.expectedSha1("Mario.nes", " "))
        assertNull(RomFileVerification.expectedSha1("Mario.nes", null))
    }
}
