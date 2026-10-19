package es.davidrg.rommsync.data.sync

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract

/**
 * Persiste las concesiones SAF (ACTION_OPEN_DOCUMENT_TREE) por ruta real:
 * ruta → treeUri.
 *
 * En Android 11/12 el selector del sistema SÍ permite elegir carpetas dentro
 * de `Android/data` (es el mecanismo que usa ZArchiver sin root; cerrado en
 * Android 13 y en parches de seguridad ≥ mar-2024). Este store alimenta al
 * staging ([SafStaging]), que copia el contenido vía ContentResolver para
 * que los handlers trabajen con la API File tal cual.
 *
 * On Android 13+ the trees of emulators that ship their own DocumentsProvider
 * (Dolphin, Eden: [EmulatorDocumentProviders]) are granted on that provider's
 * root instead; they are stored under the emulator's real user-dir path too.
 */
class SafTreeStore(context: Context) {

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("saf_trees", Context.MODE_PRIVATE)

    fun put(path: String, treeUri: String) {
        prefs.edit().putString(path.trimEnd('/'), treeUri).apply()
    }

    /** treeUri cuya ruta cubra [path] (prefijo por componentes); null si ninguna. */
    fun findTreeUriFor(path: String): String? {
        val p = path.trimEnd('/')
        var bestPath: String? = null
        var bestUri: String? = null
        for ((key, value) in prefs.all) {
            val treePath = key as? String ?: continue
            val uri = value as? String ?: continue
            val covers = p == treePath || p.startsWith("$treePath/")
            if (covers && (bestPath == null || treePath.length > bestPath!!.length)) {
                bestPath = treePath
                bestUri = uri
            }
        }
        return bestUri
    }

    /**
     * true si hay concesión guardada para [path] Y sigue vigente (el usuario
     * puede revocarla en Ajustes → Apps → Acceso a archivos).
     */
    fun hasGrantFor(path: String): Boolean {
        val tree = findTreeUriFor(path) ?: return false
        return runCatching {
            val uri = Uri.parse(tree)
            app.contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isReadPermission
            }
        }.getOrDefault(false)
    }

    companion object {
        private const val EXTERNAL_AUTHORITY = "com.android.externalstorage.documents"

        /**
         * URI de árbol para lanzar el selector directamente en [path]: la raíz
         * del DocumentsProvider del emulador cuando la ruta está en su carpeta de
         * usuario ([EmulatorDocumentProviders]; hoy el selector la ignora, ver
         * allí), si no la ruta en el volumen primario. Null si no aplica → el
         * selector abre en su ubicación por defecto.
         */
        fun initialTreeUri(path: String): Uri? {
            EmulatorDocumentProviders.forPath(path)?.let { provider ->
                return runCatching {
                    DocumentsContract.buildTreeDocumentUri(
                        provider.authority,
                        EmulatorDocumentProvider.ROOT_DOCUMENT_ID,
                    )
                }.getOrNull()
            }
            val base = Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')
            val p = path.trimEnd('/')
            if (p != base && !p.startsWith("$base/")) return null
            val rel = p.removePrefix(base).trim('/')
            return runCatching {
                DocumentsContract.buildTreeDocumentUri(
                    EXTERNAL_AUTHORITY,
                    if (rel.isEmpty()) "primary:" else "primary:$rel",
                )
            }.getOrNull()
        }

        /**
         * Real path of a granted tree: "primary:Android/data/x" →
         * "/storage/emulated/0/Android/data/x"; an emulator provider tree
         * ("root/GC" on `org.dolphinemu.dolphinemu.user`) → its user dir + "/GC".
         */
        fun pathFromTreeUri(uri: Uri): String? = runCatching {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            EmulatorDocumentProviders.forAuthority(uri.authority)?.let { provider ->
                val rel = provider.relativePathOf(docId) ?: return@runCatching null
                return@runCatching if (rel.isEmpty()) provider.userDir else "${provider.userDir}/$rel"
            }
            if (uri.authority != EXTERNAL_AUTHORITY || !docId.startsWith("primary:")) return@runCatching null
            val rel = docId.removePrefix("primary:").trim('/')
            val base = Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')
            if (rel.isEmpty()) base else "$base/$rel"
        }.getOrNull()
    }
}
