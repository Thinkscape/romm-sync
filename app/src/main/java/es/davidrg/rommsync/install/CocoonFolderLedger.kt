package es.davidrg.rommsync.install

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

/** Minimal persistent string store behind [CocoonFolderLedger] (SharedPreferences on the device). */
interface StringStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
}

/**
 * What RomM wants a Cocoon collection folder to look like ([Desired]) versus
 * what this app last wrote into Cocoon ([Applied]), per collection key.
 *
 * Cocoon keeps folders in its private database, so they can only be changed
 * through its UI (see [CocoonRescanService]); [CocoonCollectionWriter] records
 * the wish here and the accessibility flow applies it later, when Cocoon's home
 * screen is idle. The ledger is the single source of the override rule: a
 * field is only rewritten while Cocoon still shows the value we wrote last.
 * Pure JVM; unit-tested.
 */
class CocoonFolderLedger(private val store: StringStore) {

    @JsonClass(generateAdapter = false)
    data class Desired(
        val name: String,
        val pattern: String,
        /** Downloaded RomM cover to upload as the folder icon, null = none. */
        val coverPath: String? = null,
        val coverSha1: String? = null,
    )

    @JsonClass(generateAdapter = false)
    data class Applied(
        /** Folder name as Cocoon shows it after our last write (the folder is found on the home grid by it). */
        val name: String,
        /**
         * Name RomM asked for when [name] was written; differs from [name] when
         * Cocoon's keyboard could not type every character. Null = same as [name].
         */
        val requestedName: String? = null,
        /** Regex as last written; a different pattern in Cocoon means the user edited it. */
        val pattern: String,
        /** Cover last uploaded, null = none uploaded by us. */
        val coverSha1: String? = null,
        /** Cocoon's stored icon file after our upload (`folder_<id>_<uuid>.<ext>`); another file = user art. */
        val iconFile: String? = null,
        /** The user changed the pattern in Cocoon: never rewrite it. */
        val userPattern: Boolean = false,
        /** The user changed the folder art in Cocoon: never upload ours again. */
        val userIcon: Boolean = false,
    )

    @JsonClass(generateAdapter = false)
    data class Entry(
        val desired: Desired? = null,
        val applied: Applied? = null,
        /** The collection is gone from RomM: delete the folder we created. */
        val removePending: Boolean = false,
        /** Our folder vanished from Cocoon (deleted or renamed by the user): leave it alone. */
        val detached: Boolean = false,
        /** Consecutive failed UI passes for this folder; at [MAX_FAILURES] it waits for a new wish from RomM. */
        val failures: Int = 0,
    )

    sealed class Work(open val key: String) {
        data class Create(override val key: String, val desired: Desired) : Work(key)
        data class Update(override val key: String, val applied: Applied, val desired: Desired) : Work(key)
        data class Remove(override val key: String, val applied: Applied) : Work(key)
    }

    /** Result of comparing what Cocoon shows with the ledger, during an update. */
    data class UpdatePlan(
        /** New name to type, null = keep. */
        val rename: String?,
        /** New pattern to apply, null = keep (unchanged, or the user's own). */
        val pattern: String?,
        /** Upload the desired cover. */
        val uploadCover: Boolean,
        /** Ledger record once the plan is applied (cover fields are set by the flow after an upload). */
        val applied: Applied,
    ) {
        val isNoop: Boolean get() = rename == null && pattern == null && !uploadCover
    }

    private val adapter = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        .adapter<Map<String, Entry>>(Types.newParameterizedType(Map::class.java, String::class.java, Entry::class.java))

    @Synchronized
    fun entries(): Map<String, Entry> =
        store.getString(KEY)?.let { runCatching { adapter.fromJson(it) }.getOrNull() } ?: emptyMap()

    @Synchronized
    private fun save(entries: Map<String, Entry>) {
        store.putString(KEY, if (entries.isEmpty()) null else adapter.toJson(entries))
    }

