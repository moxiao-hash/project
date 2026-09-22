package com.studypilot.automation.idea.platform;

import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.openapi.wm.ex.ToolWindowEx;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentManager;
import com.studypilot.automation.idea.dispatch.IdeOutcome;
import com.studypilot.automation.idea.dispatch.IdePlatform;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * IntelliJ Platform implementation of the three registered IDEA actions.
 *
 * Every method:
 *   * resolves the project from the registered root (never from a request),
 *   * revalidates the target against the live project and VFS / RunManager / ToolWindow model,
 *   * fails closed on zero or multiple matches, project mismatch, invalid or disposed state,
 *   * observes the required post-state through the platform API AFTER acting.
 *
 * Nothing here can run, debug or schedule tests, execute a generic Action, drive Robot, or
 * touch anything outside the registered target.
 */
public final class IdeaPlatformOperations implements IdePlatform {

  @Override
  public IdeOutcome openRegisteredFile(String canonicalPath, String projectRoot) {
    Project project = findProject(projectRoot);
    if (project == null) {
      return IdeOutcome.refused("PROJECT_MISMATCH", "no open project matches the registered root");
    }
    if (!canonicalPath.startsWith("/")) {
      return IdeOutcome.refused("INVALID_TARGET", "registered file path must be absolute");
    }
    Path asPath = Path.of(canonicalPath);
    if (Files.isSymbolicLink(asPath)) {
      return IdeOutcome.refused("SYMLINK_REJECTED", "registered file must not be a symlink");
    }

    VirtualFile registeredRoot = LocalFileSystem.getInstance().findFileByPath(projectRoot);
    if (registeredRoot == null || !registeredRoot.isValid() || !registeredRoot.isDirectory()) {
      return IdeOutcome.refused("PROJECT_MISMATCH", "registered project root is not an available directory");
    }

    VirtualFile file = LocalFileSystem.getInstance().findFileByPath(canonicalPath);
    if (file == null || !file.isValid() || file.isDirectory()) {
      return IdeOutcome.refused("TARGET_NOT_FOUND", "registered file is not a valid regular file");
    }
    if (!canonicalPath.equals(file.getPath())) {
      return IdeOutcome.refused("TARGET_NOT_FOUND", "resolved file does not match the registered canonical path");
    }
    if (!VfsUtilCore.isAncestor(registeredRoot, file, false)) {
      return IdeOutcome.refused("PROJECT_MISMATCH", "registered file is outside the registered project root");
    }

    // Ambiguity guard: exactly one virtual entry may exist for this canonical path, so a
    // caller can never be answered on the strength of an ambiguous VFS entry.
    VirtualFile parent = file.getParent();
    int sameNameSiblings = 0;
    if (parent != null) {
      for (VirtualFile sibling : parent.getChildren()) {
        if (sibling.getName().equals(file.getName())) {
          sameNameSiblings++;
        }
      }
    }
    if (parent == null || sameNameSiblings != 1) {
      return IdeOutcome.refused("TARGET_AMBIGUOUS", "the registered path does not resolve to exactly one virtual file");
    }

    FileEditorManager editors = FileEditorManager.getInstance(project);
    editors.openTextEditor(new OpenFileDescriptor(project, file), true);

    // Post-state: the exact canonical VirtualFile must be the selected file of this project.
    boolean selected = false;
    for (VirtualFile selectedFile : editors.getSelectedFiles()) {
      if (canonicalPath.equals(selectedFile.getPath())) {
        selected = true;
        break;
      }
    }
    boolean hasEditor = editors.getSelectedEditor(file) != null;
    if (!selected || !hasEditor) {
      return IdeOutcome.unverified(
          "POST_STATE_NOT_VERIFIED", "the registered file is not the selected file after opening");
    }
    return IdeOutcome.verified();
  }

