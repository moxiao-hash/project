package com.studypilot.automation.idea.platform;

import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.vfs.LocalFileSystem;
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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * IntelliJ Platform implementation of the three registered IDEA actions.
 *
 * Every method:
 *   * resolves the project from the registered canonical root (never from the request) and
 *     requires the root to be exactly the open project's canonical base path;
 *   * revalidates the target against the live project, VFS, RunManager or ToolWindow model
 *     using canonical Paths and component-boundary containment;
 *   * fails closed on zero or multiple matches, project mismatch, symlinked or non-regular
 *     targets, invalid or disposed state;
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
      return IdeOutcome.refused(
          "PROJECT_MISMATCH", "no open project base path equals the registered canonical root");
    }
    if (canonicalPath == null || !canonicalPath.startsWith("/")) {
      return IdeOutcome.refused("INVALID_TARGET", "registered file path must be absolute");
    }

    Path registeredRoot;
    Path target;
    try {
      registeredRoot = PathBinding.canonical(Path.of(projectRoot), true);
      target = PathBinding.canonical(Path.of(canonicalPath), true);
      PathBinding.requireRegularFile(target);
    } catch (IOException e) {
      return IdeOutcome.refused("TARGET_NOT_FOUND", "registered file is not a canonical regular file");
    }
    if (!PathBinding.contains(registeredRoot, target)) {
      return IdeOutcome.refused("PROJECT_MISMATCH", "registered file is outside the registered project root");
    }
    if (!canonicalPath.equals(target.toString())) {
      return IdeOutcome.refused("INVALID_TARGET", "registered path is not the canonical target path");
    }

    VirtualFile file = LocalFileSystem.getInstance().findFileByPath(canonicalPath);
    if (file == null || !file.isValid() || file.isDirectory()) {
      return IdeOutcome.refused("TARGET_NOT_FOUND", "registered file is not a valid regular file");
    }

    // Ambiguity guard: exactly one virtual entry may exist for this canonical path.
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
      return IdeOutcome.refused(
          "TARGET_AMBIGUOUS", "the registered path does not resolve to exactly one virtual file");
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
      return IdeOutcome.refused(
          "PROJECT_MISMATCH", "no open project base path equals the registered canonical root");
    }
    if (configurationName == null || configurationName.trim().isEmpty()) {
      return IdeOutcome.refused("INVALID_TARGET", "registered configuration name must be non-empty");
    }

    RunManager runManager = RunManager.getInstance(project);
    List<RunnerAndConfigurationSettings> matches = new ArrayList<>();
    for (RunnerAndConfigurationSettings settings : runManager.getAllSettings()) {
      if (configurationName.equals(settings.getName())) {
        matches.add(settings);
      }
    }
    if (matches.isEmpty()) {
      return IdeOutcome.refused(
          "RUN_CONFIGURATION_NOT_FOUND", "no existing run configuration matched the registered handle");
    }
    if (matches.size() > 1) {
      return IdeOutcome.refused(
          "TARGET_AMBIGUOUS", "more than one run configuration matched the registered handle");
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
  public IdeOutcome showTestResult(String toolWindowId, String contentDisplayName, String projectRoot) {
    Project project = findProject(projectRoot);
    if (project == null) {
      return IdeOutcome.refused(
          "PROJECT_MISMATCH", "no open project base path equals the registered canonical root");
    }
    if (toolWindowId == null || toolWindowId.trim().isEmpty()) {
      return IdeOutcome.refused("INVALID_TARGET", "registered tool window id must be non-empty");
    }

    ToolWindow window = ToolWindowManager.getInstance(project).getToolWindow(toolWindowId);
    if (window == null) {
      return IdeOutcome.refused(
          "RESULT_VIEW_NOT_PRESENT", "the registered tool window is not registered in this project");
    }
    if (window.isDisposed()) {
      return IdeOutcome.refused("IDE_DISPOSING", "the registered tool window is disposed");
    }

    ContentManager contentManager = window.getContentManager();
    List<Content> contents = new ArrayList<>();
    List<TestResultContentMatcher.ContentView> views = new ArrayList<>();
    for (Content content : contentManager.getContents()) {
      contents.add(content);
      views.add(
          new TestResultContentMatcher.ContentView(
              content.getDisplayName(), componentClassName(content), content.isValid()));
    }

    // Exactly one existing content must match the registered identity AND be a test result.
    TestResultContentMatcher.Result match = TestResultContentMatcher.match(views, contentDisplayName);
    switch (match.outcome) {
      case INVALID_REGISTRATION:
        return IdeOutcome.refused(
            "INVALID_TARGET", "the registered test-result content identity is not usable");
      case NOT_FOUND:
        return IdeOutcome.refused(
            "RESULT_VIEW_NOT_PRESENT", "no existing test-result content matched the registered identity");
      case NOT_A_TEST_RESULT:
        return IdeOutcome.refused(
            "RESULT_VIEW_NOT_TEST_RESULT",
            "a content with the registered name exists but is not a test-result view");
      case AMBIGUOUS:
        return IdeOutcome.refused(
            "TARGET_AMBIGUOUS", "more than one existing content matched the registered identity");
      case MATCHED:
      default:
        break;
    }

    Content content = contents.get(match.index);
    // Reveal ONLY: never run, rerun or schedule tests.
    if (window instanceof ToolWindowEx) {
      ((ToolWindowEx) window).activate(null, true);
    } else {
      window.show();
    }
    contentManager.setSelectedContent(content);

    // Post-state, read AFTER the action: the SAME content object must still be present,
    // selected and visible, and still identify itself as the registered test result.
    Content selectedContent = contentManager.getSelectedContent();
    boolean sameObject = selectedContent == content;
    boolean stillPresent = content.isValid() && containsIdentity(contentManager, content, contentDisplayName);
    boolean visible = window.isVisible();
    if (!sameObject || !stillPresent || !visible) {
      return IdeOutcome.unverified(
          "POST_STATE_NOT_VERIFIED",
          "the registered result content is not the selected visible test-result content afterwards");
    }
    return IdeOutcome.verified();
  }

  private static boolean containsIdentity(
      ContentManager contentManager, Content content, String contentDisplayName) {
    for (Content candidate : contentManager.getContents()) {
      if (candidate == content
          && candidate.isValid()
          && contentDisplayName.equals(candidate.getDisplayName())) {
        return true;
      }
    }
    return false;
  }

  private static String componentClassName(Content content) {
    try {
      Object component = content.getComponent();
      return component == null ? null : component.getClass().getName();
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * Resolves the open project whose canonical base path EQUALS the registered canonical root.
   *
   * A registered root nested below an unrelated open project is a mismatch, not a match.
   */
  static Project findProject(String projectRoot) {
    if (projectRoot == null || projectRoot.isEmpty()) {
      return null;
    }
    Path registeredRoot;
    try {
      registeredRoot = PathBinding.canonical(Path.of(projectRoot), true);
    } catch (IOException e) {
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
      Path candidate;
      try {
        candidate = PathBinding.canonical(Path.of(basePath), true);
      } catch (IOException e) {
        continue;
      }
      if (candidate.equals(registeredRoot)) {
        return project;
      }
    }
    return null;
  }

  /** True when the application is not shutting down. */
  static boolean isAvailable() {
    return !ApplicationManager.getApplication().isDisposed();
  }

  /** True when the canonical registered file is a symlink-free regular file. */
  static boolean isCanonicalRegularFile(String canonicalPath) {
    try {
      Path target = PathBinding.canonical(Path.of(canonicalPath), true);
      PathBinding.requireRegularFile(target);
      return target.toString().equals(canonicalPath) && !Files.isSymbolicLink(target);
    } catch (IOException e) {
      return false;
    }
  }
}
