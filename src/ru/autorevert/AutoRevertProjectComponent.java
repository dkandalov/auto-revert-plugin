package ru.autorevert;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.AbstractProjectComponent;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.CheckinProjectPanel;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.vcs.changes.CommitContext;
import com.intellij.openapi.vcs.checkin.CheckinHandler;
import com.intellij.openapi.vcs.checkin.CheckinHandlerFactory;
import com.intellij.openapi.vcs.impl.CheckinHandlersManager;
import org.jetbrains.annotations.NotNull;

/**
 * User: dima
 * Date: 10/06/2012
 */
public class AutoRevertProjectComponent extends AbstractProjectComponent {
	private Model model;
	private TimerEventsSourceAppComponent.Listener listener;
	private IdeNotifications ideNotifications;

	protected AutoRevertProjectComponent(Project project) {
		super(project);
	}

	@Override public void projectOpened() {
		super.projectOpened();

		ideNotifications = new IdeNotifications(myProject);
		Settings settings = ServiceManager.getService(Settings.class);
		model = new Model(ideNotifications, new IdeActions(myProject), settings.secondsTillRevert());

		TimerEventsSourceAppComponent timerEventsSource = ApplicationManager.getApplication().getComponent(TimerEventsSourceAppComponent.class);
		listener = new TimerEventsSourceAppComponent.Listener() {
			@Override public void onTimerEvent() {
				model.onTimer();
			}
		};
		timerEventsSource.addListener(listener);

		// register commit callback
		CheckinHandlersManager.getInstance().registerCheckinHandlerFactory(new MyHandlerFactory(myProject, new Runnable() {
			@Override public void run() {
				model.onCommit();
			}
		}));
	}

	@Override public void disposeComponent() {
		super.disposeComponent();

		TimerEventsSourceAppComponent timerEventsSource = ApplicationManager.getApplication().getComponent(TimerEventsSourceAppComponent.class);
		timerEventsSource.removeListener(listener);
	}

	public void start() {
		model.start();
	}

	public boolean isStarted() {
		return model.isStarted();
	}

	public void stop() {
		model.stop();
	}

	public void onNewSettings(Settings settings) {
		ideNotifications.onNewSettings();
		model.onNewSettings(settings.secondsTillRevert());
	}

	private static class MyHandlerFactory extends CheckinHandlerFactory {
		private final Project project;
		private final Runnable callback;

		MyHandlerFactory(Project project, Runnable callback) {
			this.project = project;
			this.callback = callback;
		}

		@NotNull @Override
		public CheckinHandler createHandler(final CheckinProjectPanel panel, CommitContext commitContext) {
			return new CheckinHandler() {
				@Override public void checkinSuccessful() {
					if (!project.equals(panel.getProject())) return;

					ChangeListManager changeListManager = ChangeListManager.getInstance(panel.getProject());
					int uncommittedSize = changeListManager.getDefaultChangeList().getChanges().size() - panel.getSelectedChanges().size();
					if (uncommittedSize == 0) {
						callback.run();
					}
				}
			};
		}
	}
}
