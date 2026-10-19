package es.davidrg.rommsync.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import es.davidrg.rommsync.BuildConfig
import es.davidrg.rommsync.core.collections.CollectionMember
import es.davidrg.rommsync.core.collections.DeviceCollection
import java.io.File

/**
 * Debug builds only: drives [CocoonCollectionWriter] without a RomM server, to
 * test the Cocoon folder flow on a device.
 *
 * ```
 * adb shell am broadcast -p es.davidrg.rommsync -a es.davidrg.rommsync.DEBUG_COCOON_FOLDER \
 *   --es op write --ei id 9001 --es name 'Test' --es files '/storage/X/ROMs/snes/a.sfc|/storage/X/ROMs/nes/b.nes' \
 *   [--es cover /sdcard/Download/cover.png]
 * adb shell am broadcast -p es.davidrg.rommsync -a es.davidrg.rommsync.DEBUG_COCOON_FOLDER --es op remove --ei id 9001
 * ```
 */
object CocoonFolderDebugTrigger {
    private const val TAG = "CocoonCollections"
    private const val ACTION = "es.davidrg.rommsync.DEBUG_COCOON_FOLDER"

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pending = goAsync()
            val app = context.applicationContext
            Thread {
                try {
                    handle(app, intent)
                } catch (e: Exception) {
                    Log.w(TAG, "debug trigger failed: ${e.message}", e)
                } finally {
                    pending.finish()
                }
            }.start()
        }
    }
    private var registered = false

    fun register(context: Context) {
        if (!BuildConfig.DEBUG || registered) return
        ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION), ContextCompat.RECEIVER_EXPORTED)
        registered = true
        Log.i(TAG, "debug trigger registered ($ACTION)")
    }

    fun unregister(context: Context) {
        if (!registered) return
        runCatching { context.unregisterReceiver(receiver) }
        registered = false
    }

    private fun handle(context: Context, intent: Intent) {
        if (intent.getStringExtra("op") == "dump") {
            val service = CocoonRescanService.instance ?: return
            CocoonFolderFlow(service).describeScreen().lineSequence().forEach { Log.i(TAG, "dump: $it") }
            return
        }
        val id = intent.getIntExtra("id", -1).takeIf { it >= 0 } ?: return
        val writer = CocoonCollectionWriter(context)
        val collection = DeviceCollection(
            kind = "collection",
            id = id,
            name = intent.getStringExtra("name") ?: "Debug $id",
            description = null,
            romIds = emptyList(),
            coverUrl = null,
            updatedAt = "",
        )
        when (intent.getStringExtra("op")) {
            "write" -> {
                val paths = intent.getStringExtra("files").orEmpty().split('|').filter { it.isNotBlank() }
                val members = paths.mapIndexed { index, path ->
                    val file = File(path)
                    CollectionMember(romId = index, platformFolder = file.parentFile?.name.orEmpty(), files = listOf(file))
                }
                val cover = intent.getStringExtra("cover")?.let(::File)
                Log.i(TAG, "debug trigger: write ${collection.key} '${collection.name}' with ${members.size} members, cover=$cover")
                writer.write(collection, members, cover)
            }
            "reattach" -> {
                val ledger = CocoonCollections.ledger(context)
                ledger.entries()[collection.key]?.applied?.let { ledger.markApplied(collection.key, it) }
                Log.i(TAG, "debug trigger: reattached ${collection.key}")
            }
            "remove" -> {
                Log.i(TAG, "debug trigger: remove ${collection.key}")
                writer.remove(collection.key, collection.name)
            }
            else -> Log.w(TAG, "debug trigger: unknown op")
        }
    }
}
