package es.davidrg.rommsync.install

import com.google.common.truth.Truth.assertThat
import es.davidrg.rommsync.install.CocoonFolderLedger.Applied
import es.davidrg.rommsync.install.CocoonFolderLedger.Desired
import es.davidrg.rommsync.install.CocoonFolderLedger.Work
import org.junit.Test

class CocoonFolderLedgerTest {

    private class MapStore : StringStore {
        val values = mutableMapOf<String, String>()
        override fun getString(key: String): String? = values[key]
        override fun putString(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    private val store = MapStore()
    private val ledger = CocoonFolderLedger(store)
    private val desired = Desired(name = "Deck starters", pattern = "^(?:a\\.zip)$")

    private fun applied(name: String = desired.name, pattern: String = desired.pattern) = Applied(name = name, pattern = pattern)

    @Test
    fun `new collection needs a create and survives a reload`() {
        assertThat(ledger.want("regular:1", desired)).isTrue()
        assertThat(CocoonFolderLedger(store).pendingWork()).containsExactly(Work.Create("regular:1", desired))
    }

    @Test
    fun `applied collection with the same wish needs no UI pass`() {
        ledger.want("regular:1", desired)
        ledger.markApplied("regular:1", applied())
        assertThat(ledger.pendingWork()).isEmpty()
        assertThat(ledger.want("regular:1", desired)).isFalse()
    }

    @Test
    fun `membership change needs an update`() {
        ledger.want("regular:1", desired)
        val applied = applied()
        ledger.markApplied("regular:1", applied)
        val next = desired.copy(pattern = "^(?:a\\.zip|b\\.zip)$")
        assertThat(ledger.want("regular:1", next)).isTrue()
        assertThat(ledger.pendingWork()).containsExactly(Work.Update("regular:1", applied, next))
    }

    @Test
    fun `pattern edited in Cocoon is kept and never rewritten again`() {
        val applied = applied()
        val next = desired.copy(pattern = "^(?:a\\.zip|b\\.zip)$")
        val plan = CocoonFolderLedger.plan(applied, next, observedPattern = "zelda|mario", observedIconFile = null)
        assertThat(plan.pattern).isNull()
        assertThat(plan.isNoop).isTrue()
        assertThat(plan.applied.userPattern).isTrue()

        ledger.want("regular:1", next)
        ledger.markApplied("regular:1", plan.applied)
        assertThat(ledger.pendingWork()).isEmpty()
        assertThat(ledger.want("regular:1", next.copy(pattern = "^(?:c\\.zip)$"))).isFalse()
    }

    @Test
    fun `untouched pattern is replaced and rename is applied`() {
        val applied = applied()
        val next = Desired(name = "Couch co-op", pattern = "^(?:b\\.zip)$")
        val plan = CocoonFolderLedger.plan(applied, next, observedPattern = desired.pattern, observedIconFile = null)
        assertThat(plan.rename).isEqualTo("Couch co-op")
        assertThat(plan.pattern).isEqualTo("^(?:b\\.zip)$")
        assertThat(plan.applied).isEqualTo(Applied(name = "Couch co-op", requestedName = "Couch co-op", pattern = "^(?:b\\.zip)$"))
    }

    @Test
    fun `cover is uploaded only while the icon is still ours`() {
        val applied = Applied(name = desired.name, pattern = desired.pattern, coverSha1 = "old", iconFile = "folder_14_x.png")
        val next = desired.copy(coverPath = "/cache/c.png", coverSha1 = "new")
        assertThat(CocoonFolderLedger.plan(applied, next, desired.pattern, "folder_14_x.png").uploadCover).isTrue()
        val userArt = CocoonFolderLedger.plan(applied, next, desired.pattern, "folder_14_user.png")
        assertThat(userArt.uploadCover).isFalse()
        assertThat(userArt.applied.userIcon).isTrue()
    }

    @Test
    fun `only folders we created are removed`() {
        assertThat(ledger.forget("regular:9")).isFalse()

        ledger.want("regular:1", desired)
        assertThat(ledger.forget("regular:1")).isFalse()
        assertThat(ledger.entries()).isEmpty()

        ledger.want("regular:2", desired)
        val applied = applied()
        ledger.markApplied("regular:2", applied)
        assertThat(ledger.forget("regular:2")).isTrue()
        assertThat(ledger.pendingWork()).containsExactly(Work.Remove("regular:2", applied))
        ledger.markRemoved("regular:2")
        assertThat(ledger.entries()).isEmpty()
    }

    @Test
    fun `a folder the user deleted is left alone`() {
        ledger.want("regular:1", desired)
        ledger.markApplied("regular:1", applied())
        ledger.want("regular:1", desired.copy(pattern = "^(?:z\\.zip)$"))
        ledger.markDetached("regular:1")
        assertThat(ledger.pendingWork()).isEmpty()
        // Unsubscribing a detached folder never touches Cocoon.
        assertThat(ledger.forget("regular:1")).isFalse()
    }

    @Test
    fun `a name the keyboard could not type is not retyped while RomM asks for the same name`() {
        val wanted = desired.copy(name = "Mario & Luigi")
        ledger.want("regular:1", wanted)
        // Cocoon shows the name without the '&' the keyboard lacked.
        ledger.markApplied("regular:1", Applied(name = "Mario  Luigi", requestedName = wanted.name, pattern = wanted.pattern))
        assertThat(ledger.pendingWork()).isEmpty()
        assertThat(ledger.want("regular:1", wanted)).isFalse()

        val renamed = wanted.copy(name = "Mario Party")
        assertThat(ledger.want("regular:1", renamed)).isTrue()
        val work = ledger.pendingWork().single() as Work.Update
        // The folder is still found on the grid by the name Cocoon shows.
        assertThat(work.applied.name).isEqualTo("Mario  Luigi")
        val plan = CocoonFolderLedger.plan(work.applied, renamed, observedPattern = wanted.pattern, observedIconFile = null)
        assertThat(plan.rename).isEqualTo("Mario Party")
        assertThat(plan.applied.requestedName).isEqualTo("Mario Party")
    }

    @Test
    fun `a folder stops being retried after the maximum failures until RomM changes it`() {
        ledger.want("regular:1", desired)
        repeat(CocoonFolderLedger.MAX_FAILURES - 1) { ledger.recordFailure("regular:1") }
        assertThat(ledger.pendingWork()).hasSize(1)

        ledger.recordFailure("regular:1")
        assertThat(ledger.pendingWork()).isEmpty()
        // The same wish again does not reset the counter...
        assertThat(ledger.want("regular:1", desired)).isFalse()
        assertThat(ledger.pendingWork()).isEmpty()
        // ...a changed wish does.
        assertThat(ledger.want("regular:1", desired.copy(pattern = "^(?:b\\.zip)$"))).isTrue()
        assertThat(ledger.pendingWork()).hasSize(1)
    }

    @Test
    fun `a successful pass clears the failure counter`() {
        ledger.want("regular:1", desired)
        repeat(CocoonFolderLedger.MAX_FAILURES - 1) { ledger.recordFailure("regular:1") }
        ledger.markApplied("regular:1", applied())
        assertThat(ledger.entries().getValue("regular:1").failures).isEqualTo(0)
    }
}
