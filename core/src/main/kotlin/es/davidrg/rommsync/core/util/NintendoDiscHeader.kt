package es.davidrg.rommsync.core.util

/**
 * Locates the first bytes of a GameCube/Wii disc (the 6-char game ID lives at
 * offset 0: 4-char game code + 2-char maker code) inside Dolphin's container
 * formats, reading only container headers:
 * - **RVZ/WIA**: header 1 (0x48 bytes, magic "RVZ\u0001"/"WIA\u0001") is followed
 *   by header 2, whose `dhead` (copy of the first 0x80 disc bytes) starts at
 *   0x48 + 0x10 = 0x58.
 * - **WBFS**: magic "WBFS", byte 8 = log2 of the HD sector size; the disc
 *   header copy is stored at that sector size (512 in practice).
 * - **Dolphin CISO** (`.ciso`): magic "CISO", block size u32 LE @4, block map
 *   @8; data starts at 0x8000 when block 0 is present.
 * - Anything else (ISO/GCM/NKit): offset 0.
 */
internal object NintendoDiscHeader {
    private const val WIA_DHEAD_OFFSET = 0x58L
    private const val DOLPHIN_CISO_HEADER = 0x8000L

    /** The first [len] disc bytes, or null when the container is not recognised/readable. */
    fun read(src: ByteSource, len: Int = 8): ByteArray? {
        val magic = src.read(0, 12) ?: return null
        val tag = String(magic, 0, 4, Charsets.ISO_8859_1)
        val start = when (tag) {
            "RVZ\u0001", "WIA\u0001" -> WIA_DHEAD_OFFSET
            "WBFS" -> {
                val shift = magic[8].toInt() and 0xFF
                if (shift !in 9..16) return null
                1L shl shift
            }
            "CISO" -> {
                // Block 0 must be stored for the disc header to be present.
                if (magic[8].toInt() != 1) return null
                DOLPHIN_CISO_HEADER
            }
            else -> 0L
        }
        return src.read(start, len)
    }

    /**
     * Game ID from the disc header: 6 chars (`GAFE01`) when the maker code is
     * alphanumeric, else the 4-char game code; null when the code is not valid.
     */
    fun gameId(header: ByteArray): String? {
        if (header.size < 4) return null
        val code = String(header, 0, 4, Charsets.US_ASCII)
        if (!code.matches(GAME_CODE)) return null
        if (header.size >= 6) {
            val maker = String(header, 4, 2, Charsets.US_ASCII)
            if (maker.matches(MAKER_CODE)) return code + maker
        }
        return code
    }

    private val GAME_CODE = Regex("[A-Z0-9]{4}")
    private val MAKER_CODE = Regex("[A-Z0-9]{2}")
}