  @Override
  public IdeOutcome focusRunConfiguration(String configurationName, String projectRoot) {
    Project project = findProject(projectRoot);
    if (project == null) {
      return IdeOutcome.refused("PROJECT_MISMATCH", "no open project matches the registered root");
    }

    RunManager runManager = RunManager.getInstance(project);
    List<RunnerAndConfigurationSettings> matches = new ArrayList<>();
    for (RunnerAndConfigurationSettings settings : runManager.getAllSettings()) {
      if (configurationName.equals(settings.getName())) {
        matches.add(settings);
      }
    }
    if (matches.isEmpty()) {
      return IdeOutcome.refused("RUN_CONFIGURATION_NOT_FOUND", "no existing run configuration matched the registered handle");
    }
    if (matches.size() > 1) {
      return IdeOutcome.refused("TARGET_AMBIGUOUS", "more than one run configuration matched the registered handle");
    }

    RunnerAndConfigurationSettings target = matches.get(0);
    runManager.setSelectedConfiguration(target);

    // Post-state: the selected configuration identity must be the registered one. Nothing was
    // run, debugged or edited.
    RunnerAndConfigurationSettings selected = runManager.getSelectedConfiguration();
    if (selected == null || !configurationName.equals(selected.getName())) {
      return IdeOutcome.unverified(
          "POST_STATE_NOT_VERIFIED", "the registered configuration is not the selected configuration");
    }
    return IdeOutcome.verified();
  }

  @Override
  public IdeOutcome showTestResult(String toolWindowId, String projectRoot) {
    Project project = findProject(projectRoot);
    if (project == null) {
      return IdeOutcome.refused("PROJECT_MISMATCH", "no open project matches the registered root");
    }

    ToolWindow window = ToolWindowManager.getInstance(project).getToolWindow(toolWindowId);
    if (window == null) {
      return IdeOutcome.refused("RESULT_VIEW_NOT_PRESENT", "the registered tool window is not registered in this project");
    }
    if (window.isDisposed()) {
      return IdeOutcome.refused("IDE_DISPOSING", "the registered tool window is disposed");
    }

    ContentManager contentManager = window.getContentManager();
    List<Content> existing = new ArrayList<>();
    for (Content content : contentManager.getContents()) {
      if (content.isValid()) {
        existing.add(content);
      }
    }
    if (existing.isEmpty()) {
      return IdeOutcome.refused("RESULT_VIEW_NOT_PRESENT", "no existing result content is available");
    }
    if (existing.size() > 1) {
      // Ambiguity is a refusal, never a guess.
      return IdeOutcome.refused("TARGET_AMBIGUOUS", "more than one existing result content is available");
    }

    Content content = existing.get(0);
    // Reveal ONLY: never run, rerun or schedule tests.
    if (window instanceof ToolWindowEx) {
      ((ToolWindowEx) window).activate(null, true);
    } else {
      window.show();
    }
    contentManager.setSelectedContent(content);

    // Post-state, read AFTER the action: the exact existing content must be present, selected
    // and visible. Existence before the action is never used as evidence.
    boolean stillPresent = content.isValid() && contentManager.getContents().length == existing.size();
    boolean isSelected = contentManager.getSelectedContent() == content;
    boolean visible = window.isVisible();
    if (!stillPresent || !isSelected || !visible) {
      return IdeOutcome.unverified(
          "POST_STATE_NOT_VERIFIED", "the existing result content is not the selected visible content");
    }
    return IdeOutcome.verified();
  }

  /** Resolves the open project whose base path equals or contains the registered root. */
  static Project findProject(String projectRoot) {
    if (projectRoot == null || projectRoot.isEmpty()) {
      return null;
    }
    for (Project project : ProjectManager.getInstance().getOpenProjects()) {
      if (project.isDisposed()) {
        continue;
      }
      String basePath = project.getBasePath();
      if (basePath == null) {
        continue;
      }
      if (projectRoot.equals(basePath)) {
        return project;
      }
      if (projectRoot.startsWith(basePath.endsWith("/") ? basePath : basePath + "/")) {
        return project;
      }
    }
    return null;
  }

  /** True when the application is not shutting down. */
  static boolean isAvailable() {
    return !ApplicationManager.getApplication().isDisposed();
  }
}
