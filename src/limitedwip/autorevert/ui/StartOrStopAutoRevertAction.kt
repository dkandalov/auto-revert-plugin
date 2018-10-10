package limitedwip.autorevert.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import limitedwip.autorevert.components.AutoRevertComponent

class StartOrStopAutoRevertAction : AnAction(AllIcons.Actions.Rollback) {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val autoRevertComponent = project.getComponent(AutoRevertComponent::class.java) ?: return

        if (autoRevertComponent.isAutoRevertStarted) {
            autoRevertComponent.stopAutoRevert()
        } else {
            autoRevertComponent.startAutoRevert()
        }
    }

    override fun update(event: AnActionEvent) {
        val text = textFor(event.project)
        event.presentation.text = text
        event.presentation.description = text
        event.presentation.isEnabled = event.project != null
    }

    private fun textFor(project: Project?): String {
        if (project == null) return "Start auto-revert"
        val autoRevertComponent = project.getComponent(AutoRevertComponent::class.java) ?: return "Start auto-revert"

        return if (autoRevertComponent.isAutoRevertStarted) {
            "Stop auto-revert"
        } else {
            "Start auto-revert"
        }
    }
}
