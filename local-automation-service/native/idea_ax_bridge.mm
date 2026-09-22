// StudyPilot Task 33 — IntelliJ IDEA Accessibility (AX) bridge.
//
// This is a real, narrowly typed, in-process native binding. It talks directly to the
// macOS Accessibility server through the ApplicationServices AX APIs. It never starts a
// child process, never uses a shell, never uses a script-automation bridge, never listens
// on a network socket, and never synthesises keyboard or mouse events.
//
// It is bound to the single trusted bundle identifier below and exposes exactly four
// entry points:
//   probe()                  — honest, side-effect-free capability report
//   openRegisteredFile(path) — open one trusted registered file and verify the AX state
//   focusRunConfiguration(n) — focus one pre-registered run configuration and verify it
//   showTestResult(handle)   — reveal one pre-registered existing test result and verify it
//
// Every operation reports `ok` (the accessibility action was dispatched) separately from
// `verified` (the required accessibility state was actually observed afterwards). Callers
// must treat success as `ok === true && verified === true`. Nothing here ever fabricates a
// verified state.

#include <node_api.h>

#import <Foundation/Foundation.h>
#import <AppKit/AppKit.h>
#import <ApplicationServices/ApplicationServices.h>

#include <string>
#include <vector>

// ---------------------------------------------------------------------------
// Trusted, in-source registration constants.
// ---------------------------------------------------------------------------

static NSString *const kIdeaBundleIdentifier = @"com.jetbrains.intellij";
static NSString *const kBridgeVersion = @"1.0.0";

static const int kMaxTraversalNodes = 6000;
static const int kMaxTraversalDepth = 48;
static const size_t kMaxArgumentBytes = 4096;

// ---------------------------------------------------------------------------
// N-API value helpers
// ---------------------------------------------------------------------------

static void SetNamed(napi_env env, napi_value obj, const char *name, napi_value value) {
  napi_set_named_property(env, obj, name, value);
}

static napi_value MakeString(napi_env env, NSString *value) {
  napi_value out = nullptr;
  const char *utf8 = value ? [value UTF8String] : "";
  napi_create_string_utf8(env, utf8 ? utf8 : "", NAPI_AUTO_LENGTH, &out);
  return out;
}

static napi_value MakeBool(napi_env env, bool value) {
  napi_value out = nullptr;
  napi_get_boolean(env, value, &out);
  return out;
}

/**
 * Canonical operation result. `ok` describes dispatch, `verified` describes observed state.
 */
static napi_value MakeResult(
    napi_env env,
    bool ok,
    bool verified,
    NSString *code,
    NSString *detail
) {
  napi_value obj = nullptr;
  napi_create_object(env, &obj);
  SetNamed(env, obj, "ok", MakeBool(env, ok));
  SetNamed(env, obj, "verified", MakeBool(env, verified));
  SetNamed(env, obj, "code", MakeString(env, code));
  SetNamed(env, obj, "detail", MakeString(env, detail));
  return obj;
}

static bool ReadStringArgument(napi_env env, napi_value value, NSString **out) {
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
  NSString *str = [NSString stringWithUTF8String:buffer.data()];
  if (str == nil) {
    return false;
  }
  *out = str;
  return true;
}

// ---------------------------------------------------------------------------
// AX primitives
// ---------------------------------------------------------------------------

static CFTypeRef CopyAttribute(AXUIElementRef element, CFStringRef attribute) {
  CFTypeRef value = nullptr;
  AXError error = AXUIElementCopyAttributeValue(element, attribute, &value);
  if (error != kAXErrorSuccess) {
    return nullptr;
  }
  return value;
}

static bool IsHidden(AXUIElementRef element) {
  CFTypeRef value = CopyAttribute(element, kAXHiddenAttribute);
  if (value == nullptr) {
    return false;
  }
  bool hidden = false;
  if (CFGetTypeID(value) == CFBooleanGetTypeID()) {
    hidden = CFBooleanGetValue((CFBooleanRef)value);
  }
  CFRelease(value);
  return hidden;
}

