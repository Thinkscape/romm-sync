package es.davidrg.rommsync.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts [InstallListenerService] after a reboot or an app update so the
 * paired device comes back online without opening the app.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> InstallListenerService.start(context, drain = true)
        }
    }
}