    @Synchronized
    private fun update(key: String, change: (Entry?) -> Entry?) {
        val all = entries().toMutableMap()
        val next = change(all[key])
        if (next == null) all.remove(key) else all[key] = next
        save(all)
    }

    /** Records RomM's current wish; true when Cocoon needs a UI pass for it. */
    fun want(key: String, desired: Desired): Boolean {
        var needed = false
        update(key) { entry ->
            val current = entry ?: Entry()
            val next = current.copy(
                desired = desired,
                removePending = false,
                failures = if (current.desired == desired && !current.removePending) current.failures else 0,
            )
            needed = next.failures < MAX_FAILURES && workFor(key, next) != null
            next
        }
        return needed
    }

    /** The collection left RomM. Only folders this app created are removed; true when a UI pass is needed. */
    fun forget(key: String): Boolean {
        var needed = false
        update(key) { entry ->
            if (entry?.applied == null || entry.detached) {
                null
            } else {
                needed = true
                entry.copy(desired = null, removePending = true)
            }
        }
        return needed
    }

    /** Everything the accessibility flow still has to do. */
    fun pendingWork(): List<Work> = entries()
        .filterValues { it.failures < MAX_FAILURES }
        .mapNotNull { (key, entry) -> workFor(key, entry) }

    fun markApplied(key: String, applied: Applied) =
        update(key) { (it ?: Entry()).copy(applied = applied, detached = false, failures = 0) }

    /** A UI pass for [key] failed; the folder is retried until [MAX_FAILURES]. */
    fun recordFailure(key: String) = update(key) { entry -> entry?.copy(failures = entry.failures + 1) }

    /** Our folder was not found on the home grid: stop managing it (the user deleted or renamed it). */
    fun markDetached(key: String) = update(key) { entry -> entry?.takeUnless { it.removePending }?.copy(detached = true) }

    fun markRemoved(key: String) = update(key) { null }

    private fun workFor(key: String, entry: Entry): Work? {
        val applied = entry.applied
        if (entry.removePending) return applied?.let { Work.Remove(key, it) }
        if (entry.detached) return null
        val desired = entry.desired ?: return null
        if (applied == null) return Work.Create(key, desired)
        val nameDue = (applied.requestedName ?: applied.name) != desired.name
        val patternDue = !applied.userPattern && applied.pattern != desired.pattern
        val coverDue = !applied.userIcon && desired.coverSha1 != null && applied.coverSha1 != desired.coverSha1
        return if (nameDue || patternDue || coverDue) Work.Update(key, applied, desired) else null
    }

    companion object {
        private const val KEY = "cocoon_collection_folders"
        const val MAX_FAILURES = 3

        /**
         * Override rule for an existing folder: a field is rewritten only while
         * Cocoon still shows what we wrote last ([observedPattern], [observedIconFile]
         * read from Cocoon); anything else is the user's and stays theirs for good
         * ([Applied.userPattern], [Applied.userIcon] in the returned [UpdatePlan.applied]).
         */
        fun plan(applied: Applied, desired: Desired, observedPattern: String?, observedIconFile: String?): UpdatePlan {
            val userPattern = applied.userPattern || observedPattern != applied.pattern
            val userIcon = applied.userIcon || observedIconFile != applied.iconFile
            val rename = desired.name.takeIf { it != (applied.requestedName ?: applied.name) }
            val pattern = desired.pattern.takeIf { !userPattern && it != applied.pattern }
            val uploadCover = !userIcon && desired.coverPath != null && desired.coverSha1 != null &&
                desired.coverSha1 != applied.coverSha1
            return UpdatePlan(
                rename = rename,
                pattern = pattern,
                uploadCover = uploadCover,
                applied = applied.copy(
                    name = rename ?: applied.name,
                    requestedName = rename ?: applied.requestedName,
                    pattern = pattern ?: applied.pattern,
                    userPattern = userPattern,
                    userIcon = userIcon,
                ),
            )
        }
    }
}
