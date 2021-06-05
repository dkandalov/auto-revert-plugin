package limitedwip.tcr

import limitedwip.common.settings.TcrAction.*
import limitedwip.expect
import limitedwip.shouldEqual
import limitedwip.tcr.Tcr.ChangeListModifications
import limitedwip.tcr.components.TcrIde
import org.junit.Test
import org.mockito.Mockito.*

private class Fixture(
    val ide: TcrIde = mock(TcrIde::class.java),
    val settings: Tcr.Settings = Tcr.Settings(
        enabled = true,
        notifyOnRevert = true,
        actionOnPassedTest = OpenCommitDialog,
        doNotRevertTests = false,
        doNotRevertFiles = emptySet()
    ),
    val tcr: Tcr = Tcr(ide, settings),
    val someModifications: ChangeListModifications = ChangeListModifications(mapOf("foo" to 1L)),
    val someTestName: String = "SomeTest"
) {
    init {
        `when`(ide.revertCurrentChangeList(anyBoolean(), anySet())).thenReturn(10)
    }

    fun run(f: Fixture.() -> Unit) = f(this)
}

class TcrTests {
    @Test fun `allow commits only after running a unit test`() = Fixture().run {
        tcr.isCommitAllowed(someModifications) shouldEqual false
        ide.expect().notifyThatCommitWasCancelled()

        tcr.onUnitTestSucceeded(someModifications, someTestName)
        tcr.isCommitAllowed(someModifications) shouldEqual true
    }

    @Test fun `when test failed, revert changes`() = Fixture().run {
        tcr.onUnitTestFailed("SomeTest")
        ide.expect().revertCurrentChangeList(anyBoolean(), anySet())
    }

    @Test fun `when test passed, show commit dialog`() = Fixture().run {
        tcr.onSettingsUpdate(settings.copy(actionOnPassedTest = OpenCommitDialog))
        tcr.onUnitTestSucceeded(someModifications, someTestName)
        ide.expect().openCommitDialog()
    }

    @Test fun `when test passed, do commit`() = Fixture().run {
        tcr.onSettingsUpdate(settings.copy(actionOnPassedTest = Commit))
        tcr.onUnitTestSucceeded(someModifications, someTestName)
        ide.expect().commitWithoutDialog()
    }

    @Test fun `when test passed, do amend commit`() = Fixture().run {
        `when`(ide.lastCommitExistOnlyOnCurrentBranch()).thenReturn(true)
        tcr.onSettingsUpdate(settings.copy(actionOnPassedTest = AmendCommit))
        tcr.onUnitTestSucceeded(someModifications, someTestName)
        ide.expect().amendCommitWithoutDialog()
    }

    @Test fun `when test passed, do commit and push`() = Fixture().run {
        tcr.onSettingsUpdate(settings.copy(actionOnPassedTest = CommitAndPush))
        tcr.onUnitTestSucceeded(someModifications, someTestName)
        ide.expect().commitWithoutDialogAndPush()
    }

    @Test fun `when test passed and commit exists on other branches, open dialog instead of amend commit`() = Fixture().run {
        `when`(ide.lastCommitExistOnlyOnCurrentBranch()).thenReturn(false)
        tcr.onSettingsUpdate(settings.copy(actionOnPassedTest = AmendCommit))
        tcr.onUnitTestSucceeded(someModifications, someTestName)
        ide.expect().openCommitDialog()
    }

    @Test fun `when test passed and test name is different from last execution, open dialog instead of amend commit`() = Fixture().run {
        `when`(ide.lastCommitExistOnlyOnCurrentBranch()).thenReturn(true)
        tcr.onSettingsUpdate(settings.copy(actionOnPassedTest = AmendCommit))
        tcr.onUnitTestSucceeded(someModifications, someTestName)
        tcr.onUnitTestSucceeded(someModifications, "SomeOtherTest")
        ide.expect().amendCommitWithoutDialog()
        ide.expect().openCommitDialog()
    }

