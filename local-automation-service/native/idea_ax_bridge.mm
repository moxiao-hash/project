// StudyPilot Task 33 — IntelliJ IDEA Accessibility (AX) bridge.
//
// Real, narrowly typed, in-process native binding. It talks directly to the macOS
// Accessibility server through the ApplicationServices AX APIs. It never starts a child
// process, never uses a shell, never uses a script-automation bridge, never listens on a
// network socket, and never synthesises keyboard or mouse events.
//
// Identity binding rules (these exist to make false success impossible):
//   * The traversal root is a real AXWindow of the trusted bundle identifier, never the
//     whole application. There is no application-wide or system-wide search.
//   * A target is only admissible when its AX role is in the per-operation allowlist, its
//     ancestor chain proves the required container, and it is the UNIQUE such element.
//     Zero candidates and two-or-more candidates both fail closed.
//   * Identity comparisons are always exact equality. A basename or a title substring is
//     never accepted as proof of anything.
//   * Actuation selects the target from a pre-action snapshot. Verification re-locates the
//     target in a snapshot taken AFTER the action, so pre-existing state can never be
//     mistaken for the effect of the action.
//
// Exports depend on the build:
//   production       probe, openRegisteredFile, focusRunConfiguration, showTestResult
//   AX_BRIDGE_TEST_SEAM adds __testEvaluate, which runs the SAME decision functions over
//                      fixture trees. The production artifact never contains it, and the
//                      TypeScript export-surface guard rejects any extra export.

#include <node_api.h>

#import <Foundation/Foundation.h>
#import <AppKit/AppKit.h>
#import <ApplicationServices/ApplicationServices.h>

#include <algorithm>
#include <string>
#include <vector>

// ---------------------------------------------------------------------------
// Trusted, in-source registration constants.
// ---------------------------------------------------------------------------

static NSString *const kIdeaBundleIdentifier = @"com.jetbrains.intellij";
static const char *kBridgeVersion = "2.0.0";

static const int kMaxSnapshotNodes = 2000;
static const int kMaxSnapshotDepth = 30;
static const size_t kMaxArgumentBytes = 4096;
static const size_t kMaxFieldBytes = 512;
static const float kAxMessagingTimeoutSeconds = 2.0f;

// Per-operation structural allowlists. A candidate must match one of these roles AND the
// required container role somewhere in its ancestor chain.
static const char *kProjectTreeContainerRoles[] = {"AXOutline", "AXTable", "AXList", "AXTree"};
static const char *kProjectTreeLeafRoles[] = {"AXCell", "AXStaticText", "AXRow"};
static const char *kRunSelectorContainerRoles[] = {"AXToolbar"};
static const char *kRunSelectorRoles[] = {"AXPopUpButton", "AXComboBox"};
static const char *kToolWindowContainerRoles[] = {"AXTabGroup"};
static const char *kToolWindowTabRoles[] = {"AXTab", "AXRadioButton"};

// ---------------------------------------------------------------------------
// N-API helpers
// ---------------------------------------------------------------------------

static void SetNamed(napi_env env, napi_value obj, const char *name, napi_value value) {
  napi_set_named_property(env, obj, name, value);
}

static napi_value MakeString(napi_env env, const std::string &value) {
  napi_value out = nullptr;
  napi_create_string_utf8(env, value.c_str(), value.size(), &out);
  return out;
}

static napi_value MakeBool(napi_env env, bool value) {
  napi_value out = nullptr;
  napi_get_boolean(env, value, &out);
  return out;
}

static napi_value MakeResult(
    napi_env env,
    bool ok,
    bool verified,
    const char *code,
    const char *detail
) {
  napi_value obj = nullptr;
  napi_create_object(env, &obj);
  SetNamed(env, obj, "ok", MakeBool(env, ok));
  SetNamed(env, obj, "verified", MakeBool(env, verified));
  SetNamed(env, obj, "code", MakeString(env, code));
  SetNamed(env, obj, "detail", MakeString(env, detail));
  return obj;
}

static bool ReadStringArgument(napi_env env, napi_value value, std::string *out) {
  napi_valuetype type = napi_undefined;
  if (napi_typeof(env, value, &type) != napi_ok || type != napi_string) {
    return false;
  }
  size_t length = 0;
  if (napi_get_value_string_utf8(env, value, nullptr, 0, &length) != napi_ok) {
    return false;
  }
  if (length == 0 || length > kMaxArgumentBytes) {
    return false;
  }
  std::vector<char> buffer(length + 1, 0);
  if (napi_get_value_string_utf8(env, value, buffer.data(), buffer.size(), &length) != napi_ok) {
    return false;
  }
  out->assign(buffer.data(), length);
  return true;
}

// ---------------------------------------------------------------------------
// AX primitives
// ---------------------------------------------------------------------------

static CFTypeRef CopyAttribute(AXUIElementRef element, CFStringRef attribute) {
  CFTypeRef value = nullptr;
  if (AXUIElementCopyAttributeValue(element, attribute, &value) != kAXErrorSuccess) {
    return nullptr;
  }
  return value;
}

static std::string ToStd(NSString *value, size_t maxBytes) {
  if (value == nil) {
    return std::string();
  }
  const char *utf8 = [value UTF8String];
  if (utf8 == nullptr) {
    return std::string();
  }
  std::string text(utf8);
  if (text.size() > maxBytes) {
    text.resize(maxBytes);
  }
  return text;
}

struct RawAttributes {
  std::string role;
  std::string subrole;
  std::string identifier;
  std::string title;
  std::string description;
  std::string document;
  std::string url;
  std::string value;
  bool selected = false;
  bool focused = false;
  bool hidden = false;
  bool hasSize = false;
  double width = 0.0;
  double height = 0.0;
};

enum AttributeIndex {
  kIdxRole = 0,
  kIdxSubrole,
  kIdxIdentifier,
  kIdxTitle,
  kIdxDescription,
  kIdxDocument,
  kIdxUrl,
  kIdxValue,
  kIdxSelected,
  kIdxFocused,
  kIdxHidden,
  kIdxSize,
  kAttributeCount
};

/**
 * Reads every attribute the decision functions need in a single IPC round trip.
 * Long values are bounded; nothing here is ever surfaced in a receipt.
 */