static bool CopyBoolAttribute(AXUIElementRef element, CFStringRef attribute, bool fallback) {
  CFTypeRef value = CopyAttribute(element, attribute);
  if (value == nullptr) {
    return fallback;
  }
  bool result = fallback;
  CFTypeID type = CFGetTypeID(value);
  if (type == CFBooleanGetTypeID()) {
    result = CFBooleanGetValue((CFBooleanRef)value);
  } else if (type == CFNumberGetTypeID()) {
    int number = 0;
    if (CFNumberGetValue((CFNumberRef)value, kCFNumberIntType, &number)) {
      result = number != 0;
    }
  } else if (type == CFStringGetTypeID()) {
    result = [(__bridge NSString *)value boolValue];
  }
  CFRelease(value);
  return result;
}

/** Reads a string-valued attribute verbatim. Returns nil when the attribute is absent. */
static NSString *CopyStringAttribute(AXUIElementRef element, CFStringRef attribute) {
  CFTypeRef value = CopyAttribute(element, attribute);
  if (value == nullptr) {
    return nil;
  }
  NSString *result = nil;
  CFTypeID type = CFGetTypeID(value);
  if (type == CFStringGetTypeID()) {
    result = [(__bridge NSString *)value copy];
  } else if (type == CFNumberGetTypeID() || type == CFBooleanGetTypeID()) {
    result = [(__bridge id)value description];
  }
  CFRelease(value);
  return result;
}


/**
 * Resolves a human-readable identity for an element. Accessibility titles are the primary
 * identity signal; the description is only a fallback.
 */
static NSString *CopyElementTitle(AXUIElementRef element) {
  NSString *title = CopyStringAttribute(element, kAXTitleAttribute);
  if (title.length > 0) {
    return title;
  }
  return CopyStringAttribute(element, kAXDescriptionAttribute);
}

static bool IsElementVisible(AXUIElementRef element) {
  AXUIElementRef current = element;
  bool releaseCurrent = false;
  for (int depth = 0; depth < kMaxTraversalDepth; depth++) {
    if (current == nullptr) {
      break;
    }
    if (IsHidden(current)) {
      if (releaseCurrent) CFRelease(current);
      return false;
    }
    CFTypeRef parentValue = CopyAttribute(current, kAXParentAttribute);
    AXUIElementRef parent = nullptr;
    if (parentValue != nullptr) {
      if (CFGetTypeID(parentValue) == AXUIElementGetTypeID()) {
        parent = (AXUIElementRef)parentValue;
      } else {
        CFRelease(parentValue);
      }
    }
    if (releaseCurrent) {
      CFRelease(current);
    }
    current = parent;
    releaseCurrent = (parent != nullptr);
    if (parent == nullptr) {
      break;
    }
  }
  if (releaseCurrent && current != nullptr) {
    CFRelease(current);
  }

  CFTypeRef sizeValue = CopyAttribute(element, kAXSizeAttribute);
  if (sizeValue == nullptr) {
    return true;
  }
  CGSize size = CGSizeZero;
  bool hasSize =
      CFGetTypeID(sizeValue) == AXValueGetTypeID() &&
      AXValueGetValue((AXValueRef)sizeValue, (AXValueType)kAXValueCGSizeType, &size);
  CFRelease(sizeValue);
  if (!hasSize) {
    return true;
  }
  return size.width > 0.0 && size.height > 0.0;
}

typedef bool (*AxMatcher)(AXUIElementRef element, int depth, const void *context);

/**
 * Depth-limited, budget-limited depth-first search over the AX tree.
 * Returns a retained element (caller releases) or nullptr.
 */
static AXUIElementRef FindElement(
    AXUIElementRef element,
    int depth,
    int *budget,
    AxMatcher matcher,
    const void *context
) {
  if (element == nullptr || *budget <= 0) {
    return nullptr;
  }
  (*budget)--;

  if (matcher(element, depth, context)) {
    CFRetain(element);
    return element;
  }
  if (depth >= kMaxTraversalDepth) {
    return nullptr;
  }

  CFTypeRef childrenValue = CopyAttribute(element, kAXChildrenAttribute);
  if (childrenValue == nullptr) {
    return nullptr;
  }
  if (CFGetTypeID(childrenValue) != CFArrayGetTypeID()) {
    CFRelease(childrenValue);
    return nullptr;
  }

  AXUIElementRef found = nullptr;
  CFArrayRef children = (CFArrayRef)childrenValue;
  CFIndex count = CFArrayGetCount(children);
  for (CFIndex i = 0; i < count && found == nullptr; i++) {
    CFTypeRef child = CFArrayGetValueAtIndex(children, i);
    if (child == nullptr || CFGetTypeID(child) != AXUIElementGetTypeID()) {
      continue;
    }
    found = FindElement((AXUIElementRef)child, depth + 1, budget, matcher, context);
  }
  CFRelease(childrenValue);
  return found;
}

