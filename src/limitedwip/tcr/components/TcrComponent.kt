package limitedwip.tcr.components

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import limitedwip.common.settings.LimitedWipSettings
import limitedwip.common.toPathMatchers
import limitedwip.common.vcs.SuccessfulCheckin
import limitedwip.tcr.Tcr
import limitedwip.tcr.Tcr.ChangeListModifications
import limitedwip.tcr.Tcr.Settings

class TcrComponentStartup: StartupActivity {
    override fun runActivity(project: Project) = TcrComponent(project).start()
}

private class TcrComponent(private val project: Project) {
    fun start() {
        val ide = TcrIde(project)
        val tcr = Tcr(ide, LimitedWipSettings.getInstance(project).toTcrSettings())
        ide.listener = object: TcrIde.Listener {
            override fun onForceCommit() = tcr.forceOneCommit()
            override fun allowCommit() = tcr.isCommitAllowed(ChangeListModifications(ide.defaultChangeListModificationCount()))
        }

        UnitTestsWatcher(project).start(object: UnitTestsWatcher.Listener {
            override fun onUnitTestSucceeded(testName: String) = tcr.onUnitTestSucceeded(ChangeListModifications(ide.defaultChangeListModificationCount()), testName)
            override fun onUnitTestFailed(testName: String) = tcr.onUnitTestFailed(testName)
        })

        SuccessfulCheckin.registerListener(project, object: SuccessfulCheckin.Listener {
            override fun onSuccessfulCheckin(allChangesAreCommitted: Boolean) = tcr.onSuccessfulCommit()
        })

        LimitedWipSettings.getInstance(project).addListener(project, object: LimitedWipSettings.Listener {
            override fun onUpdate(settings: LimitedWipSettings) = tcr.onSettingsUpdate(settings.toTcrSettings())
        })
    }

    private fun LimitedWipSettings.toTcrSettings() =
        Settings(
            enabled = tcrEnabled,
            notifyOnRevert = notifyOnTcrRevert,
            actionOnPassedTest = tcrActionOnPassedTest,
            doNotRevertTests = doNotRevertTests,
            doNotRevertFiles = doNotRevertFiles.toPathMatchers()
        )
}