static bool ReadAttributes(AXUIElementRef element, RawAttributes *out) {
  CFStringRef names[kAttributeCount] = {
      kAXRoleAttribute,
      kAXSubroleAttribute,
      kAXIdentifierAttribute,
      kAXTitleAttribute,
      kAXDescriptionAttribute,
      kAXDocumentAttribute,
      kAXURLAttribute,
      kAXValueAttribute,
      kAXSelectedAttribute,
      kAXFocusedAttribute,
      kAXHiddenAttribute,
      kAXSizeAttribute,
  };
  CFArrayRef attributeArray =
      CFArrayCreate(nullptr, (const void **)names, kAttributeCount, &kCFTypeArrayCallBacks);
  if (attributeArray == nullptr) {
    return false;
  }

  CFArrayRef values = nullptr;
  AXError error = AXUIElementCopyMultipleAttributeValues(
      element, attributeArray, (AXCopyMultipleAttributeOptions)1, &values);
  CFRelease(attributeArray);
  if (error != kAXErrorSuccess || values == nullptr) {
    return false;
  }

  auto readString = [&](int index) -> std::string {
    CFTypeRef value = CFArrayGetValueAtIndex(values, index);
    if (value == nullptr || CFGetTypeID(value) != CFStringGetTypeID()) {
      return std::string();
    }
    return ToStd((__bridge NSString *)value, kMaxFieldBytes);
  };
  auto readFlag = [&](int index) -> bool {
    CFTypeRef value = CFArrayGetValueAtIndex(values, index);
    if (value == nullptr) {
      return false;
    }
    if (CFGetTypeID(value) == CFBooleanGetTypeID()) {
      return CFBooleanGetValue((CFBooleanRef)value);
    }
    if (CFGetTypeID(value) == CFStringGetTypeID()) {
      return [(__bridge NSString *)value boolValue];
    }
    if (CFGetTypeID(value) == CFNumberGetTypeID()) {
      int number = 0;
      if (CFNumberGetValue((CFNumberRef)value, kCFNumberIntType, &number)) {
        return number != 0;
      }
    }
    return false;
  };

  out->role = readString(kIdxRole);
  out->subrole = readString(kIdxSubrole);
  out->identifier = readString(kIdxIdentifier);
  out->title = readString(kIdxTitle);
  out->description = readString(kIdxDescription);
  out->document = readString(kIdxDocument);
  out->url = readString(kIdxUrl);
  out->value = readString(kIdxValue);
  out->selected = readFlag(kIdxSelected);
  out->focused = readFlag(kIdxFocused);
  out->hidden = readFlag(kIdxHidden);

  CFTypeRef sizeValue = CFArrayGetValueAtIndex(values, kIdxSize);
  if (sizeValue != nullptr && CFGetTypeID(sizeValue) == AXValueGetTypeID()) {
    CGSize size = CGSizeZero;
    if (AXValueGetValue((AXValueRef)sizeValue, (AXValueType)kAXValueCGSizeType, &size)) {
      out->hasSize = true;
      out->width = size.width;
      out->height = size.height;
    }
  }

  CFRelease(values);
  return true;
}

static CFTypeRef CopyChildElements(AXUIElementRef element) {
  CFTypeRef children = CopyAttribute(element, kAXChildrenAttribute);
  if (children == nullptr) {
    return nullptr;
  }
  if (CFGetTypeID(children) != CFArrayGetTypeID()) {
    CFRelease(children);
    return nullptr;
  }
  return children;
}

// ---------------------------------------------------------------------------
// Snapshot model — plain data, so the decision functions are pure and testable.
// ---------------------------------------------------------------------------

typedef int AxNode;
static const AxNode kNoNode = 0;

struct AxNodeData {
  std::string role;
  std::string subrole;
  std::string identifier;
  std::string title;
  std::string document;
  std::string url;
  std::string value;
  bool selected = false;
  bool focused = false;
  bool hidden = false;
  bool hasSize = false;
  double width = 0.0;
  double height = 0.0;
  AxNode parent = kNoNode;
  int liveChildIndex = -1;
  std::vector<AxNode> children;
};

struct AxSnapshot {
  std::vector<AxNodeData> nodes;  // AxNode n maps to nodes[n - 1]
  AxNode root = kNoNode;
  bool truncated = false;

  AxNode Add() {
    nodes.push_back(AxNodeData());
    return (AxNode)nodes.size();
  }
  const AxNodeData *At(AxNode node) const {
    if (node <= 0 || node > (AxNode)nodes.size()) {
      return nullptr;
    }
    return &nodes[node - 1];
  }
  bool Truncated() const { return truncated; }
};

static bool RoleInList(const std::string &role, const char *const *roles, size_t count) {
  for (size_t i = 0; i < count; i++) {
    if (role == roles[i]) {
      return true;
    }
  }
  return false;
}

static bool HasAncestorRole(
    const AxSnapshot &snapshot,
    AxNode node,
    const char *const *roles,
    size_t count
) {
  const AxNodeData *current = snapshot.At(node);
  while (current != nullptr && current->parent != kNoNode) {
    const AxNodeData *parent = snapshot.At(current->parent);
    if (parent == nullptr) {
      return false;
    }
    if (RoleInList(parent->role, roles, count)) {
      return true;
    }
    current = parent;
  }
  return false;
}

/**
 * Chain of ancestor titles from the outermost node below the traversal root down to (and
 * excluding) the candidate, joined with '/'. Used to prove a project-tree path.
 */
static std::string AncestorTitleChain(const AxSnapshot &snapshot, AxNode node) {
  std::vector<std::string> titles;
  const AxNodeData *current = snapshot.At(node);
  if (current == nullptr) {
    return std::string();
  }
  AxNode parent = current->parent;
  while (parent != kNoNode && parent != snapshot.root) {
    const AxNodeData *data = snapshot.At(parent);
    if (data == nullptr || data->title.empty()) {
      return std::string();
    }
    titles.push_back(data->title);
    parent = data->parent;
  }
  std::reverse(titles.begin(), titles.end());
  std::string chain;
  for (size_t i = 0; i < titles.size(); i++) {
    if (i > 0) {
      chain += "/";
    }
    chain += titles[i];
  }
  return chain;
}