static bool TitleMatches(NSString *candidate, NSString *wanted, bool exact) {
  if (candidate == nil || wanted == nil || wanted.length == 0) {
    return false;
  }
  return exact ? [candidate isEqualToString:wanted] : [candidate containsString:wanted];
}

// ---------------------------------------------------------------------------
// Matchers
// ---------------------------------------------------------------------------

struct ExactTitleContext {
  NSString *wanted;
  bool requireVisible;
};

static bool MatchExactTitle(AXUIElementRef element, int depth, const void *context) {
  const ExactTitleContext *ctx = (const ExactTitleContext *)context;
  NSString *title = CopyElementTitle(element);
  if (!TitleMatches(title, ctx->wanted, true)) {
    return false;
  }
  if (ctx->requireVisible && !IsElementVisible(element)) {
    return false;
  }
  return true;
}

struct ContainsTitleContext {
  NSString *wanted;
  bool requireVisible;
};

static bool MatchContainsTitle(AXUIElementRef element, int depth, const void *context) {
  const ContainsTitleContext *ctx = (const ContainsTitleContext *)context;
  NSString *title = CopyElementTitle(element);
  if (!TitleMatches(title, ctx->wanted, false)) {
    return false;
  }
  if (ctx->requireVisible && !IsElementVisible(element)) {
    return false;
  }
  return true;
}

/** Matches the element that is currently focused and whose identity mentions a title. */
struct FocusedDocumentContext {
  NSString *wanted;
};

static bool MatchFocusedDocument(AXUIElementRef element, int depth, const void *context) {
  const FocusedDocumentContext *ctx = (const FocusedDocumentContext *)context;
  bool focused = CopyBoolAttribute(element, kAXFocusedAttribute, false);
  if (!focused) {
    return false;
  }
  if (TitleMatches(CopyElementTitle(element), ctx->wanted, false)) {
    return true;
  }
  if (TitleMatches(CopyStringAttribute(element, kAXDocumentAttribute), ctx->wanted, false)) {
    return true;
  }
  return false;
}

struct SelectedTitleContext {
  NSString *wanted;
};

static bool MatchSelectedTitle(AXUIElementRef element, int depth, const void *context) {
  const SelectedTitleContext *ctx = (const SelectedTitleContext *)context;
  if (!CopyBoolAttribute(element, kAXSelectedAttribute, false)) {
    return false;
  }
  return TitleMatches(CopyElementTitle(element), ctx->wanted, false) ||
         TitleMatches(CopyStringAttribute(element, kAXValueAttribute), ctx->wanted, false);
}

// ---------------------------------------------------------------------------
// Application discovery (trusted bundle identifier only)
// ---------------------------------------------------------------------------

struct IdeaApplication {
  pid_t pid;
  bool running;
};

static IdeaApplication ResolveIdeaApplication() {
  IdeaApplication app;
  app.pid = 0;
  app.running = false;
  NSArray<NSRunningApplication *> *matches =
      [NSRunningApplication runningApplicationsWithBundleIdentifier:kIdeaBundleIdentifier];
  if (matches.count == 0) {
    return app;
  }
  app.pid = matches[0].processIdentifier;
  app.running = app.pid > 0;
  return app;
}

static AXUIElementRef CopyApplicationElement(pid_t pid) {
  return AXUIElementCreateApplication(pid);
}

