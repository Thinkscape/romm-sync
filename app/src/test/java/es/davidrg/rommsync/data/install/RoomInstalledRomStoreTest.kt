package es.davidrg.rommsync.data.install

import com.google.common.truth.Truth.assertThat
import es.davidrg.rommsync.core.remote.dto.RomDetailDto
import es.davidrg.rommsync.core.remote.dto.RomFileDto
import es.davidrg.rommsync.data.local.entity.DownloadedRomEntity
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RoomInstalledRomStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `pushed single-file rom is recorded like a manual download`() {
        val rom = File(tmp.newFolder("snes"), "Super Mario World (USA).sfc").apply { writeBytes(ByteArray(512)) }
        val dto = RomDetailDto(
            id = 55,
            platformSlug = "snes",
            fsName = "Super Mario World (USA).sfc",
            files = listOf(RomFileDto(id = 1, fileName = rom.name, fileSizeBytes = 512, sha1Hash = "abc")),
            platformId = 9,
            name = "Super Mario World",
        )

        val entity = RoomInstalledRomStore.toEntity(dto, rom, listOf(rom), existing = null)

        assertThat(entity.romId).isEqualTo(55)
        assertThat(entity.name).isEqualTo("Super Mario World")
        assertThat(entity.fileName).isEqualTo("Super Mario World (USA).sfc")
        assertThat(entity.platformId).isEqualTo(9)
        assertThat(entity.platformSlug).isEqualTo("snes")
        assertThat(entity.localPath).isEqualTo(rom.absolutePath)
        assertThat(entity.fileSizeBytes).isEqualTo(512L)
        assertThat(entity.fileHash).isEqualTo("abc")
    }

    @Test
    fun `re-push keeps per-game sync settings`() {
        val dir = tmp.newFolder("Game (USA).m3u")
        val disc = File(dir, "Game (USA) (Disc 1).chd").apply { writeBytes(ByteArray(10)) }
        val dto = RomDetailDto(id = 8, platformSlug = "psx", fsName = "Game (USA)")
        val existing = DownloadedRomEntity(
            romId = 8, name = "Game", fileName = "old", platformId = 4, platformSlug = "psx",
            localPath = "/old", fileSizeBytes = 1, savesPathOverride = "/custom", excludedFromSync = true,
        )

        val entity = RoomInstalledRomStore.toEntity(dto, dir, listOf(disc), existing)

        assertThat(entity.fileName).isEqualTo("Game (USA).m3u")
        assertThat(entity.name).isEqualTo("Game (USA)")
        assertThat(entity.platformId).isEqualTo(4)
        assertThat(entity.fileSizeBytes).isEqualTo(10L)
        assertThat(entity.savesPathOverride).isEqualTo("/custom")
        assertThat(entity.excludedFromSync).isTrue()
    }
}