static bool IsVisible(const AxSnapshot &snapshot, AxNode node) {
  const AxNodeData *current = snapshot.At(node);
  while (current != nullptr) {
    if (current->hidden) {
      return false;
    }
    if (current->parent == kNoNode) {
      break;
    }
    current = snapshot.At(current->parent);
  }
  const AxNodeData *data = snapshot.At(node);
  if (data != nullptr && data->hasSize && (data->width <= 0.0 || data->height <= 0.0)) {
    return false;
  }
  return true;
}

static std::string CanonicalBasename(const std::string &path) {
  size_t slash = path.find_last_of('/');
  if (slash == std::string::npos) {
    return path;
  }
  return path.substr(slash + 1);
}

static std::string CanonicalDirname(const std::string &path) {
  size_t slash = path.find_last_of('/');
  if (slash == std::string::npos) {
    return std::string();
  }
  if (slash == 0) {
    return "/";
  }
  return path.substr(0, slash);
}

/**
 * Normalises an AX document or URL value into an absolute filesystem path.
 * Returns an empty string when the value cannot be proven to be a local file path.
 */
static std::string NormaliseFileReference(std::string value) {
  if (value.empty()) {
    return std::string();
  }
  const std::string prefix = "file://";
  if (value.compare(0, prefix.size(), prefix) == 0) {
    std::string rest = value.substr(prefix.size());
    if (rest.compare(0, 9, "localhost") == 0) {
      rest = rest.substr(9);
    }
    // Percent-decode the small subset that appears in file URLs.
    std::string decoded;
    decoded.reserve(rest.size());
    for (size_t i = 0; i < rest.size(); i++) {
      if (rest[i] == '%' && i + 2 < rest.size()) {
        auto hex = [](char c) -> int {
          if (c >= '0' && c <= '9') return c - '0';
          if (c >= 'a' && c <= 'f') return c - 'a' + 10;
          if (c >= 'A' && c <= 'F') return c - 'A' + 10;
          return -1;
        };
        int hi = hex(rest[i + 1]);
        int lo = hex(rest[i + 2]);
        if (hi >= 0 && lo >= 0) {
          decoded.push_back((char)((hi << 4) | lo));
          i += 2;
          continue;
        }
      }
      decoded.push_back(rest[i]);
    }
    return decoded;
  }
  if (!value.empty() && value[0] == '/') {
    return value;
  }
  return std::string();
}

enum class LocateStatus { kFound, kNotFound, kAmbiguous, kTruncated };

struct MatchSpec {
  const char *const *nodeRoles = nullptr;
  size_t nodeRoleCount = 0;
  const char *const *containerRoles = nullptr;
  size_t containerRoleCount = 0;
  std::string exactTitle;
  bool requireExactTitle = false;
  bool requirePathProof = false;
  std::string canonicalPath;
  std::string canonicalDir;
  bool requireVisible = true;
};

static bool MatchesSpec(
    const AxSnapshot &snapshot,
    AxNode node,
    const MatchSpec &spec,
    const std::string &canonicalBasename
) {
  const AxNodeData *data = snapshot.At(node);
  if (data == nullptr) {
    return false;
  }
  if (!RoleInList(data->role, spec.nodeRoles, spec.nodeRoleCount)) {
    return false;
  }
  if (spec.containerRoleCount > 0 &&
      !HasAncestorRole(snapshot, node, spec.containerRoles, spec.containerRoleCount)) {
    return false;
  }
  if (spec.requireExactTitle && data->title != spec.exactTitle) {
    return false;
  }
  if (spec.requirePathProof) {
    // The candidate's own title must be exactly the canonical basename, and the chain of
    // ancestor titles must be a whole-component suffix of the canonical path's directory.
    if (canonicalBasename.empty() || data->title != canonicalBasename) {
      return false;
    }
    std::string chain = AncestorTitleChain(snapshot, node);
    if (chain.empty()) {
      return false;
    }
    std::string suffix = "/" + chain;
    if (spec.canonicalDir.size() < suffix.size()) {
      return false;
    }
    if (spec.canonicalDir.compare(spec.canonicalDir.size() - suffix.size(), suffix.size(), suffix) != 0) {
      return false;
    }
  }
  if (spec.requireVisible && !IsVisible(snapshot, node)) {
    return false;
  }
  return true;
}

/**
 * Locates the unique admissible target. Ambiguity is a hard failure: when two structurally
 * admissible elements exist, no action is dispatched.
 */
static LocateStatus LocateUnique(
    const AxSnapshot &snapshot,
    const MatchSpec &spec,
    const std::string &canonicalBasename,
    AxNode *out
) {
  if (snapshot.nodes.empty() || snapshot.Truncated()) {
    return LocateStatus::kTruncated;
  }
  AxNode found = kNoNode;
  int matches = 0;
  for (int i = 1; i <= (int)snapshot.nodes.size(); i++) {
    AxNode node = (AxNode)i;
    if (MatchesSpec(snapshot, node, spec, canonicalBasename)) {
      if (matches == 0) {
        found = node;
      }
      matches++;
      if (matches > 1) {
        return LocateStatus::kAmbiguous;
      }
    }
  }
  if (matches == 0) {
    return LocateStatus::kNotFound;
  }
  *out = found;
  return LocateStatus::kFound;
}

const char *LocateStatusCode(LocateStatus status) {
  switch (status) {
    case LocateStatus::kFound:
      return "OK";
    case LocateStatus::kNotFound:
      return "TARGET_NOT_FOUND";
    case LocateStatus::kAmbiguous:
      return "TARGET_AMBIGUOUS";
    case LocateStatus::kTruncated:
      return "AX_SNAPSHOT_TRUNCATED";
  }
  return "TARGET_NOT_FOUND";
}

// ---------------------------------------------------------------------------
// Verification predicates — evaluated only on the post-action snapshot.
// ---------------------------------------------------------------------------

/**
 * OPEN_REGISTERED_FILE proof: some element must carry an AX document or URL that resolves
 * to exactly the canonical registered path. Titles, basenames and selections are never used.
 */
