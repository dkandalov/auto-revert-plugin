package limitedwip.common.vcs

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Key
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsBundle
import com.intellij.openapi.vcs.VcsConfiguration
import com.intellij.openapi.vcs.changes.*
import com.intellij.openapi.vcs.changes.InvokeAfterUpdateMode.SYNCHRONOUS_CANCELLABLE
import com.intellij.openapi.vcs.changes.actions.RefreshAction
import com.intellij.openapi.vcs.impl.CheckinHandlersManager
import com.intellij.openapi.vcs.impl.LineStatusTrackerManager
import com.intellij.util.ObjectUtils.notNull
import com.intellij.vcs.commit.*
import com.intellij.vcs.log.VcsLogProvider
import limitedwip.common.settings.CommitMessageSource.ChangeListName
import limitedwip.common.settings.CommitMessageSource.LastCommit
import limitedwip.common.settings.LimitedWipSettings
import java.util.*
import java.util.concurrent.CompletableFuture

class CommitWithoutDialogAction: AnAction(AllIcons.Actions.Commit) {
    override fun actionPerformed(event: AnActionEvent) {
        doCommitWithoutDialog(event.project ?: return)
    }
}

fun doCommitWithoutDialog(project: Project, isAmendCommit: Boolean = false): Boolean {
    if (anySystemCheckinHandlerCancelsCommit(project)) return true
    // Don't attempt to commit if there are no VCS registered because it will throw an exception.
    val defaultChangeList = project.defaultChangeList() ?: return true

    val runnable = Runnable {
        RefreshAction.doRefresh(project)

        // Need this starting from around IJ 2019.2 because otherwise changes are not included into commit.
        // This seems be related to change in VCS UI which has commit dialog built-in into the toolwindow.
        LineStatusTrackerManager.getInstanceImpl(project).resetExcludedFromCommitMarkers()

        if (defaultChangeList.changes.isEmpty()) {
            Messages.showInfoMessage(
                project,
                VcsBundle.message("commit.dialog.no.changes.detected.text"),
                VcsBundle.message("commit.dialog.no.changes.detected.title")
            )
            return@Runnable
        }
        val commitMessage = when (LimitedWipSettings.getInstance(project).commitMessageSource) {
            LastCommit     -> VcsConfiguration.getInstance(project).LAST_COMMIT_MESSAGE ?: ""
            ChangeListName -> defaultChangeList.name
        }
        // Can't use empty message because CommitHelper will silently not commit changes.
        val nonEmptyCommitMessage = if (commitMessage.trim().isEmpty()) "empty message" else commitMessage

        // Commit "asynchronously" because right now in IJ this means doing it as background task
        // and this is better UX compared to a flashing modal commit progress window (which people have noticed and complained about).
        val commitSynchronously = false

        val noopCommitHandler = CommitResultHandler { }
        val commitHelper = CommitHelper(
            project,
            defaultChangeList,
            defaultChangeList.changes.toList(),
            "",
            nonEmptyCommitMessage,
            true,
            commitSynchronously,
            createCommitContext(isAmendCommit),
            noopCommitHandler
        )
        commitHelper.doCommit()
    }

    ChangeListManager.getInstance(project).invokeAfterUpdate(
        runnable,
        SYNCHRONOUS_CANCELLABLE,
        "Refreshing changelists...",
        ModalityState.current()
    )
    return false
}

/**
 * Couldn't find a better way to "reuse" this code but to copy-paste it from
 * [com.intellij.openapi.vcs.changes.ui.CommitChangeListDialog].
 */
private fun anySystemCheckinHandlerCancelsCommit(project: Project): Boolean {
    val allActiveVcss = ProjectLevelVcsManager.getInstance(project).allActiveVcss
    return CheckinHandlersManager.getInstance()
        .getRegisteredCheckinHandlerFactories(allActiveVcss)
        .map { it.createSystemReadyHandler(project) }
        .any { it != null && !it.beforeCommitDialogShown(project, ArrayList(), ArrayList(), false) }
}

private fun createCommitContext(isAmendCommit: Boolean): CommitContext {
    return CommitContext().also {
        if (isAmendCommit) {
            it.isAmendCommitMode // Accessing field to force lazy-loading of IS_AMEND_COMMIT_MODE_KEY 🙄
            @Suppress("UNCHECKED_CAST", "DEPRECATION")
            // Search for Key by name because IS_AMEND_COMMIT_MODE_KEY is private.
            it.putUserData(Key.findKeyByName("Vcs.Commit.IsAmendCommitMode") as Key<Boolean>, true)
        }
    }
}

fun lastCommitExistOnlyOnCurrentBranch(project: Project): Boolean {
    val logProviders = VcsLogProvider.LOG_PROVIDER_EP.getExtensions(project)
    val roots = ProjectLevelVcsManager.getInstance(project).allVcsRoots
    roots.map { root ->
        val commitIsOnOtherBranches = CompletableFuture<Boolean>()
        ApplicationManager.getApplication().executeOnPooledThread {
            val logProvider = logProviders.find { it.supportedVcs == root.vcs?.keyInstanceMethod }!!
            val logData = logProvider.readFirstBlock(root.path) { 1 }
            val hash = logData.commits.last().id
            val branchesWithCommit = logProvider.getContainingBranches(root.path, hash)
            val currentBranch = logProvider.getCurrentBranch(root.path)
            commitIsOnOtherBranches.complete((branchesWithCommit - currentBranch).isNotEmpty())
        }
        if (commitIsOnOtherBranches.get()) return false
    }
    return true
}

private class CommitHelper(
    project: Project,
    changeList: ChangeList,
    changes: List<Change>,
    private val myActionName: String,
    commitMessage: String,
    isDefaultChangeListFullyIncluded: Boolean,
    private val myForceSyncCommit: Boolean,
    commitContext: CommitContext,
    resultHandler: CommitResultHandler?
) {
    private val myCommitter: AbstractCommitter

    init {
        val commitState = ChangeListCommitState(changeList as LocalChangeList, changes, commitMessage)
        myCommitter = SingleChangeListCommitter(project, commitState, commitContext, myActionName, isDefaultChangeListFullyIncluded)
        myCommitter.addResultHandler(notNull(resultHandler, ShowNotificationCommitResultHandler(myCommitter)))
    }

    fun doCommit() {
        myCommitter.runCommit(myActionName, myForceSyncCommit)
    }
}