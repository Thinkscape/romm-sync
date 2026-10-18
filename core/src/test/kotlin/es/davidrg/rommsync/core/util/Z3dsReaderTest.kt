package es.davidrg.rommsync.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 3DS title ids from Azahar Z3DS containers must match the plain `.3ds`/`.cci` reading.
 *
 * `resources/z3ds/synthetic.zcci` / `synthetic.zcxi` were produced by the real Azahar CLI
 * (`azahar -c synthetic.cci -o …`, Azahar 2126.2 macOS) from the same synthetic image that
 * [plainCci] builds here: NCSD header at 0x100, partition 0 at 0x4000 with its NCCH header
 * (partition id = program id = [TITLE_ID], NoCrypto); `.zcxi` from partition 0 alone.
 */
class Z3dsReaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun write(name: String, bytes: ByteArray): File =
        tmp.newFile(name).apply { writeBytes(bytes) }

    private fun resource(name: String): File =
        write(name, requireNotNull(javaClass.getResourceAsStream("/z3ds/$name")) { name }.readBytes())

    // ── Real Azahar output ──────────────────────────────────────────────

    @Test
    fun `zcci from the Azahar CLI yields the same id as the plain cci`() {
        val plain = RomHeaderIdReader.readGameId(write("Game.3ds", plainCci()), "3ds")
        assertEquals(TITLE_HEX, plain)
        assertEquals(plain, RomHeaderIdReader.readGameId(resource("synthetic.zcci"), "3ds"))
    }

    @Test
    fun `zcxi from the Azahar CLI yields the title id`() {
        assertEquals(TITLE_HEX, RomHeaderIdReader.readGameId(resource("synthetic.zcxi"), "n3ds"))
    }

    // ── Containers built in-test ────────────────────────────────────────

    @Test
    fun `titleinfo found after other metadata items`() {
        val file = write(
            "Game.zcci",
            z3ds(
                "NCSD",
                item("compressor", "Azahar test".toByteArray()),
                item("date", "2026-10-18T00:00:00Z".toByteArray()),
                item("titleinfo", titleInfo(TITLE_ID)),
            ),
        )
        assertEquals(TITLE_HEX, RomHeaderIdReader.readGameId(file, "3ds"))
    }

    @Test
    fun `detection is by magic, not extension`() {
        val file = write("Game.3ds", z3ds("NCSD", item("titleinfo", titleInfo(TITLE_ID))))
        assertEquals(TITLE_HEX, RomHeaderIdReader.readGameId(file, "3ds"))
    }

    @Test
    fun `missing titleinfo returns null`() {
        val file = write("Game.zcci", z3ds("NCSD", item("compressor", "Azahar test".toByteArray())))
        assertNull(RomHeaderIdReader.readGameId(file, "3ds"))
    }

    @Test
    fun `non-retail title id returns null like the plain reader`() {
        val file = write("Game.zcci", z3ds("NCSD", item("titleinfo", titleInfo(0x0004000E00055D00L))))
        assertNull(RomHeaderIdReader.readGameId(file, "3ds"))
    }

    @Test
    fun `metadata item running past the block returns null`() {
        val broken = item("titleinfo", titleInfo(TITLE_ID)).also { it[2] = 0xFF.toByte(); it[3] = 0x7F }
        assertNull(RomHeaderIdReader.readGameId(write("Game.zcci", z3ds("NCSD", broken)), "3ds"))
    }

    @Test
    fun `truncated header returns null`() {
        assertNull(RomHeaderIdReader.readGameId(write("Game.zcci", "Z3DSNCSD".toByteArray()), "3ds"))
    }

    // ── Builders ────────────────────────────────────────────────────────

    private fun le(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    /** `Service::AM::TitleInfo`: tid, size, version, unused, type. */
    private fun titleInfo(tid: Long): ByteArray =
        le(0x18).putLong(tid).putLong(0x400).putShort(2).putShort(0).putInt(0).array()

    private fun item(name: String, data: ByteArray): ByteArray =
        le(4 + name.length + data.size)
            .put(ITEM_BINARY).put(name.length.toByte()).putShort(data.size.toShort())
            .put(name.toByteArray(Charsets.US_ASCII)).put(data).array()

    /** Z3DS header + metadata (version, items, end item, padded to 0x10) + a stand-in payload. */
    private fun z3ds(underlying: String, vararg items: ByteArray): ByteArray {
        val meta = ByteArrayOutputStream().apply {
            write(1)
            items.forEach { write(it) }
            write(ByteArray(4))
            write(ByteArray((0x10 - size() % 0x10) % 0x10))
        }.toByteArray()
        // Never decompressed by the reader: a zstd frame magic followed by filler.
        val payload = byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte()) + ByteArray(64)
        val header = le(0x20)
            .put("Z3DS".toByteArray()).put(underlying.toByteArray())
            .put(VERSION).put(0.toByte()).putShort(0x20).putInt(meta.size)
            .putLong(payload.size.toLong()).putLong(0x4400).array()
        return header + meta + payload
    }

    /** Same bytes as the image the resource fixtures were compressed from. */
    private fun plainCci(): ByteArray {
        val size = 0x4400
        val mediaUnit = 0x200
        val ncch = 0x4000
        val b = le(size)
        b.put(0x100, "NCSD".toByteArray()).putInt(0x104, size / mediaUnit).putLong(0x108, TITLE_ID)
        b.putInt(0x120, ncch / mediaUnit).putInt(0x124, (size - ncch) / mediaUnit)
        b.put(ncch + 0x100, "NCCH".toByteArray()).putInt(ncch + 0x104, (size - ncch) / mediaUnit)
        b.putLong(ncch + 0x108, TITLE_ID).put(ncch + 0x110, "01".toByteArray()).putShort(ncch + 0x112, 2)
        b.putLong(ncch + 0x118, TITLE_ID).put(ncch + 0x150, "CTR-P-TEST".toByteArray())
        b.put(ncch + 0x188 + 5, 0x03.toByte()).put(ncch + 0x188 + 7, 0x04.toByte())
        return b.array()
    }

    private companion object {
        const val TITLE_ID = 0x0004000000055D00L
        const val TITLE_HEX = "0004000000055D00"
        const val ITEM_BINARY: Byte = 1
        const val VERSION: Byte = 1
    }
}