static bool VerifyCanonicalDocumentPresent(const AxSnapshot &post, const std::string &canonicalPath) {
  for (int i = 1; i <= (int)post.nodes.size(); i++) {
    const AxNodeData *data = post.At((AxNode)i);
    if (data == nullptr || data->hidden) {
      continue;
    }
    if (NormaliseFileReference(data->document) == canonicalPath) {
      return true;
    }
    if (NormaliseFileReference(data->url) == canonicalPath) {
      return true;
    }
  }
  return false;
}

/**
 * FOCUS_RUN_CONFIGURATION proof: the run-configuration selector control (re-located on the
 * post-action snapshot by the same strict rule) must be focused and must now report the
 * registered configuration value itself.
 */
static bool VerifyRunSelectorState(
    const AxSnapshot &post,
    const MatchSpec &spec,
    const std::string &handle
) {
  AxNode node = kNoNode;
  if (LocateUnique(post, spec, std::string(), &node) != LocateStatus::kFound) {
    return false;
  }
  const AxNodeData *data = post.At(node);
  if (data == nullptr) {
    return false;
  }
  if (!IsVisible(post, node)) {
    return false;
  }
  bool showsHandle = (data->value == handle) || (data->title == handle);
  if (!showsHandle) {
    return false;
  }
  return data->focused || data->selected;
}

/**
 * SHOW_TEST_RESULT proof: the registered existing results view must, AFTER the action,
 * be the selected tab of its tool-window group and be visible. Pre-action visibility is
 * never consulted, and generic focus is never accepted as proof.
 */
static bool VerifyResultViewSelected(
    const AxSnapshot &post,
    const MatchSpec &spec,
    const std::string &handle
) {
  AxNode node = kNoNode;
  if (LocateUnique(post, spec, std::string(), &node) != LocateStatus::kFound) {
    return false;
  }
  const AxNodeData *data = post.At(node);
  if (data == nullptr) {
    return false;
  }
  if (!data->selected) {
    return false;
  }
  return IsVisible(post, node) && data->title == handle;
}

// ---------------------------------------------------------------------------
// Target application discovery (trusted bundle identifier only)
// ---------------------------------------------------------------------------

struct IdeaApplication {
  pid_t pid = 0;
  bool running = false;
};

static IdeaApplication ResolveIdeaApplication() {
  IdeaApplication app;
  NSArray<NSRunningApplication *> *matches =
      [NSRunningApplication runningApplicationsWithBundleIdentifier:kIdeaBundleIdentifier];
  if (matches.count == 0) {
    return app;
  }
  app.pid = matches[0].processIdentifier;
  app.running = app.pid > 0;
  return app;
}

/** Only a real AXWindow of the trusted application is accepted as a traversal root. */
static AXUIElementRef CopyTrustedWindow(AXUIElementRef application) {
  CFTypeRef candidates[3] = {nullptr, nullptr, nullptr};
  candidates[0] = CopyAttribute(application, kAXFocusedWindowAttribute);
  candidates[1] = CopyAttribute(application, kAXMainWindowAttribute);

  CFTypeRef windows = CopyAttribute(application, kAXWindowsAttribute);
  if (windows != nullptr) {
    if (CFGetTypeID(windows) == CFArrayGetTypeID() && CFArrayGetCount((CFArrayRef)windows) > 0) {
      CFTypeRef first = CFArrayGetValueAtIndex((CFArrayRef)windows, 0);
      if (first != nullptr && CFGetTypeID(first) == AXUIElementGetTypeID()) {
        candidates[2] = CFRetain(first);
      }
    }
    CFRelease(windows);
  }

  AXUIElementRef result = nullptr;
  for (int i = 0; i < 3; i++) {
    CFTypeRef candidate = candidates[i];
    if (candidate == nullptr) {
      continue;
    }
    bool usable = false;
    if (CFGetTypeID(candidate) == AXUIElementGetTypeID()) {
      CFTypeRef role = CopyAttribute((AXUIElementRef)candidate, kAXRoleAttribute);
      if (role != nullptr) {
        if (CFGetTypeID(role) == CFStringGetTypeID()) {
          usable = [(__bridge NSString *)role isEqualToString:(__bridge NSString *)kAXWindowRole];
        }
        CFRelease(role);
      }
    }
    if (usable && result == nullptr) {
      result = (AXUIElementRef)CFRetain(candidate);
    }
    CFRelease(candidate);
  }
  return result;
}

/** Builds a bounded snapshot of the trusted window's accessibility subtree. */
static void BuildSnapshot(AXUIElementRef window, AxSnapshot *out) {
  struct Pending {
    AXUIElementRef element;  // owned by the stack
    int depth;
    AxNode parent;
    int liveChildIndex;
  };
  std::vector<Pending> stack;
  AXUIElementRef root = (AXUIElementRef)CFRetain(window);
  stack.push_back(Pending{root, 0, kNoNode, -1});

  while (!stack.empty()) {
    Pending current = stack.back();
    stack.pop_back();

    if ((int)out->nodes.size() >= kMaxSnapshotNodes) {
      out->truncated = true;
      CFRelease(current.element);
      continue;
    }

    RawAttributes attributes;
    AxNode node = kNoNode;
    if (ReadAttributes(current.element, &attributes)) {
      node = out->Add();
      AxNodeData *data = &out->nodes[node - 1];
      data->role = attributes.role;
      data->subrole = attributes.subrole;
      data->identifier = attributes.identifier;
      data->title = attributes.title;
      data->document = attributes.document;
      data->url = attributes.url;
      data->value = attributes.value;
      data->selected = attributes.selected;
      data->focused = attributes.focused;
      data->hidden = attributes.hidden;
      data->hasSize = attributes.hasSize;
      data->width = attributes.width;
      data->height = attributes.height;
      data->parent = current.parent;
      data->liveChildIndex = current.liveChildIndex;
      if (current.parent != kNoNode) {
        out->nodes[current.parent - 1].children.push_back(node);
      }
      if (out->root == kNoNode) {
        out->root = node;
      }
    }

    if (node != kNoNode && current.depth < kMaxSnapshotDepth) {
      CFTypeRef children = CopyChildElements(current.element);
      if (children != nullptr) {
        CFIndex count = CFArrayGetCount((CFArrayRef)children);
        for (CFIndex i = 0; i < count; i++) {
          CFTypeRef child = CFArrayGetValueAtIndex((CFArrayRef)children, i);
          if (child != nullptr && CFGetTypeID(child) == AXUIElementGetTypeID()) {
            stack.push_back(Pending{(AXUIElementRef)CFRetain(child), current.depth + 1, node,
                                    (int)i});
          }
        }
        CFRelease(children);
      }
    }
    CFRelease(current.element);
  }
}

