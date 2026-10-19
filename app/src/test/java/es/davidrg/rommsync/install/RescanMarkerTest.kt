package es.davidrg.rommsync.install

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RescanMarkerTest {

    private class MapStore : LongStore {
        val values = mutableMapOf<String, Long>()
        override fun getLong(key: String): Long? = values[key]
        override fun putLongs(values: Map<String, Long?>) {
            values.forEach { (key, value) -> if (value == null) this.values.remove(key) else this.values[key] = value }
        }
    }

    private var now = 1_000_000L
    private val store = MapStore()
    private val marker = RescanMarker(store, { now }, SETTLE_MS, BACKOFF_MS)

    @Test
    fun `install during a flow survives that flow's clear`() {
        marker.recordInstall()
        now += SETTLE_MS
        assertThat(marker.isDue()).isTrue()

        val started = marker.generation // flow starts
        now += 2_000
        marker.recordInstall() // push lands mid-flow
        now += 2_000
        marker.clear(started) // flow succeeds

        assertThat(marker.isPending).isTrue()
        assertThat(marker.isDue()).isFalse() // still settling
        now += SETTLE_MS
        assertThat(marker.isDue()).isTrue()
    }

    @Test
    fun `clear with the current generation removes the marker`() {
        marker.recordInstall()
        marker.recordInstall()
        now += SETTLE_MS
        marker.clear(marker.generation)

        assertThat(marker.isPending).isFalse()
        assertThat(marker.isDue()).isFalse()
    }

    @Test
    fun `stale clear never undoes a newer clear`() {
        marker.recordInstall()
        val first = marker.generation
        marker.recordInstall()
        marker.clear(marker.generation)
        marker.clear(first)

        assertThat(marker.isPending).isFalse()
    }

    @Test
    fun `not due before installs settle`() {
        marker.recordInstall()
        now += SETTLE_MS - 1
        assertThat(marker.isPending).isTrue()
        assertThat(marker.isDue()).isFalse()
    }

    @Test
    fun `failure keeps the marker and backs off for the same generation`() {
        marker.recordInstall()
        now += SETTLE_MS
        val started = marker.generation
        marker.recordFailure(started)

        assertThat(marker.isPending).isTrue()
        assertThat(marker.isDue()).isFalse()
        now += BACKOFF_MS - 1
        assertThat(marker.isDue()).isFalse()
        now += 1
        assertThat(marker.isDue()).isTrue()
    }

    @Test
    fun `a newer install after a failure retries once settled`() {
        marker.recordInstall()
        now += SETTLE_MS
        marker.recordFailure(marker.generation)
        now += 1_000
        marker.recordInstall()

        assertThat(marker.isDue()).isFalse()
        now += SETTLE_MS
        assertThat(marker.isDue()).isTrue()
    }

    @Test
    fun `success after a failure resets the backoff`() {
        marker.recordInstall()
        now += SETTLE_MS
        marker.recordFailure(marker.generation)
        marker.clear(marker.generation)
        marker.recordInstall()
        now += SETTLE_MS

        assertThat(marker.isDue()).isTrue()
    }

    @Test
    fun `discard drops every recorded install`() {
        marker.recordInstall()
        marker.recordInstall()
        marker.discard()

        assertThat(marker.isPending).isFalse()
    }

    private companion object {
        const val SETTLE_MS = 5_000L
        const val BACKOFF_MS = 600_000L
    }
}
