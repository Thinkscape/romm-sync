package es.davidrg.rommsync.install

/** Minimal persistent long store behind [RescanMarker] (SharedPreferences on the device). */
interface LongStore {
    fun getLong(key: String): Long?
    /** Writes all [values] together; a null value removes the key. */
    fun putLongs(values: Map<String, Long?>)
}

/**
 * The "Cocoon rescan pending" marker, race-free against installs that land while
 * a rescan flow is running.
 *
 * Every install bumps a monotonically increasing generation. A flow captures
 * [generation] when it starts and, on success, calls [clear] with it: only
 * generations up to the captured one are marked handled, so an install recorded
 * after the flow started keeps the marker set. A failed flow never clears the
 * marker; it records [recordFailure] so [isDue] holds off retrying that same
 * generation for [failureBackoffMs] (a newer install retries right away).
 */
class RescanMarker(
    private val store: LongStore,
    private val clock: () -> Long,
    private val settleMs: Long,
    private val failureBackoffMs: Long,
) {
    /** Latest install generation (0 = no install ever recorded). */
    @get:Synchronized
    val generation: Long get() = store.getLong(KEY_GENERATION) ?: 0L

    /** Wall-clock time of the latest install, 0 when none. */
    @get:Synchronized
    val lastInstallAt: Long get() = store.getLong(KEY_LAST_INSTALL_AT) ?: 0L

    @get:Synchronized
    val isPending: Boolean get() = generation > handledGeneration()

    /** Called on every install; returns the new generation. */
    @Synchronized
    fun recordInstall(): Long {
        val next = generation + 1
        store.putLongs(mapOf(KEY_GENERATION to next, KEY_LAST_INSTALL_AT to clock()))
        return next
    }

    /**
     * Pending, installs have settled for [settleMs], and the current generation
     * is not inside a failure backoff.
     */
    @Synchronized
    fun isDue(): Boolean {
        val gen = generation
        if (gen <= handledGeneration()) return false
        val now = clock()
        val last = lastInstallAt
        // A clock that moved backwards must not block forever: treat it as settled.
        if (now >= last && now - last < settleMs) return false
        val failedGen = store.getLong(KEY_FAILED_GENERATION) ?: 0L
        val failedAt = store.getLong(KEY_FAILED_AT) ?: 0L
        if (failedGen == gen && now >= failedAt && now - failedAt < failureBackoffMs) return false
        return true
    }

    /** A flow that started at [startedGeneration] succeeded. Newer installs stay pending. */
    @Synchronized
    fun clear(startedGeneration: Long) {
        val handled = maxOf(handledGeneration(), minOf(startedGeneration, generation))
        store.putLongs(
            mapOf(
                KEY_HANDLED_GENERATION to handled,
                KEY_FAILED_GENERATION to null,
                KEY_FAILED_AT to null,
            ),
        )
    }

    /** A flow that started at [startedGeneration] failed: keep the marker, back off. */
    @Synchronized
    fun recordFailure(startedGeneration: Long) {
        store.putLongs(mapOf(KEY_FAILED_GENERATION to startedGeneration, KEY_FAILED_AT to clock()))
    }

    /** The feature was turned off: drop everything recorded so far. */
    @Synchronized
    fun discard() = clear(generation)

    private fun handledGeneration(): Long = store.getLong(KEY_HANDLED_GENERATION) ?: 0L

    companion object {
        const val KEY_GENERATION = "generation"
        const val KEY_HANDLED_GENERATION = "handled_generation"
        const val KEY_LAST_INSTALL_AT = "last_install_at"
        const val KEY_FAILED_GENERATION = "failed_generation"
        const val KEY_FAILED_AT = "failed_at"
    }
}
