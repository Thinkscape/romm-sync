package es.davidrg.rommsync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import es.davidrg.rommsync.install.InstallListenerService
import es.davidrg.rommsync.install.SyncTriggers
import es.davidrg.rommsync.ui.navigation.RomMSyncApp
import es.davidrg.rommsync.ui.theme.RomMSyncTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RomMSyncTheme {
                RomMSyncApp()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // App came to the foreground: sync saves (debounced) and, when paired,
        // make sure the push-install service runs (a foreground start is always allowed here).
        SyncTriggers.requestFullSync(applicationContext, "app foreground")
        InstallListenerService.start(applicationContext)
    }
}
