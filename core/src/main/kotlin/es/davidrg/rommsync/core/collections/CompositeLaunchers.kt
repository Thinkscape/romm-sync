package es.davidrg.rommsync.core.collections

import java.io.File

/**
 * Fans collection writes out to several launchers (Cocoon and ES-DE on the Thor).
 * Every writer runs even when an earlier one throws; the first failure is rethrown
 * afterwards, so the reconciler keeps the collection unwritten and retries all of
 * them (writers are idempotent). [tag] becomes the [signatureTag].
 */
class CompositeCollectionWriter(
    private val writers: List<LauncherCollectionWriter>,
    private val tag: () -> String = { "" },
) : LauncherCollectionWriter {

    override fun write(collection: DeviceCollection, members: List<CollectionMember>, cover: File?) =
        runAll(writers) { it.write(collection, members, cover) }

    override fun remove(key: String, name: String) = runAll(writers) { it.remove(key, name) }

    override fun signatureTag(): String = tag()
}

/** Runs every remover; the first failure is rethrown after all of them ran. */
class CompositeGameRemover(private val removers: List<LauncherGameRemover>) : LauncherGameRemover {
    override fun onRomRemoved(romId: Int, platformFolder: String, files: List<File>) =
        runAll(removers) { it.onRomRemoved(romId, platformFolder, files) }
}

private fun <T> runAll(targets: List<T>, block: (T) -> Unit) {
    var failure: Exception? = null
    for (target in targets) {
        try {
            block(target)
        } catch (e: Exception) {
            val first = failure
            if (first == null) failure = e else first.addSuppressed(e)
        }
    }
    failure?.let { throw it }
}