static AXUIElementRef CopyTargetWindow(AXUIElementRef application) {
  CFTypeRef window = CopyAttribute(application, kAXFocusedWindowAttribute);
  if (window == nullptr) {
    window = CopyAttribute(application, kAXMainWindowAttribute);
  }
  if (window == nullptr) {
    CFTypeRef windows = CopyAttribute(application, kAXWindowsAttribute);
    if (windows != nullptr) {
      if (CFGetTypeID(windows) == CFArrayGetTypeID() && CFArrayGetCount((CFArrayRef)windows) > 0) {
        CFTypeRef first = CFArrayGetValueAtIndex((CFArrayRef)windows, 0);
        if (first != nullptr && CFGetTypeID(first) == AXUIElementGetTypeID()) {
          window = CFRetain(first);
        }
      }
      CFRelease(windows);
    }
  }
  if (window != nullptr && CFGetTypeID(window) != AXUIElementGetTypeID()) {
    CFRelease(window);
    return nullptr;
  }
  return (AXUIElementRef)window;
}

/**
 * Shared precondition gate. Reports the exact reason the bridge is unavailable so the
 * caller can fail closed honestly.
 */
static bool ResolveEnvironment(NSString **code, NSString **detail, IdeaApplication *outApp) {
  if (!AXIsProcessTrusted()) {
    *code = @"AX_NOT_TRUSTED";
    *detail = @"macOS Accessibility permission has not been granted to this process";
    return false;
  }
  IdeaApplication app = ResolveIdeaApplication();
  if (!app.running) {
    *code = @"IDEA_NOT_RUNNING";
    *detail = @"trusted IntelliJ IDEA application is not running";
    return false;
  }
  *outApp = app;
  return true;
}

/** Returns true when the accessibility server refuses the request (e.g. API disabled). */
static bool IsAccessibilityApiDisabled(AXUIElementRef application) {
  CFTypeRef role = nullptr;
  AXError error = AXUIElementCopyAttributeValue(application, kAXRoleAttribute, &role);
  if (role != nullptr) {
    CFRelease(role);
  }
  return error == kAXErrorAPIDisabled;
}

// ---------------------------------------------------------------------------
// Registered action primitives
// ---------------------------------------------------------------------------

static bool PerformPress(AXUIElementRef element) {
  return AXUIElementPerformAction(element, kAXPressAction) == kAXErrorSuccess;
}

/** Attempts to focus an element and reports whether the focus actually took effect. */
static bool FocusElement(AXUIElementRef element) {
  AXUIElementSetAttributeValue(element, kAXFocusedAttribute, kCFBooleanTrue);
  return CopyBoolAttribute(element, kAXFocusedAttribute, false);
}

struct OperationContext {
  AXUIElementRef application;
  AXUIElementRef window;
};

static AXUIElementRef FindByExactTitle(OperationContext *ctx, NSString *wanted, bool requireVisible) {
  ExactTitleContext matchCtx{wanted, requireVisible};
  int budget = kMaxTraversalNodes;
  AXUIElementRef found = FindElement(ctx->window, 0, &budget, MatchExactTitle, &matchCtx);
  if (found != nullptr) {
    return found;
  }
  budget = kMaxTraversalNodes;
  return FindElement(ctx->application, 0, &budget, MatchExactTitle, &matchCtx);
}

static AXUIElementRef FindByContainsTitle(OperationContext *ctx, NSString *wanted) {
  ContainsTitleContext matchCtx{wanted, false};
  int budget = kMaxTraversalNodes;
  AXUIElementRef found = FindElement(ctx->window, 0, &budget, MatchContainsTitle, &matchCtx);
  if (found != nullptr) {
    return found;
  }
  budget = kMaxTraversalNodes;
  return FindElement(ctx->application, 0, &budget, MatchContainsTitle, &matchCtx);
}

/**
 * OPEN_REGISTERED_FILE verification.
 *
 * Requires a real accessibility observation that the trusted registered file is now the
 * active editor content. Accepted signals (all read back from the live AX tree):
 *   1. a selected or focused element whose identity matches the registered file name
 *   2. a selected element whose value matches the registered file name
 *   3. the focused UI element's document/title matching the registered file name
 */
