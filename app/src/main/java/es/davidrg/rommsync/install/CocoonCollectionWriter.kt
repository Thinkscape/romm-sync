package es.davidrg.rommsync.install

import android.content.Context
import android.util.Log
import es.davidrg.rommsync.core.collections.CollectionMember
import es.davidrg.rommsync.core.collections.DeviceCollection
import es.davidrg.rommsync.core.collections.LauncherCollectionWriter
import java.io.File
import java.security.MessageDigest

/**
 * RomM collection → Cocoon home-screen folder of the same name.
 *
 * Cocoon 3.07 keeps folders only in its private Room database (`folders`
 * table) and exposes no import, intent or file format for them, so the folder
 * is a **Custom smart folder** (regex over game file names, see
 * [CocoonFolderPattern]) created and edited through Cocoon's own UI by
 * [CocoonRescanService]. This writer only records the wish in the
 * [CocoonFolderLedger]; the accessibility flow applies it the next time
 * Cocoon's home screen is idle (never over a game).
 *
 * Cocoon folders have no description field: RomM's description is not shown.
 * The cover becomes the folder icon when the flow can upload it; local edits
 * (pattern, icon) win over RomM, see [CocoonFolderLedger.plan].
 */
class CocoonCollectionWriter(private val context: Context) : LauncherCollectionWriter {

    override fun write(collection: DeviceCollection, members: List<CollectionMember>, cover: File?) {
        if (!CocoonAutomation.isEnabled(context)) return
        val pattern = CocoonFolderPattern.build(members.flatMap { CocoonFolderPattern.namesFor(it.files) })
        val source = cover?.takeIf { it.isFile && it.length() > 0 }
        val coverSha1 = source?.let(::sha1)
        val visible = if (source != null && coverSha1 != null) publishCover(collection.key, source, coverSha1) else null
        val desired = CocoonFolderLedger.Desired(
            name = collection.name.trim(),
            pattern = pattern,
            coverPath = visible?.absolutePath,
            coverSha1 = visible?.let { coverSha1 },
        )
        val needed = CocoonCollections.ledger(context).want(collection.key, desired)
        Log.i(TAG, "collection ${collection.key} '${desired.name}': ${members.size} members, UI pass needed=$needed")
        if (needed) CocoonCollections.onFolderWorkPending(context)
    }

    override fun remove(key: String, name: String) {
        if (!CocoonAutomation.isEnabled(context)) return
        val needed = CocoonCollections.ledger(context).forget(key)
        Log.i(TAG, "collection $key '$name' left RomM; folder removal needed=$needed")
        coversFor(key).forEach { it.delete() }
        if (needed) CocoonCollections.onFolderWorkPending(context)
    }

    /**
     * Copies the cover to `$SD/Cocoon/romm-covers/<kind>-<id>.<ext>`, a folder the
     * system file picker can show (Cocoon's "Upload Icon" only takes a picked
     * document). Null when Cocoon's data folder is not found.
     */
    private fun publishCover(key: String, cover: File, sha1: String): File? {
        val dir = coversDir() ?: return null
        if (!dir.isDirectory && !dir.mkdirs()) {
            Log.w(TAG, "cannot create ${dir.path}; folder icon skipped")
            return null
        }
        val target = File(dir, "${coverStem(key)}.${cover.extension.lowercase().ifEmpty { "png" }}")
        if (target.isFile && sha1(target) == sha1) return target
        coversFor(key).forEach { it.delete() }
        return runCatching { cover.copyTo(target, overwrite = true) }
            .onFailure { Log.w(TAG, "cannot publish cover for $key: ${it.message}") }
            .getOrNull()
    }

    private fun coversDir(): File? = CocoonCollections.cocoonDataDir(context)?.let { File(it, CocoonCollections.COVERS_DIR) }

    private fun coversFor(key: String): List<File> {
        val stem = coverStem(key)
        return coversDir()?.listFiles()?.filter { it.isFile && it.nameWithoutExtension == stem }.orEmpty()
    }

    private fun coverStem(key: String) = key.replace(Regex("[^A-Za-z0-9_-]"), "-")

    private fun sha1(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val TAG = "CocoonCollections"
    }
}
