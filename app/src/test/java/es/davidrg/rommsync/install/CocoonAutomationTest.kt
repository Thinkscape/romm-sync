package es.davidrg.rommsync.install

import com.google.common.truth.Truth.assertThat
import es.davidrg.rommsync.install.CocoonAutomation.Step
import org.junit.Test

class CocoonAutomationTest {

    @Test
    fun `off drops a due rescan and never runs a Cocoon flow`() {
        for (rescan in listOf(true, false)) for (folders in listOf(true, false)) for (settling in listOf(true, false)) {
            val step = CocoonAutomation.step(enabled = false, rescanDue = rescan, folderWorkDue = folders, installsSettling = settling)
            assertThat(step).isNotEqualTo(Step.RESCAN)
            assertThat(step).isNotEqualTo(Step.FOLDERS)
            assertThat(step).isEqualTo(if (rescan || folders) Step.DISCARD_PENDING else Step.NONE)
        }
    }

    @Test
    fun `on runs the rescan before folder work and waits for settling installs`() {
        assertThat(CocoonAutomation.step(true, rescanDue = true, folderWorkDue = false, installsSettling = false)).isEqualTo(Step.RESCAN)
        assertThat(CocoonAutomation.step(true, rescanDue = false, folderWorkDue = true, installsSettling = true)).isEqualTo(Step.WAIT_FOR_SETTLE)
        assertThat(CocoonAutomation.step(true, rescanDue = false, folderWorkDue = true, installsSettling = false)).isEqualTo(Step.FOLDERS)
        assertThat(CocoonAutomation.step(true, rescanDue = false, folderWorkDue = false, installsSettling = true)).isEqualTo(Step.NONE)
    }

    @Test
    fun `Cocoon is uncovered only when automation is on and Cocoon is HOME`() {
        assertThat(CocoonAutomation.mayUncoverCocoon(true, CocoonAutoImport.COCOON_PACKAGE)).isTrue()
        assertThat(CocoonAutomation.mayUncoverCocoon(false, CocoonAutoImport.COCOON_PACKAGE)).isFalse()
        assertThat(CocoonAutomation.mayUncoverCocoon(true, EsdeReloader.ESDE_PACKAGE)).isFalse()
        assertThat(CocoonAutomation.mayUncoverCocoon(true, null)).isFalse()
    }
}
