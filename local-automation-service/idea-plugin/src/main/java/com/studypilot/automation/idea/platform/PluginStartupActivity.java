package com.studypilot.automation.idea.platform;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import org.jetbrains.annotations.NotNull;

/**
 * Starts the private bridge once a project is open.
 *
 * The bridge itself is application scoped, so the first project only triggers the start.
 */
public final class PluginStartupActivity implements StartupActivity {

  @Override
  public void runActivity(@NotNull Project project) {
    StudyPilotAutomationService.startOnce();
  }
}