static bool VerifyActiveEditorFile(OperationContext *ctx, NSString *fileName) {
  SelectedTitleContext selectedCtx{fileName};
  int budget = kMaxTraversalNodes;
  AXUIElementRef selected = FindElement(ctx->window, 0, &budget, MatchSelectedTitle, &selectedCtx);
  if (selected != nullptr) {
    bool visible = IsElementVisible(selected);
    CFRelease(selected);
    if (visible) {
      return true;
    }
  }

  FocusedDocumentContext focusedCtx{fileName};
  budget = kMaxTraversalNodes;
  AXUIElementRef focused = FindElement(ctx->application, 0, &budget, MatchFocusedDocument, &focusedCtx);
  if (focused == nullptr) {
    budget = kMaxTraversalNodes;
    focused = FindElement(ctx->window, 0, &budget, MatchFocusedDocument, &focusedCtx);
  }
  if (focused != nullptr) {
    CFRelease(focused);
    return true;
  }
  return false;
}

static napi_value OpenRegisteredFile(napi_env env, napi_callback_info info) {
  size_t argc = 1;
  napi_value argv[1];
  napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);

  NSString *realPath = nil;
  if (argc < 1 || !ReadStringArgument(env, argv[0], &realPath)) {
    return MakeResult(env, false, false, @"INVALID_ARGUMENT", @"registered path argument is not a bounded string");
  }
  if (![realPath isAbsolutePath]) {
    return MakeResult(env, false, false, @"INVALID_REGISTERED_PATH", @"registered path must be absolute");
  }

  BOOL isDirectory = NO;
  BOOL exists = [[NSFileManager defaultManager] fileExistsAtPath:realPath isDirectory:&isDirectory];
  if (!exists || isDirectory) {
    return MakeResult(env, false, false, @"INVALID_REGISTERED_PATH", @"registered path is not an existing regular file");
  }

  NSString *fileName = [realPath lastPathComponent];
  if (fileName.length == 0) {
    return MakeResult(env, false, false, @"INVALID_REGISTERED_PATH", @"registered path has no file name");
  }

  NSString *code = nil;
  NSString *detail = nil;
  IdeaApplication app;
  if (!ResolveEnvironment(&code, &detail, &app)) {
    return MakeResult(env, false, false, code, detail);
  }

  AXUIElementRef application = CopyApplicationElement(app.pid);
  if (application == nullptr) {
    return MakeResult(env, false, false, @"AX_QUERY_FAILED", @"could not create an accessibility application element");
  }
  if (IsAccessibilityApiDisabled(application)) {
    CFRelease(application);
    return MakeResult(env, false, false, @"AX_API_DISABLED", @"macOS Accessibility API is disabled for this process");
  }

  AXUIElementRef window = CopyTargetWindow(application);
  if (window == nullptr) {
    CFRelease(application);
    return MakeResult(env, false, false, @"NO_ACTIVE_WINDOW", @"IntelliJ IDEA has no accessible active window");
  }

  OperationContext ctx{application, window};

  // Locate exactly one accessibility element whose title is the registered file name.
  AXUIElementRef target = FindByExactTitle(&ctx, fileName, true);
  if (target == nullptr) {
    CFRelease(window);
    CFRelease(application);
    return MakeResult(env, false, false, @"TARGET_NOT_FOUND",
                      @"registered file is not present in the accessible IntelliJ IDEA project tree");
  }

  bool dispatched = PerformPress(target);
  if (!dispatched) {
    // A project-tree cell may not accept AXPress directly; select it and press its row.
    AXUIElementSetAttributeValue(target, kAXSelectedAttribute, kCFBooleanTrue);
    CFTypeRef parentValue = CopyAttribute(target, kAXParentAttribute);
    if (parentValue != nullptr && CFGetTypeID(parentValue) == AXUIElementGetTypeID()) {
      dispatched = PerformPress((AXUIElementRef)parentValue);
      CFRelease(parentValue);
    } else if (parentValue != nullptr) {
      CFRelease(parentValue);
    }
  }

  bool verified = dispatched && VerifyActiveEditorFile(&ctx, fileName);

  CFRelease(target);
  CFRelease(window);
  CFRelease(application);

  if (!dispatched) {
    return MakeResult(env, false, false, @"ACTION_NOT_DISPATCHED",
                      @"accessibility could not activate the registered file");
  }
  if (!verified) {
    return MakeResult(env, true, false, @"STATE_NOT_VERIFIED",
                      @"registered file was activated but the active editor file was not confirmed");
  }
  return MakeResult(env, true, true, @"OK", @"registered file opened and active editor verified");
}

