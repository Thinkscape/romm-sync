package es.davidrg.rommsync.data.sync

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import es.davidrg.rommsync.core.sync.SavePathStaging
import es.davidrg.rommsync.core.sync.StagedPath
import es.davidrg.rommsync.core.util.RootShell
import java.io.File

/**
 * Punto único de staging de rutas de saves de la app Android. En orden:
 * 1. Legible por File → sin copia (lo normal: /storage/emulated/0/...).
 * 2. Root (teléfonos rooteados) → [SavePathStaging] (cp con su).
 * 3. Concesión SAF guardada (Android 11/12 sin root, el mecanismo de
 *    ZArchiver) → [SafStaging] (ContentResolver).
 *
 * Si nada funciona se devuelve la ruta original: los avisos de ruta
 * ilegible se encargan de explicarlo.
 */
object SavePathAccess {

    fun stage(path: String, stageRoot: File, context: Context): StagedPath {
        val direct = File(path)
        if (direct.isDirectory && direct.listFiles() != null) return StagedPath(path, direct)

        val rootStaged = SavePathStaging.stage(path, stageRoot)
        if (rootStaged.isCopy) return rootStaged

        val treeUri = SafTreeStore(context).findTreeUriFor(path) ?: return StagedPath(path, direct)
        return SafStaging.stage(path, stageRoot, treeUri, context) ?: StagedPath(path, direct)
    }

    fun commit(staged: StagedPath, context: Context): Boolean {
        if (!staged.isCopy) return true
        if (staged.viaSaf) {
            val treeUri = SafTreeStore(context).findTreeUriFor(staged.original) ?: return false
            return SafStaging.commit(staged, treeUri, context)
        }
        return SavePathStaging.commit(staged)
    }

    fun cleanup(staged: StagedPath, context: Context) {
        if (!staged.isCopy) return
        if (staged.viaSaf) {
            // staged es local y de la app: borrado directo.
            if (!staged.dir.deleteRecursively()) staged.dir.delete()
        } else {
            SavePathStaging.cleanup(staged)
        }
    }

    /**
     * Removes [file] (inside [staged].dir) from the real save folder too:
     * [commit] only upserts, so a deletion in a staged copy would not reach the
     * original. Returns true when the file is gone from the original location.
     */
    fun delete(staged: StagedPath, file: File, context: Context): Boolean {
        val relative = file.relativeToOrNull(staged.dir)?.path
            ?.takeIf { it.isNotEmpty() && !it.startsWith("..") }
            ?: return false
        if (file.exists() && !file.deleteRecursively()) return false
        if (!staged.isCopy) return true
        if (!staged.viaSaf) {
            return RootShell.run("rm -rf ${RootShell.sq(File(staged.original, relative).path)}") != null
        }
        val treeUri = SafTreeStore(context).findTreeUriFor(staged.original) ?: return false
        val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) ?: return false
        val treePath = SafTreeStore.pathFromTreeUri(Uri.parse(treeUri))?.trimEnd('/') ?: return false
        val target = File(staged.original, relative).path
        if (!target.startsWith("$treePath/")) return false
        var cur: DocumentFile = root
        for (seg in target.removePrefix("$treePath/").split('/').filter { it.isNotEmpty() }) {
            cur = cur.findFile(seg) ?: return true // already absent
        }
        return cur.delete()
    }

    /** true si la ruta es utilizable: File, root o concesión SAF vigente. */
    fun isUsable(path: String, context: Context): Boolean {
        if (File(path).isDirectory) return true
        if (RootShell.available &&
            RootShell.run("test -d ${RootShell.sq(path)}") != null
        ) return true
        return SafTreeStore(context).hasGrantFor(path)
    }

    /**
     * true when [path] lies in another app's `Android/data` (or `Android/obb`)
     * on Android 13+: the system folder picker no longer lets the user grant
     * those trees, so without root they stay unreadable. Read-only check.
     */
    fun isSystemBlocked(path: String): Boolean =
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            (path.contains("/Android/data/") || path.contains("/Android/obb/")) &&
            !RootShell.available
}