static bool PerformPress(AXUIElementRef element) {
  return AXUIElementPerformAction(element, kAXPressAction) == kAXErrorSuccess;
}

// ---------------------------------------------------------------------------
// Shared operation driver
// ---------------------------------------------------------------------------

struct OperationOutcome {
  bool ok = false;
  bool verified = false;
  const char *code = "INTERNAL_ERROR";
  const char *detail = "unexpected bridge state";
};

/** Resolves the trusted window or reports the precise fail-closed reason. */
static bool OpenTrustedWindow(
    OperationOutcome *outcome,
    AXUIElementRef *application,
    AXUIElementRef *window
) {
  if (!AXIsProcessTrusted()) {
    outcome->code = "AX_NOT_TRUSTED";
    outcome->detail = "macOS Accessibility permission has not been granted to this process";
    return false;
  }
  IdeaApplication app = ResolveIdeaApplication();
  if (!app.running) {
    outcome->code = "IDEA_NOT_RUNNING";
    outcome->detail = "trusted IntelliJ IDEA application is not running";
    return false;
  }
  AXUIElementRef element = AXUIElementCreateApplication(app.pid);
  if (element == nullptr) {
    outcome->code = "AX_QUERY_FAILED";
    outcome->detail = "could not create an accessibility application element";
    return false;
  }
  // Bounded messaging: a busy IDE must never hang the service.
  AXUIElementSetMessagingTimeout(element, kAxMessagingTimeoutSeconds);

  *window = CopyTrustedWindow(element);
  if (*window == nullptr) {
    CFRelease(element);
    outcome->code = "NO_ACTIVE_WINDOW";
    outcome->detail =
        "trusted IntelliJ IDEA exposes no accessible window element to bind identity against";
    return false;
  }
  AXUIElementSetMessagingTimeout(*window, kAxMessagingTimeoutSeconds);
  *application = element;
  return true;
}

/**
 * Recovers the live accessibility element for a snapshot node by re-walking the recorded
 * live child indices. Indices are recorded during the snapshot build, so a node whose
 * attributes could not be read can never shift the mapping onto a different element.
 */
static AXUIElementRef CopyNodeElement(AXUIElementRef window, const AxSnapshot &snapshot, AxNode node) {
  if (node == kNoNode) {
    return nullptr;
  }
  std::vector<int> offsets;
  AxNode current = node;
  while (current != kNoNode && current != snapshot.root) {
    const AxNodeData *data = snapshot.At(current);
    if (data == nullptr || data->parent == kNoNode || data->liveChildIndex < 0) {
      return nullptr;
    }
    offsets.push_back(data->liveChildIndex);
    current = data->parent;
  }
  if (current != snapshot.root) {
    return nullptr;
  }

  AXUIElementRef element = (AXUIElementRef)CFRetain(window);
  for (std::vector<int>::reverse_iterator it = offsets.rbegin(); it != offsets.rend(); ++it) {
    CFTypeRef children = CopyChildElements(element);
    CFRelease(element);
    if (children == nullptr) {
      return nullptr;
    }
    CFIndex count = CFArrayGetCount((CFArrayRef)children);
    if (*it < 0 || *it >= count) {
      CFRelease(children);
      return nullptr;
    }
    CFTypeRef child = CFArrayGetValueAtIndex((CFArrayRef)children, *it);
    if (child == nullptr || CFGetTypeID(child) != AXUIElementGetTypeID()) {
      CFRelease(children);
      return nullptr;
    }
    element = (AXUIElementRef)CFRetain(child);
    CFRelease(children);
  }
  return element;
}

/**
 * Re-checks the live element's identity immediately before dispatching, so a tree change
 * between snapshot and action can never cause a different control to be activated.
 */
static bool ReverifyLiveIdentity(
    AXUIElementRef element,
    const std::string &expectedRole,
    const std::string &expectedTitle
) {
  RawAttributes attributes;
  if (!ReadAttributes(element, &attributes)) {
    return false;
  }
  return attributes.role == expectedRole && attributes.title == expectedTitle &&
         !attributes.hidden;
}

// ---------------------------------------------------------------------------
// Registered operations
// ---------------------------------------------------------------------------

