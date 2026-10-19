package es.davidrg.rommsync.install

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import es.davidrg.rommsync.RomMSyncApplication

/**
 * The "Cocoon automation" setting (DataStore `cocoon_auto_import_enabled`,
 * default on) and the decisions it drives in [CocoonRescanService].
 *
 * Off: no Cocoon flow runs (rescan, collection folders, uncovering Cocoon's grid),
 * pending rescans are dropped and [CocoonCollectionWriter] leaves the folder ledger
 * alone. The accessibility service stays on: it also refreshes ES-DE ([EsdeReloader]).
 */
object CocoonAutomation {

    fun isEnabled(context: Context): Boolean =
        (context.applicationContext as? RomMSyncApplication)?.container?.settingsDataStore
            ?.getCocoonAutoImportEnabledBlocking() ?: false

    /** Package of the default HOME app (null when none is chosen or it is not visible to this app). */
    fun homePackage(context: Context): String? {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
    }

    /** What the service does about Cocoon work on one evaluation. */
    enum class Step { NONE, WAIT_FOR_SETTLE, DISCARD_PENDING, RESCAN, FOLDERS }

    fun step(enabled: Boolean, rescanDue: Boolean, folderWorkDue: Boolean, installsSettling: Boolean): Step {
        if (!rescanDue && !folderWorkDue) return Step.NONE
        if (!enabled) return Step.DISCARD_PENDING
        if (rescanDue) return Step.RESCAN
        // Installs still settling: their rescan goes first, then the folder pass.
        return if (installsSettling) Step.WAIT_FOR_SETTLE else Step.FOLDERS
    }

    /**
     * Cocoon is brought in front of a stale ES-DE Companion only while Cocoon
     * work is running and Cocoon is the HOME app (with ES-DE as HOME, the
     * companion's second screen belongs to ES-DE).
     */
    fun mayUncoverCocoon(enabled: Boolean, homePackage: String?): Boolean =
        enabled && homePackage == CocoonAutoImport.COCOON_PACKAGE
}