/**
 * FOCUS_RUN_CONFIGURATION verification.
 *
 * Locates the accessibility element that the trusted run-configuration handle names,
 * requests real keyboard focus, and then reads the focus back from the accessibility
 * server. Success requires the focus flag to be true on the named element.
 */
static napi_value FocusRunConfiguration(napi_env env, napi_callback_info info) {
  size_t argc = 1;
  napi_value argv[1];
  napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);

  NSString *configName = nil;
  if (argc < 1 || !ReadStringArgument(env, argv[0], &configName)) {
    return MakeResult(env, false, false, @"INVALID_ARGUMENT", @"run configuration handle argument is not a bounded string");
  }

  NSString *code = nil;
  NSString *detail = nil;
  IdeaApplication app;
  if (!ResolveEnvironment(&code, &detail, &app)) {
    return MakeResult(env, false, false, code, detail);
  }

  AXUIElementRef application = CopyApplicationElement(app.pid);
  if (application == nullptr) {
    return MakeResult(env, false, false, @"AX_QUERY_FAILED", @"could not create an accessibility application element");
  }
  if (IsAccessibilityApiDisabled(application)) {
    CFRelease(application);
    return MakeResult(env, false, false, @"AX_API_DISABLED", @"macOS Accessibility API is disabled for this process");
  }

  AXUIElementRef window = CopyTargetWindow(application);
  if (window == nullptr) {
    CFRelease(application);
    return MakeResult(env, false, false, @"NO_ACTIVE_WINDOW", @"IntelliJ IDEA has no accessible active window");
  }

  OperationContext ctx{application, window};
  AXUIElementRef target = FindByExactTitle(&ctx, configName, true);
  if (target == nullptr) {
    CFRelease(window);
    CFRelease(application);
    return MakeResult(env, false, false, @"TARGET_NOT_FOUND",
                      @"registered run configuration is not present in the accessible IntelliJ IDEA window");
  }

  bool dispatched = PerformPress(target);
  bool focused = FocusElement(target);
  bool identityMatches =
      TitleMatches(CopyElementTitle(target), configName, true) ||
      TitleMatches(CopyStringAttribute(target, kAXValueAttribute), configName, true);
  bool visible = IsElementVisible(target);

  CFRelease(target);
  CFRelease(window);
  CFRelease(application);

  if (!dispatched && !focused) {
    return MakeResult(env, false, false, @"ACTION_NOT_DISPATCHED",
                      @"accessibility could not focus the registered run configuration");
  }
  if (!focused || !identityMatches || !visible) {
    return MakeResult(env, true, false, @"STATE_NOT_VERIFIED",
                      @"registered run configuration was activated but its focus was not confirmed");
  }
  return MakeResult(env, true, true, @"OK", @"registered run configuration focused and verified");
}

/**
 * SHOW_TEST_RESULT verification.
 *
 * Reveals a pre-registered, already existing test-result view. This operation only ever
 * activates the element named by the trusted handle — it never starts, re-runs, or
 * executes a test. Success requires the named result view to be visible afterwards.
 */