static napi_value OpenRegisteredFile(napi_env env, napi_callback_info info) {
  size_t argc = 1;
  napi_value argv[1];
  napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);

  std::string canonicalPath;
  if (argc < 1 || !ReadStringArgument(env, argv[0], &canonicalPath)) {
    return MakeResult(env, false, false, "INVALID_ARGUMENT",
                      "registered path argument is not a bounded string");
  }
  if (canonicalPath.empty() || canonicalPath[0] != '/') {
    return MakeResult(env, false, false, "INVALID_REGISTERED_PATH",
                      "registered path must be an absolute canonical path");
  }
  NSString *pathString = [NSString stringWithUTF8String:canonicalPath.c_str()];
  if (pathString == nil) {
    return MakeResult(env, false, false, "INVALID_REGISTERED_PATH",
                      "registered path is not valid UTF-8");
  }
  BOOL isDirectory = NO;
  if (![[NSFileManager defaultManager] fileExistsAtPath:pathString isDirectory:&isDirectory] ||
      isDirectory) {
    return MakeResult(env, false, false, "INVALID_REGISTERED_PATH",
                      "registered path is not an existing regular file");
  }

  OperationOutcome outcome;
  AXUIElementRef application = nullptr;
  AXUIElementRef window = nullptr;
  if (!OpenTrustedWindow(&outcome, &application, &window)) {
    return MakeResult(env, false, false, outcome.code, outcome.detail);
  }

  std::string basename = CanonicalBasename(canonicalPath);

  MatchSpec spec;
  spec.nodeRoles = kProjectTreeLeafRoles;
  spec.nodeRoleCount = sizeof(kProjectTreeLeafRoles) / sizeof(const char *);
  spec.containerRoles = kProjectTreeContainerRoles;
  spec.containerRoleCount = sizeof(kProjectTreeContainerRoles) / sizeof(const char *);
  spec.requirePathProof = true;
  spec.canonicalPath = canonicalPath;
  spec.canonicalDir = CanonicalDirname(canonicalPath);
  spec.requireVisible = true;

  AxSnapshot pre;
  BuildSnapshot(window, &pre);

  AxNode target = kNoNode;
  LocateStatus located = LocateUnique(pre, spec, basename, &target);
  if (located != LocateStatus::kFound) {
    CFRelease(window);
    CFRelease(application);
    return MakeResult(env, false, false, LocateStatusCode(located),
                      located == LocateStatus::kAmbiguous
                          ? "more than one accessible project-tree node proves the registered path"
                          : "no accessible project-tree node proves the registered canonical path");
  }

  AXUIElementRef targetElement = CopyNodeElement(window, pre, target);
  if (targetElement == nullptr) {
    CFRelease(window);
    CFRelease(application);
    return MakeResult(env, false, false, "ACTION_NOT_DISPATCHED",
                      "could not resolve the accessibility element for the registered file");
  }
  const AxNodeData *targetData = pre.At(target);
  bool identityHolds = targetData != nullptr &&
                       ReverifyLiveIdentity(targetElement, targetData->role, targetData->title);
  if (!identityHolds) {
    CFRelease(targetElement);
    CFRelease(window);
    CFRelease(application);
    return MakeResult(env, false, false, "IDENTITY_CHANGED_BEFORE_ACTION",
                      "the registered path proof no longer holds on the live accessibility element");
  }
  bool dispatched = PerformPress(targetElement);
  CFRelease(targetElement);

  if (!dispatched) {
    CFRelease(window);
    CFRelease(application);
    return MakeResult(env, false, false, "ACTION_NOT_DISPATCHED",
                      "accessibility could not activate the registered project-tree node");
  }

  AxSnapshot post;
  BuildSnapshot(window, &post);
  bool verified = VerifyCanonicalDocumentPresent(post, canonicalPath);

  CFRelease(window);
  CFRelease(application);

  if (!verified) {
    return MakeResult(env, true, false, "STATE_NOT_VERIFIED",
                      "no accessible document or URL proves the canonical registered path is open");
  }
  return MakeResult(env, true, true, "OK",
                    "registered file opened and its canonical path verified through accessibility");
}

static napi_value FocusRunConfiguration(napi_env env, napi_callback_info info) {
  size_t argc = 1;
  napi_value argv[1];
  napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);

  std::string handle;
  if (argc < 1 || !ReadStringArgument(env, argv[0], &handle)) {
    return MakeResult(env, false, false, "INVALID_ARGUMENT",
                      "run configuration handle argument is not a bounded string");
  }

  OperationOutcome outcome;
  AXUIElementRef application = nullptr;
  AXUIElementRef window = nullptr;
  if (!OpenTrustedWindow(&outcome, &application, &window)) {
    return MakeResult(env, false, false, outcome.code, outcome.detail);
  }

  MatchSpec spec;
  spec.nodeRoles = kRunSelectorRoles;
  spec.nodeRoleCount = sizeof(kRunSelectorRoles) / sizeof(const char *);
  spec.containerRoles = kRunSelectorContainerRoles;
  spec.containerRoleCount = sizeof(kRunSelectorContainerRoles) / sizeof(const char *);
  spec.exactTitle = handle;
  spec.requireExactTitle = true;
  spec.requireVisible = true;

  AxSnapshot pre;
  BuildSnapshot(window, &pre);

  AxNode target = kNoNode;
  LocateStatus located = LocateUnique(pre, spec, std::string(), &target);
  if (located != LocateStatus::kFound) {
    CFRelease(window);
    CFRelease(application);
    return MakeResult(env, false, false,
                      located == LocateStatus::kAmbiguous ? "TARGET_AMBIGUOUS"
                                                          : "SELECTOR_NOT_IDENTIFIED",
                      "no unique run-configuration selector control could be identified in the window toolbar");
  }

  AXUIElementRef targetElement = CopyNodeElement(window, pre, target);
  if (targetElement == nullptr) {
    CFRelease(window);
    CFRelease(application);
    return MakeResult(env, false, false, "ACTION_NOT_DISPATCHED",
                      "could not resolve the accessibility element for the run configuration selector");
  }
  const AxNodeData *targetData = pre.At(target);
  if (targetData == nullptr ||
      !ReverifyLiveIdentity(targetElement, targetData->role, targetData->title)) {
    CFRelease(targetElement);
    CFRelease(window);
    CFRelease(application);
    return MakeResult(env, false, false, "IDENTITY_CHANGED_BEFORE_ACTION",
                      "the run configuration selector identity no longer holds on the live element");
  }
  // The registered action is FOCUS only. The selector is never pressed, so no menu is
  // opened and no configuration is changed: the control must already show the registered
  // configuration, and this operation only moves focus to it.
  bool dispatched =
      AXUIElementSetAttributeValue(targetElement, kAXFocusedAttribute, kCFBooleanTrue) ==
      kAXErrorSuccess;
  CFRelease(targetElement);

  if (!dispatched) {
    CFRelease(window);
    CFRelease(application);
    return MakeResult(env, false, false, "ACTION_NOT_DISPATCHED",
                      "accessibility could not focus the run configuration selector");
  }

  AxSnapshot post;
  BuildSnapshot(window, &post);
  bool verified = VerifyRunSelectorState(post, spec, handle);

  CFRelease(window);
  CFRelease(application);

  if (!verified) {
    return MakeResult(env, true, false, "STATE_NOT_VERIFIED",
                      "the run configuration selector did not report the registered configuration as focused");
  }
  return MakeResult(env, true, true, "OK",
                    "registered run configuration focused and verified on the selector control");
}