    @Test fun `when test failed and next passed test name is different from last execution, open dialog instead of amend commit`() = Fixture().run {
        `when`(ide.lastCommitExistOnlyOnCurrentBranch()).thenReturn(true)
        tcr.onSettingsUpdate(settings.copy(actionOnPassedTest = AmendCommit))
        tcr.onUnitTestFailed(someTestName)
        tcr.onUnitTestSucceeded(someModifications, "SomeOtherTest")
        ide.expect().revertCurrentChangeList(anyBoolean(), anySet())
        ide.expect().openCommitDialog()
    }

    @Test fun `don't show commit dialog if there are no modifications`() = Fixture().run {
        val noModifications = ChangeListModifications(emptyMap())
        tcr.onUnitTestSucceeded(noModifications, someTestName)
        ide.expect(never()).openCommitDialog()
    }

    @Test fun `after commit need to run a unit test to be able to commit again`() = Fixture().run {
        tcr.onUnitTestSucceeded(someModifications, someTestName)
        tcr.isCommitAllowed(someModifications) shouldEqual true

        tcr.onSuccessfulCommit()
        tcr.isCommitAllowed(someModifications) shouldEqual false

        tcr.onUnitTestSucceeded(someModifications, someTestName)
        tcr.isCommitAllowed(someModifications) shouldEqual true
    }

    @Test fun `don't allow commits if files were changed after running a unit test`() = Fixture().run {
        tcr.onUnitTestSucceeded(someModifications, someTestName)
        tcr.isCommitAllowed(someModifications) shouldEqual true

        val moreModifications = ChangeListModifications(someModifications.value + Pair("foo", 2L))
        tcr.isCommitAllowed(moreModifications) shouldEqual false

        tcr.onUnitTestSucceeded(moreModifications, someTestName)
        tcr.isCommitAllowed(moreModifications) shouldEqual true
    }

    @Test fun `notify user on revert`() = Fixture().run {
        tcr.onUnitTestFailed(someTestName)
        ide.expect().revertCurrentChangeList(anyBoolean(), anySet())
        ide.expect().notifyThatChangesWereReverted()
    }

    @Test fun `don't notify user on revert if notification is disabled in settings`() = Fixture().run {
        tcr.onSettingsUpdate(settings.copy(notifyOnRevert = false))
        tcr.onUnitTestFailed(someTestName)
        ide.expect().revertCurrentChangeList(anyBoolean(), anySet())
        ide.expect(never()).notifyThatChangesWereReverted()
    }

    @Test fun `can do one-off commit without running a unit test`() = Fixture().run {
        tcr.isCommitAllowed(someModifications) shouldEqual false
        tcr.forceOneCommit()
        tcr.isCommitAllowed(someModifications) shouldEqual true
        ide.expect().commitWithoutDialog()

        tcr.onSuccessfulCommit()
        tcr.isCommitAllowed(someModifications) shouldEqual false
    }
}

class TcrDisabledTests {
    @Test fun `if disabled, always allow commits`() = Fixture().run {
        tcr.onSettingsUpdate(settings.copy(enabled = false))
        tcr.isCommitAllowed(someModifications) shouldEqual true
    }

    @Test fun `if disabled, don't revert changes on failed unit test`() = Fixture().run {
        tcr.onSettingsUpdate(settings.copy(enabled = false))
        tcr.onUnitTestFailed(someTestName)
        ide.expect(never()).revertCurrentChangeList(anyBoolean(), anySet())
        ide.expect(never()).notifyThatChangesWereReverted()
    }

    @Test fun `if disabled, don't show commit dialog and don't count successful test runs`() = Fixture().run {
        tcr.onSettingsUpdate(settings.copy(enabled = false))
        tcr.onUnitTestSucceeded(someModifications, someTestName)
        ide.expect(never()).openCommitDialog()

        tcr.onSettingsUpdate(settings.copy(enabled = true))
        tcr.isCommitAllowed(someModifications) shouldEqual false
    }
}