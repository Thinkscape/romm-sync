package es.davidrg.rommsync.install

import android.content.Context
import android.media.MediaScannerConnection
import android.util.Log
import es.davidrg.rommsync.core.collections.LauncherGameRemover
import es.davidrg.rommsync.core.metadata.EsdeRemoval
import es.davidrg.rommsync.data.local.SettingsDataStore
import java.io.File

/**
 * Cocoon side of an uninstall, after core deleted the game files.
 *
 * Cocoon reads game art and metadata from the ES-DE folder this app fills on
 * push (`<esde>/gamelists/<system>/gamelist.xml`, `<esde>/downloaded_media/…`),
 * so the entry and media written for the rom are dropped there. Cocoon's own
 * library row disappears with the next Rescan Games ("newly added or removed
 * games are reflected"), which [CocoonAutoImport] schedules exactly as it does
 * after an install. Collection folders are not touched here: the reconciler
 * rewrites their pattern from the remaining members.
 */
class CocoonGameRemover(
    private val context: Context,
    private val settings: SettingsDataStore,
) : LauncherGameRemover {

    override fun onRomRemoved(romId: Int, platformFolder: String, files: List<File>) {
        val top = EsdeRemoval.topLevel(platformFolder, files)
        if (top != null && settings.getEsdePushMediaEnabledBlocking()) {
            val esdeDir = File(settings.getEsdeMetadataDirBlocking())
            runCatching {
                val gamelist = EsdeRemoval.removeGamelistEntries(esdeDir, platformFolder, top)
                val media = EsdeRemoval.deleteMedia(File(esdeDir, "downloaded_media"), platformFolder, top)
                Log.i(TAG, "rom $romId: ES-DE gamelist entry removed=$gamelist, media files deleted=${media.size}")
            }.onFailure { Log.w(TAG, "rom $romId: ES-DE cleanup failed: ${it.message}") }
        }
        if (files.isNotEmpty()) {
            // Drops the deleted paths from MediaStore.
            MediaScannerConnection.scanFile(context, files.map { it.absolutePath }.toTypedArray(), null, null)
        }
        CocoonAutoImport.onLibraryChanged(context)
    }

    private companion object {
        const val TAG = "CocoonGameRemover"
    }
}