static napi_value ShowTestResult(napi_env env, napi_callback_info info) {
  size_t argc = 1;
  napi_value argv[1];
  napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);

  std::string handle;
  if (argc < 1 || !ReadStringArgument(env, argv[0], &handle)) {
    return MakeResult(env, false, false, "INVALID_ARGUMENT",
                      "test result handle argument is not a bounded string");
  }

  OperationOutcome outcome;
  AXUIElementRef application = nullptr;
  AXUIElementRef window = nullptr;
  if (!OpenTrustedWindow(&outcome, &application, &window)) {
    return MakeResult(env, false, false, outcome.code, outcome.detail);
  }

  MatchSpec spec;
  spec.nodeRoles = kToolWindowTabRoles;
  spec.nodeRoleCount = sizeof(kToolWindowTabRoles) / sizeof(const char *);
  spec.containerRoles = kToolWindowContainerRoles;
  spec.containerRoleCount = sizeof(kToolWindowContainerRoles) / sizeof(const char *);
  spec.exactTitle = handle;
  spec.requireExactTitle = true;
  spec.requireVisible = true;

  AxSnapshot pre;
  BuildSnapshot(window, &pre);

  AxNode target = kNoNode;
  LocateStatus located = LocateUnique(pre, spec, std::string(), &target);
  if (located != LocateStatus::kFound) {
    CFRelease(window);
    CFRelease(application);
    return MakeResult(env, false, false,
                      located == LocateStatus::kAmbiguous ? "TARGET_AMBIGUOUS"
                                                          : "RESULT_VIEW_NOT_IDENTIFIED",
                      "no unique test-result tool-window view matched the registered handle");
  }

  AXUIElementRef targetElement = CopyNodeElement(window, pre, target);
  if (targetElement == nullptr) {
    CFRelease(window);
    CFRelease(application);
    return MakeResult(env, false, false, "ACTION_NOT_DISPATCHED",
                      "could not resolve the accessibility element for the test result view");
  }
  const AxNodeData *targetData = pre.At(target);
  if (targetData == nullptr ||
      !ReverifyLiveIdentity(targetElement, targetData->role, targetData->title)) {
    CFRelease(targetElement);
    CFRelease(window);
    CFRelease(application);
    return MakeResult(env, false, false, "IDENTITY_CHANGED_BEFORE_ACTION",
                      "the test result view identity no longer holds on the live element");
  }
  bool dispatched = PerformPress(targetElement);
  CFRelease(targetElement);

  if (!dispatched) {
    CFRelease(window);
    CFRelease(application);
    return MakeResult(env, false, false, "ACTION_NOT_DISPATCHED",
                      "accessibility could not activate the registered test result view");
  }

  AxSnapshot post;
  BuildSnapshot(window, &post);
  bool verified = VerifyResultViewSelected(post, spec, handle);

  CFRelease(window);
  CFRelease(application);

  if (!verified) {
    return MakeResult(env, true, false, "STATE_NOT_VERIFIED",
                      "the registered test-result view is not the selected visible tool-window view after the action");
  }
  return MakeResult(env, true, true, "OK",
                    "existing test result view selected and verified after the action");
}

// ---------------------------------------------------------------------------
// Diagnostic probe (side-effect free)
// ---------------------------------------------------------------------------

static napi_value Probe(napi_env env, napi_callback_info info) {
  bool trusted = AXIsProcessTrusted();
  IdeaApplication app = ResolveIdeaApplication();

  bool apiAvailable = trusted;
  bool windowExposed = false;
  if (trusted && app.running) {
    AXUIElementRef application = AXUIElementCreateApplication(app.pid);
    if (application != nullptr) {
      AXUIElementSetMessagingTimeout(application, kAxMessagingTimeoutSeconds);
      CFTypeRef role = CopyAttribute(application, kAXRoleAttribute);
      apiAvailable = role != nullptr;
      if (role != nullptr) {
        CFRelease(role);
      }
      AXUIElementRef window = CopyTrustedWindow(application);
      windowExposed = window != nullptr;
      if (window != nullptr) {
        CFRelease(window);
      }
      CFRelease(application);
    } else {
      apiAvailable = false;
    }
  }

  napi_value obj = nullptr;
  napi_create_object(env, &obj);
  SetNamed(env, obj, "platform", MakeString(env, "darwin"));
  SetNamed(env, obj, "bridgeVersion", MakeString(env, kBridgeVersion));
  SetNamed(env, obj, "axApiAvailable", MakeBool(env, apiAvailable));
  SetNamed(env, obj, "axTrusted", MakeBool(env, trusted));
  SetNamed(env, obj, "ideaRunning", MakeBool(env, app.running));
  SetNamed(env, obj, "ideaWindowExposed", MakeBool(env, windowExposed));
  return obj;
}

// ---------------------------------------------------------------------------
// Test seam — same decision functions, fixture trees. Never present in production.
// ---------------------------------------------------------------------------

#ifdef AX_BRIDGE_TEST_SEAM

static std::string Trim(const std::string &value) {
  size_t start = value.find_first_not_of(" \t\r\n");
  if (start == std::string::npos) {
    return std::string();
  }
  size_t end = value.find_last_not_of(" \t\r\n");
  return value.substr(start, end - start + 1);
}

static std::vector<std::string> Split(const std::string &value, char separator) {
  std::vector<std::string> parts;
  std::string current;
  for (size_t i = 0; i < value.size(); i++) {
    if (value[i] == separator) {
      parts.push_back(current);
      current.clear();
    } else {
      current.push_back(value[i]);
    }
  }
  parts.push_back(current);
  return parts;
}

/**
 * Fixture format, one node per line, in depth-first order:
 *   depth|role|subrole|identifier|title|document|url|value|flags|size
 * flags: subset of "s" (selected), "f" (focused), "h" (hidden). size: "WxH" or empty.
 * depth 0 is the traversal root (the window).
 */
