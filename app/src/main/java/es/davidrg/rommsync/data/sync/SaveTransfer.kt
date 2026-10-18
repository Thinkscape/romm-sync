package es.davidrg.rommsync.data.sync

import es.davidrg.rommsync.core.remote.RomMApiService
import es.davidrg.rommsync.core.remote.dto.ServerAssetDto
import es.davidrg.rommsync.core.sync.RommContentHash
import es.davidrg.rommsync.core.sync.SyncSlots
import es.davidrg.rommsync.core.sync.platform.LocalSave
import es.davidrg.rommsync.core.sync.platform.SaveHandler
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.ResponseBody
import retrofit2.HttpException
import java.io.File
import java.security.MessageDigest

/**
 * Save/state transfers against RomM following the slot contract
 * ([SyncSlots]): uploads carry slot, emulator, content hash and device id;
 * downloads land at the canonical local name chosen by the caller, never at the
 * server's datetime-tagged file name. Pure JVM (no Android APIs) so it is unit
 * testable.
 */
class SaveTransfer(
    private val api: RomMApiService,
    private val cacheDir: File,
    private val log: (message: String, error: Throwable?) -> Unit = { _, _ -> },
) {

    /** The rom a transfer belongs to, as the save handlers need it (local path: header game id). */
    data class RomRef(
        val romId: Int,
        val fileName: String,
        val platformSlug: String,
        val romLocalPath: String? = null,
    )

    enum class UploadOutcome {
        OK,

        /** HTTP 409: the slot holds a newer save than this device last synced. */
        CONFLICT,
        FAILED,
    }

    /**
     * `POST /api/saves` with the slot contract parameters. [overwrite] is true
     * only for an explicit "keep local" conflict resolution.
     */
    suspend fun uploadSave(
        save: LocalSave,
        romId: Int,
        deviceId: String,
        emulator: String,
        handler: SaveHandler,
        overwrite: Boolean,
    ): UploadOutcome = try {
        val prepared = handler.prepareForUpload(save)
        val body = prepared.asRequestBody(OCTET_STREAM)
        api.uploadSave(
            file = MultipartBody.Part.createFormData("saveFile", save.fileName, body),
            romId = romId,
            emulator = emulator.take(SyncSlots.EMULATOR_MAX_LENGTH),
            deviceId = deviceId,
            slot = SyncSlots.slotFor(save),
            // RomM's own hash of the uploaded bytes (what negotiate sent), not the local sha1.
            contentHash = RommContentHash.of(prepared),
            overwrite = overwrite,
        ).close()
        UploadOutcome.OK
    } catch (e: HttpException) {
        log("Upload of ${save.fileName} rejected: HTTP ${e.code()}", e)
        if (e.code() == 409) UploadOutcome.CONFLICT else UploadOutcome.FAILED
    } catch (e: Exception) {
        log("Upload failed for ${save.fileName}", e)
        UploadOutcome.FAILED
    }

    /** `POST /api/states` (multipart part `stateFile`); returns the stored state or null. */
    suspend fun uploadState(
        state: LocalSave,
        romId: Int,
        emulator: String,
        handler: SaveHandler,
    ): ServerAssetDto? = try {
        val body = handler.prepareForUpload(state).asRequestBody(OCTET_STREAM)
        api.uploadState(
            file = MultipartBody.Part.createFormData("stateFile", state.fileName, body),
            romId = romId,
            emulator = emulator.take(SyncSlots.EMULATOR_MAX_LENGTH),
        )
    } catch (e: Exception) {
        log("State upload failed for ${state.fileName}", e)
        null
    }

    /**
     * Downloads save [saveId] and writes it as [targetName] through [handler].
     * Returns the sha1 of the downloaded content, or null on failure.
     */
    suspend fun downloadSave(
        saveId: Int,
        deviceId: String,
        targetName: String,
        rom: RomRef,
        savesBasePath: String,
        handler: SaveHandler,
    ): String? = download(targetName, rom, savesBasePath, handler) { api.downloadSave(saveId, deviceId) }

    /** Same as [downloadSave] for a save state. */
    suspend fun downloadState(
        stateId: Int,
        targetName: String,
        rom: RomRef,
        savesBasePath: String,
        handler: SaveHandler,
    ): String? = download(targetName, rom, savesBasePath, handler) { api.downloadState(stateId) }

    private suspend fun download(
        targetName: String,
        rom: RomRef,
        savesBasePath: String,
        handler: SaveHandler,
        fetch: suspend () -> ResponseBody,
    ): String? {
        val tempFile = File(cacheDir, "sync_dl_${rom.romId}_${System.nanoTime()}")
        return try {
            fetch().use { body ->
                tempFile.outputStream().use { out -> body.byteStream().use { it.copyTo(out) } }
            }
            val ok = handler.extractDownload(
                tempFile = tempFile,
                romFileName = rom.fileName,
                platformSlug = rom.platformSlug,
                savesBasePath = savesBasePath,
                targetFileName = targetName,
                romLocalPath = rom.romLocalPath,
            )
            if (ok) sha1(tempFile) else null
        } catch (e: Exception) {
            log("Download failed for $targetName (rom ${rom.romId})", e)
            null
        } finally {
            tempFile.delete()
        }
    }

    private companion object {
        val OCTET_STREAM = "application/octet-stream".toMediaType()

        fun sha1(file: File): String {
            val digest = MessageDigest.getInstance("SHA-1")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
