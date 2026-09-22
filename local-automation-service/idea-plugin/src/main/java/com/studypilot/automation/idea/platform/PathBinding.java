package com.studypilot.automation.idea.platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Canonical filesystem binding for every trusted path the plugin handles.
 *
 * Rules:
 *   * No path component may be a symbolic link. A symlinked parent could silently move a
 *     trusted target outside the registered project, so it is rejected outright.
 *   * Paths are canonicalised (dot segments resolved) before any comparison.
 *   * Containment is decided on CANONICAL Paths component-by-component, never by string
 *     prefix, so "/work/project2" is not inside "/work/project".
 */
public final class PathBinding {

  /**
   * macOS itself ships these as symlinks into /private. They are part of the platform layout,
   * not an operator-controlled redirection, so they are tolerated explicitly and by exact
   * absolute path. Every other symlinked component is rejected.
   */
  private static final java.util.Set<String> PLATFORM_SYSTEM_LINKS = java.util.Set.of("/var", "/tmp", "/etc");

  private PathBinding() {}

  /** Rejects any symbolic link found in the path's own components. */
  public static void rejectSymlinkComponents(Path path) throws IOException {
    Path absolute = path.toAbsolutePath().normalize();
    Path current = absolute.getRoot();
    for (Path component : absolute) {
      current = current == null ? component : current.resolve(component);
      if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS) || !Files.isSymbolicLink(current)) {
        continue;
      }
      if (PLATFORM_SYSTEM_LINKS.contains(current.toString())) {
        continue;
      }
      throw new IOException("path component must not be a symbolic link");
    }
  }

  /**
   * Canonicalises a path.
   *
   * When {@code mustExist} is true the whole path must already exist. Otherwise the nearest
   * existing ancestor is canonicalised and the remaining (not yet created) components are
   * appended, which is what the plugin needs for its own socket and ledger files.
   */
  public static Path canonical(Path path, boolean mustExist) throws IOException {
    Path absolute = path.toAbsolutePath().normalize();
    rejectSymlinkComponents(absolute);

    if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
      // Following resolution is used only after every user symlink component was rejected
      // above; this makes the macOS platform links (/var, /tmp, /etc) resolve to one
      // consistent canonical form on both sides of every comparison.
      return absolute.toRealPath();
    }
    if (mustExist) {
      throw new IOException("required path does not exist");
    }

    Path ancestor = absolute;
    Path remainder = null;
    while (ancestor != null && !Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
      remainder = remainder == null ? ancestor.getFileName() : ancestor.getFileName().resolve(remainder);
      ancestor = ancestor.getParent();
    }
    if (ancestor == null) {
      throw new IOException("path has no existing ancestor");
    }
    Path canonicalAncestor = ancestor.toRealPath();
    return remainder == null ? canonicalAncestor : canonicalAncestor.resolve(remainder);
  }

  /** Component-boundary containment on canonical absolute paths. */
  public static boolean contains(Path canonicalRoot, Path canonicalCandidate) {
    if (canonicalRoot == null || canonicalCandidate == null) {
      return false;
    }
    Path root = canonicalRoot.toAbsolutePath().normalize();
    Path candidate = canonicalCandidate.toAbsolutePath().normalize();
    return candidate.startsWith(root);
  }

  /** Requires a canonical path to be an existing regular file. */
  public static void requireRegularFile(Path path) throws IOException {
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
      throw new IOException("path must be an existing regular file");
    }
  }

  /** Requires a canonical path to be an existing directory. */
  public static void requireDirectory(Path path) throws IOException {
    if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
      throw new IOException("path must be an existing directory");
    }
  }
}