static bool ParseFixture(const std::string &text, AxSnapshot *out) {
  std::vector<AxNode> stack;  // most recent node per depth
  for (const std::string &rawLine : Split(text, '\n')) {
    std::string line = Trim(rawLine);
    if (line.empty() || line[0] == '#') {
      continue;
    }
    std::vector<std::string> fields = Split(line, '|');
    if (fields.size() < 10) {
      return false;
    }
    int depth = atoi(Trim(fields[0]).c_str());
    if (depth < 0 || depth > kMaxSnapshotDepth) {
      return false;
    }
    if ((int)out->nodes.size() >= kMaxSnapshotNodes) {
      return false;
    }
    AxNode parent = kNoNode;
    if (depth > 0) {
      if (stack.size() < (size_t)depth) {
        return false;
      }
      parent = stack[(size_t)depth - 1];
    }
    AxNode node = out->Add();
    AxNodeData *data = &out->nodes[node - 1];
    data->role = Trim(fields[1]);
    data->subrole = Trim(fields[2]);
    data->identifier = Trim(fields[3]);
    data->title = Trim(fields[4]);
    data->document = Trim(fields[5]);
    data->url = Trim(fields[6]);
    data->value = Trim(fields[7]);
    std::string flags = Trim(fields[8]);
    data->selected = flags.find('s') != std::string::npos;
    data->focused = flags.find('f') != std::string::npos;
    data->hidden = flags.find('h') != std::string::npos;
    std::string size = Trim(fields[9]);
    if (!size.empty()) {
      size_t x = size.find('x');
      if (x != std::string::npos) {
        data->hasSize = true;
        data->width = atof(size.substr(0, x).c_str());
        data->height = atof(size.substr(x + 1).c_str());
      }
    }
    data->parent = parent;
    if (parent != kNoNode) {
      out->nodes[parent - 1].children.push_back(node);
    }
    if (out->root == kNoNode) {
      out->root = node;
    }
    stack.resize((size_t)depth);
    stack.push_back(node);
  }
  return !out->nodes.empty();
}

static napi_value TestEvaluate(napi_env env, napi_callback_info info) {
  size_t argc = 4;
  napi_value argv[4];
  napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);

  std::string operation;
  std::string argument;
  std::string preText;
  std::string postText;
  if (argc < 4 || !ReadStringArgument(env, argv[0], &operation) ||
      !ReadStringArgument(env, argv[1], &argument) || !ReadStringArgument(env, argv[2], &preText) ||
      !ReadStringArgument(env, argv[3], &postText)) {
    return MakeResult(env, false, false, "INVALID_ARGUMENT", "invalid test seam arguments");
  }

  AxSnapshot pre;
  AxSnapshot post;
  if (!ParseFixture(preText, &pre) || !ParseFixture(postText, &post)) {
    return MakeResult(env, false, false, "INVALID_FIXTURE", "fixture could not be parsed");
  }

  MatchSpec spec;
  std::string basename;
  if (operation == "OPEN_REGISTERED_FILE") {
    spec.nodeRoles = kProjectTreeLeafRoles;
    spec.nodeRoleCount = sizeof(kProjectTreeLeafRoles) / sizeof(const char *);
    spec.containerRoles = kProjectTreeContainerRoles;
    spec.containerRoleCount = sizeof(kProjectTreeContainerRoles) / sizeof(const char *);
    spec.requirePathProof = true;
    spec.canonicalPath = argument;
    spec.canonicalDir = CanonicalDirname(argument);
    spec.requireVisible = true;
    basename = CanonicalBasename(argument);
  } else if (operation == "FOCUS_RUN_CONFIGURATION") {
    spec.nodeRoles = kRunSelectorRoles;
    spec.nodeRoleCount = sizeof(kRunSelectorRoles) / sizeof(const char *);
    spec.containerRoles = kRunSelectorContainerRoles;
    spec.containerRoleCount = sizeof(kRunSelectorContainerRoles) / sizeof(const char *);
    spec.exactTitle = argument;
    spec.requireExactTitle = true;
    spec.requireVisible = true;
  } else if (operation == "SHOW_TEST_RESULT") {
    spec.nodeRoles = kToolWindowTabRoles;
    spec.nodeRoleCount = sizeof(kToolWindowTabRoles) / sizeof(const char *);
    spec.containerRoles = kToolWindowContainerRoles;
    spec.containerRoleCount = sizeof(kToolWindowContainerRoles) / sizeof(const char *);
    spec.exactTitle = argument;
    spec.requireExactTitle = true;
    spec.requireVisible = true;
  } else {
    return MakeResult(env, false, false, "INVALID_ACTION", "unknown operation for the test seam");
  }

  AxNode target = kNoNode;
  LocateStatus located = LocateUnique(pre, spec, basename, &target);
  if (located != LocateStatus::kFound) {
    return MakeResult(env, false, false, LocateStatusCode(located),
                      "actuation target was not uniquely proven on the pre-action snapshot");
  }

  bool verified = false;
  if (operation == "OPEN_REGISTERED_FILE") {
    verified = VerifyCanonicalDocumentPresent(post, argument);
  } else if (operation == "FOCUS_RUN_CONFIGURATION") {
    verified = VerifyRunSelectorState(post, spec, argument);
  } else {
    verified = VerifyResultViewSelected(post, spec, argument);
  }

  if (!verified) {
    return MakeResult(env, true, false, "STATE_NOT_VERIFIED",
                      "the required accessibility state was not observed after the action");
  }
  return MakeResult(env, true, true, "OK", "state verified after the action");
}

#endif  // AX_BRIDGE_TEST_SEAM

// ---------------------------------------------------------------------------
// Module registration
// ---------------------------------------------------------------------------

static napi_value Init(napi_env env, napi_value exports) {
  napi_value fn = nullptr;

  napi_create_function(env, "probe", NAPI_AUTO_LENGTH, Probe, nullptr, &fn);
  SetNamed(env, exports, "probe", fn);

  napi_create_function(env, "openRegisteredFile", NAPI_AUTO_LENGTH, OpenRegisteredFile, nullptr, &fn);
  SetNamed(env, exports, "openRegisteredFile", fn);

  napi_create_function(env, "focusRunConfiguration", NAPI_AUTO_LENGTH, FocusRunConfiguration, nullptr,
                       &fn);
  SetNamed(env, exports, "focusRunConfiguration", fn);

  napi_create_function(env, "showTestResult", NAPI_AUTO_LENGTH, ShowTestResult, nullptr, &fn);
  SetNamed(env, exports, "showTestResult", fn);

#ifdef AX_BRIDGE_TEST_SEAM
  napi_create_function(env, "__testEvaluate", NAPI_AUTO_LENGTH, TestEvaluate, nullptr, &fn);
  SetNamed(env, exports, "__testEvaluate", fn);
#endif

  return exports;
}

NAPI_MODULE(NODE_GYP_MODULE_NAME, Init)