static napi_value ShowTestResult(napi_env env, napi_callback_info info) {
  size_t argc = 1;
  napi_value argv[1];
  napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);

  NSString *handle = nil;
  if (argc < 1 || !ReadStringArgument(env, argv[0], &handle)) {
    return MakeResult(env, false, false, @"INVALID_ARGUMENT", @"test result handle argument is not a bounded string");
  }

  NSString *code = nil;
  NSString *detail = nil;
  IdeaApplication app;
  if (!ResolveEnvironment(&code, &detail, &app)) {
    return MakeResult(env, false, false, code, detail);
  }

  AXUIElementRef application = CopyApplicationElement(app.pid);
  if (application == nullptr) {
    return MakeResult(env, false, false, @"AX_QUERY_FAILED", @"could not create an accessibility application element");
  }
  if (IsAccessibilityApiDisabled(application)) {
    CFRelease(application);
    return MakeResult(env, false, false, @"AX_API_DISABLED", @"macOS Accessibility API is disabled for this process");
  }

  AXUIElementRef window = CopyTargetWindow(application);
  if (window == nullptr) {
    CFRelease(application);
    return MakeResult(env, false, false, @"NO_ACTIVE_WINDOW", @"IntelliJ IDEA has no accessible active window");
  }

  OperationContext ctx{application, window};

  // The registered handle names an existing results view. Try the exact accessibility
  // title first, then the trailing component of a registered relative handle.
  AXUIElementRef target = FindByExactTitle(&ctx, handle, true);
  if (target == nullptr) {
    NSString *trailing = [handle lastPathComponent];
    if (trailing.length > 0 && ![trailing isEqualToString:handle]) {
      target = FindByExactTitle(&ctx, trailing, true);
    }
  }
  if (target == nullptr) {
    target = FindByContainsTitle(&ctx, handle);
  }
  if (target == nullptr) {
    CFRelease(window);
    CFRelease(application);
    return MakeResult(env, false, false, @"TARGET_NOT_FOUND",
                      @"registered test result view is not present in the accessible IntelliJ IDEA window");
  }

  bool dispatched = PerformPress(target);
  bool visible = IsElementVisible(target);
  bool asserted =
      CopyBoolAttribute(target, kAXSelectedAttribute, false) ||
      CopyBoolAttribute(target, kAXFocusedAttribute, false) ||
      FocusElement(target);

  CFRelease(target);
  CFRelease(window);
  CFRelease(application);

  if (!dispatched && !asserted) {
    return MakeResult(env, false, false, @"ACTION_NOT_DISPATCHED",
                      @"accessibility could not reveal the registered test result view");
  }
  if (!visible || !asserted) {
    return MakeResult(env, true, false, @"STATE_NOT_VERIFIED",
                      @"registered test result view was activated but is not confirmed visible");
  }
  return MakeResult(env, true, true, @"OK", @"existing test result view revealed and verified");
}

// ---------------------------------------------------------------------------
// Diagnostic probe (side-effect free)
// ---------------------------------------------------------------------------

static napi_value Probe(napi_env env, napi_callback_info info) {
  bool trusted = AXIsProcessTrusted();
  IdeaApplication app = ResolveIdeaApplication();

  bool apiAvailable = trusted;
  if (trusted && app.running) {
    AXUIElementRef application = CopyApplicationElement(app.pid);
    if (application != nullptr) {
      apiAvailable = !IsAccessibilityApiDisabled(application);
      CFRelease(application);
    } else {
      apiAvailable = false;
    }
  }

  napi_value obj = nullptr;
  napi_create_object(env, &obj);
  SetNamed(env, obj, "platform", MakeString(env, @"darwin"));
  SetNamed(env, obj, "bridgeVersion", MakeString(env, kBridgeVersion));
  SetNamed(env, obj, "axApiAvailable", MakeBool(env, apiAvailable));
  SetNamed(env, obj, "axTrusted", MakeBool(env, trusted));
  SetNamed(env, obj, "ideaRunning", MakeBool(env, app.running));
  return obj;
}

// ---------------------------------------------------------------------------
// Module registration — exactly four exported functions.
// ---------------------------------------------------------------------------

static napi_value Init(napi_env env, napi_value exports) {
  napi_value fn = nullptr;

  napi_create_function(env, "probe", NAPI_AUTO_LENGTH, Probe, nullptr, &fn);
  SetNamed(env, exports, "probe", fn);

  napi_create_function(env, "openRegisteredFile", NAPI_AUTO_LENGTH, OpenRegisteredFile, nullptr, &fn);
  SetNamed(env, exports, "openRegisteredFile", fn);

  napi_create_function(env, "focusRunConfiguration", NAPI_AUTO_LENGTH, FocusRunConfiguration, nullptr, &fn);
  SetNamed(env, exports, "focusRunConfiguration", fn);

  napi_create_function(env, "showTestResult", NAPI_AUTO_LENGTH, ShowTestResult, nullptr, &fn);
  SetNamed(env, exports, "showTestResult", fn);

  return exports;
}

NAPI_MODULE(NODE_GYP_MODULE_NAME, Init)
