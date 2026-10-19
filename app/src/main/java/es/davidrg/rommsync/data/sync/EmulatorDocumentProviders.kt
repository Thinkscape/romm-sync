package es.davidrg.rommsync.data.sync

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * An emulator that publishes its own user directory (`getExternalFilesDir(null)`)
 * through a DocumentsProvider whose document ids are `root/<relative path>`.
 *
 * Android 13 hides other apps' `Android/data` folders from the system folder
 * picker, but a provider root can be granted like any other document root:
 * in ACTION_OPEN_DOCUMENT_TREE the user opens the picker's menu, picks the
 * emulator, taps "Use this folder" and "Allow"; the returned tree URI
 * (`content://<authority>/tree/root%2F`) is persisted ([SafTreeStore]) and
 * read/written through ContentResolver ([SafStaging]). Verified on the AYN
 * Thor (Android 13, no root) for Dolphin 2609a and Eden v0.2.1, with the
 * emulator processes stopped.
 *
 * Neither provider implements `findDocumentPath`, so the picker ignores
 * EXTRA_INITIAL_URI for them (DocumentsUI's LoadDocStackTask fails and it
 * opens on its default location); the URI is still passed in case a later
 * version supports it.
 */
data class EmulatorDocumentProvider(
    val packageName: String,
    /** Provider authority declared in the emulator's manifest. */
    val authority: String,
) {
    /** Real path of the directory the provider root exposes. */
    val userDir: String
        get() = File(
            Environment.getExternalStorageDirectory(),
            "Android/data/$packageName/files",
        ).path

    /** Path relative to [userDir] for a document id of this provider, or null if foreign. */
    fun relativePathOf(documentId: String): String? = when {
        documentId == ROOT -> ""
        documentId.startsWith("$ROOT/") -> documentId.removePrefix(ROOT).trim('/')
        else -> null
    }

    /** true when [path] is [userDir] or lies below it. */
    fun covers(path: String): Boolean {
        val p = path.trimEnd('/')
        val base = userDir.trimEnd('/')
        return p == base || p.startsWith("$base/")
    }

    /** true when the emulator is installed and its provider resolvable from this app. */
    fun isAvailable(context: Context): Boolean =
        runCatching { context.packageManager.resolveContentProvider(authority, 0) != null }
            .getOrDefault(false)

    companion object {
        private const val ROOT = "root"

        /** Document id the picker returns for the provider root (both providers). */
        const val ROOT_DOCUMENT_ID = "root/"
    }
}

object EmulatorDocumentProviders {

    /** Dolphin `org.dolphinemu.dolphinemu.features.DocumentProvider`. */
    val DOLPHIN = EmulatorDocumentProvider(
        packageName = "org.dolphinemu.dolphinemu",
        authority = "org.dolphinemu.dolphinemu.user",
    )

    /** Eden, yuzu's `org.yuzu.yuzu_emu.features.DocumentProvider`. */
    val EDEN = EmulatorDocumentProvider(
        packageName = "dev.eden.eden_emulator",
        authority = "dev.eden.eden_emulator.user",
    )

    val ALL: List<EmulatorDocumentProvider> = listOf(DOLPHIN, EDEN)

    fun forAuthority(authority: String?): EmulatorDocumentProvider? =
        ALL.firstOrNull { it.authority == authority }

    fun forPath(path: String): EmulatorDocumentProvider? =
        ALL.firstOrNull { it.covers(path) }
}
