// StudyPilot Task 33 — IntelliJ IDEA Accessibility (AX) bridge.
//
// Real, narrowly typed, in-process native binding. It talks directly to the macOS
// Accessibility server through the ApplicationServices AX APIs. It never starts a child
// process, never uses a shell, never uses a script-automation bridge, never listens on a
// network socket, and never synthesises keyboard or mouse events.
//
// Version 3.0.0 is calibrated against a live IntelliJ IDEA 2026.1.1 accessibility tree
// (ide.support.screenreaders.enabled=true). Observed shape:
//   window  -> AXGroup desc='根窗格'
//   editor  -> AXTabGroup desc='<file>.<ext>'  + AXTextArea desc='<file>.<ext> 的编辑器'
//              (no AXDocument / AXURL in this build)
//   run     -> AXGroup desc='帧标题' -> ... -> AXGroup -> AXButton desc='<config>'
//              with sibling AXButton desc="运行 '<config>'" and "调试 '<config>'"
//              (no AXToolbar, no AXPopUpButton, no AXComboBox)
//   project -> AXOutline desc='项目结构树' with FLAT AXOutlineRow siblings whose nesting
//              is AXDisclosureLevel (0..N); file rows omit recognised source extensions
//              ("DeptController" for DeptController.java) and folder rows carry a
//              ", <type>" suffix ("java, 源根", "application.yml, Spring Boot")
//
// Identity binding rules (these make false success impossible):
//   * The traversal root is a real AXWindow of the trusted bundle identifier, never the
//     whole application, and there is no application-wide or system-wide search.
//   * Editor content subtrees are pruned, so the bounded snapshot reaches the project
//     outline instead of truncating on editor text.
//   * A target must be the UNIQUE structurally admissible element. Zero or two-or-more
//     candidates both fail closed.
//   * Every identity comparison is exact. Substring matching is forbidden. The only
//     permitted normalisation is dropping a recognised SOURCE extension from the
//     registered basename, and only when the reconstructed outline hierarchy proves the
//     full path.
//   * The project-tree path is reconstructed from AXDisclosureLevel + outline order and
//     matched component-by-component against the canonical registered path.
//   * Actuation uses a pre-action snapshot; verification re-locates the target in a
//     snapshot taken AFTER the action, so pre-existing state is never mistaken for the
//     effect of the action.

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
static const char *kBridgeVersion = "3.0.0";

static const int kMaxSnapshotNodes = 20000;
static const int kMaxSnapshotDepth = 40;
static const size_t kMaxArgumentBytes = 4096;
static const size_t kMaxFieldBytes = 512;
static const float kAxMessagingTimeoutSeconds = 2.0f;

static const char *kRoleWindow = "AXWindow";
static const char *kRoleOutline = "AXOutline";
static const char *kRoleRow = "AXRow";
static const char *kRoleButton = "AXButton";
static const char *kRoleGroup = "AXGroup";
static const char *kRoleTextArea = "AXTextArea";
static const char *kRoleTabGroup = "AXTabGroup";
static const char *kRoleTab = "AXTab";
static const char *kSubroleOutlineRow = "AXOutlineRow";

// IntelliJ's frame-title container. Locale/version specific: a different IDE locale fails
// closed rather than matching something else.
static const char *kFrameTitleGroupLabel = "帧标题";
// Localised Run/Debug sibling labels, tied to the exact configuration name.
static const char *kRunVerbPrefix = "运行 '";
static const char *kDebugVerbPrefix = "调试 '";

// Extensions IntelliJ omits in the project view. Nothing outside this list is ever
// dropped from the registered basename.
static const char *kOmittedSourceExtensions[] = {
    "java", "kt",   "kts", "groovy", "scala", "py",  "pyi", "ts",  "tsx", "js",
    "jsx",  "mjs",  "cjs", "c",      "cc",    "cpp", "cxx", "h",   "hpp", "cs",
    "go",   "rs",   "rb",  "php",    "swift", "m",   "mm",  "sql",
};

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

/** Optional string argument: absent, null or empty yields an empty string. */
static bool ReadOptionalStringArgument(napi_env env, size_t argc, napi_value *argv, size_t index,
                                       std::string *out) {
  out->clear();
  if (argc <= index) {
    return true;
  }
  napi_valuetype type = napi_undefined;
  if (napi_typeof(env, argv[index], &type) != napi_ok || type != napi_string) {
    return true;
  }
  std::string value;
  if (!ReadStringArgument(env, argv[index], &value)) {
    return true;
  }
  *out = value;
  return true;
}

// ---------------------------------------------------------------------------
// String helpers
// ---------------------------------------------------------------------------

static std::vector<std::string> SplitPathComponents(const std::string &path) {
  std::vector<std::string> parts;
  std::string current;
  for (size_t i = 0; i < path.size(); i++) {
    if (path[i] == '/') {
      if (!current.empty()) {
        parts.push_back(current);
        current.clear();
      }
    } else {
      current.push_back(path[i]);
    }
  }
  if (!current.empty()) {
    parts.push_back(current);
  }
  return parts;
}

static std::string BasenameOf(const std::string &path) {
  size_t slash = path.find_last_of('/');
  return slash == std::string::npos ? path : path.substr(slash + 1);
}

#ifdef AX_BRIDGE_TEST_SEAM
static std::string Trimmed(const std::string &value) {
  size_t start = value.find_first_not_of(" \t\r\n");
  if (start == std::string::npos) {
    return std::string();
  }
  size_t end = value.find_last_not_of(" \t\r\n");
  return value.substr(start, end - start + 1);
}
#endif

/** True when `prefix` is an ancestor of (or equal to) `candidate`, on component boundaries. */
static bool IsComponentPrefix(const std::string &prefix, const std::string &candidate) {
  if (prefix.empty() || candidate.empty()) {
    return false;
  }
  if (prefix == candidate) {
    return true;
  }
  if (candidate.size() <= prefix.size()) {
    return false;
  }
  if (candidate.compare(0, prefix.size(), prefix) != 0) {
    return false;
  }
  if (!prefix.empty() && prefix[prefix.size() - 1] == '/') {
    return true;
  }
  return candidate[prefix.size()] == '/';
}

static bool ExtensionOf(const std::string &name, std::string *out) {
  size_t dot = name.find_last_of('.');
  if (dot == std::string::npos || dot == 0 || dot + 1 >= name.size()) {
    return false;
  }
  *out = name.substr(dot + 1);
  return true;
}

static bool IsOmittedSourceExtension(const std::string &extension) {
  for (size_t i = 0; i < sizeof(kOmittedSourceExtensions) / sizeof(const char *); i++) {
    if (extension == kOmittedSourceExtensions[i]) {
      return true;
    }
  }
  return false;
}

/**
 * Exact basename match. `rowName` (the project-view display name) may equal the canonical
 * basename, or the canonical basename with a RECOGNISED SOURCE extension removed. No other
 * transformation is allowed, and no substring comparison is ever performed.
 */
static bool RowNameMatchesBasename(const std::string &rowName, const std::string &basename) {
  if (rowName.empty() || basename.empty()) {
    return false;
  }
  if (rowName == basename) {
    return true;
  }
  std::string extension;
  if (!ExtensionOf(basename, &extension) || !IsOmittedSourceExtension(extension)) {
    return false;
  }
  return rowName == basename.substr(0, basename.size() - extension.size() - 1);
}

/**
 * Whitespace-aware trimming. IntelliJ separates the level-0 module name from the embedded
 * project path with U+2009 THIN SPACE (observed live as "name\x20\xe2\x80\x89~/path, 模块"),
 * so ASCII-only trimming is not enough.
 */
static bool IsSpaceLikeAtEnd(const std::string &value, size_t *charStart) {
  if (value.empty()) {
    return false;
  }
  const size_t size = value.size();
  const unsigned char last = (unsigned char)value[size - 1];
  if (last == ' ' || last == '\t' || last == '\r' || last == '\n') {
    *charStart = size - 1;
    return true;
  }
  if (size >= 3) {
    const unsigned char b1 = (unsigned char)value[size - 3];
    const unsigned char b2 = (unsigned char)value[size - 2];
    if (b1 == 0xe2 && b2 == 0x80 && ((last >= 0x80 && last <= 0x8a) || last == 0xaf)) {
      *charStart = size - 3;
      return true;
    }
    if (b1 == 0xe3 && b2 == 0x80 && last == 0x80) {
      *charStart = size - 3;
      return true;
    }
    if (b1 == 0xe2 && b2 == 0x81 && last == 0x9f) {
      *charStart = size - 3;
      return true;
    }
  }
  if (size >= 2) {
    const unsigned char b1 = (unsigned char)value[size - 2];
    if (b1 == 0xc2 && (last == 0xa0 || last == 0x85)) {
      *charStart = size - 2;
      return true;
    }
  }
  return false;
}

static std::string StripSpaceLike(std::string value) {
  for (;;) {
    size_t start = 0;
    if (!IsSpaceLikeAtEnd(value, &start)) {
      break;
    }
    value.resize(start);
  }
  for (;;) {
    if (value.empty()) {
      break;
    }
    const unsigned char first = (unsigned char)value[0];
    if (first == ' ' || first == '\t' || first == '\r' || first == '\n') {
      value.erase(0, 1);
      continue;
    }
    if (value.size() >= 3) {
      const unsigned char b2 = (unsigned char)value[1];
      const unsigned char b3 = (unsigned char)value[2];
      if (first == 0xe2 && b2 == 0x80 && ((b3 >= 0x80 && b3 <= 0x8a) || b3 == 0xaf)) {
        value.erase(0, 3);
        continue;
      }
      if (first == 0xe3 && b2 == 0x80 && b3 == 0x80) {
        value.erase(0, 3);
        continue;
      }
      if (first == 0xe2 && b2 == 0x81 && b3 == 0x9f) {
        value.erase(0, 3);
        continue;
      }
    }
    if (value.size() >= 2 && first == 0xc2 &&
        ((unsigned char)value[1] == 0xa0 || (unsigned char)value[1] == 0x85)) {
      value.erase(0, 2);
      continue;
    }
    break;
  }
  return value;
}

/** The level-0 module row is "<name><space><path>, <type>"; this is the path start index. */
static size_t ModuleEmbeddedPathStart(const std::string &namePart) {
  for (size_t i = 1; i < namePart.size(); i++) {
    if (namePart[i] == '~' || namePart[i] == '/') {
      return i;
    }
  }
  return std::string::npos;
}

/** Strips the ", <type>" suffix IntelliJ appends to project-view row descriptions. */
static std::string RowDisplayName(const std::string &description) {
  size_t comma = description.find(", ");
  std::string name = comma == std::string::npos ? description : description.substr(0, comma);
  return StripSpaceLike(name);
}

/**
 * Level-0 rows look like "<module> <thin-space> <path>, <type>". Extracts the embedded
 * filesystem path when it is unambiguous, otherwise returns an empty string.
 */
static std::string ExtractModuleRootPath(const std::string &description) {
  size_t comma = description.find(", ");
  std::string namePart = comma == std::string::npos ? description : description.substr(0, comma);
  size_t pathStart = ModuleEmbeddedPathStart(namePart);
  if (pathStart == std::string::npos) {
    return std::string();
  }
  std::string tail = StripSpaceLike(namePart.substr(pathStart));
  if (tail.empty()) {
    return std::string();
  }
  if (tail[0] == '/') {
    return tail;
  }
  if (tail[0] == '~') {
    const char *home = getenv("HOME");
    std::string resolved = home == nullptr ? std::string() : std::string(home);
    if (resolved.empty()) {
      return std::string();
    }
    std::string rest = tail.substr(1);
    return rest.empty() ? resolved : resolved + rest;
  }
  return std::string();
}

/** The module row's display name is the part before the embedded path, if any. */
static std::string ModuleDisplayName(const std::string &description) {
  size_t comma = description.find(", ");
  std::string namePart = comma == std::string::npos ? description : description.substr(0, comma);
  size_t pathStart = ModuleEmbeddedPathStart(namePart);
  return StripSpaceLike(pathStart == std::string::npos ? namePart : namePart.substr(0, pathStart));
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
  std::string description;
  std::string title;
  std::string value;
  bool selected = false;
  bool focused = false;
  bool hidden = false;
  bool hasSize = false;
  double width = 0.0;
  double height = 0.0;
  bool hasDisclosureLevel = false;
  int disclosureLevel = 0;
};

enum AttributeIndex {
  kIdxRole = 0,
  kIdxSubrole,
  kIdxIdentifier,
  kIdxDescription,
  kIdxTitle,
  kIdxValue,
  kIdxSelected,
  kIdxFocused,
  kIdxHidden,
  kIdxSize,
  kIdxDisclosureLevel,
  kAttributeCount
};

/** Reads every needed attribute in a single IPC round trip; long values are bounded. */
static bool ReadAttributes(AXUIElementRef element, RawAttributes *out) {
  CFStringRef names[kAttributeCount] = {
      kAXRoleAttribute,    kAXSubroleAttribute,    kAXIdentifierAttribute,
      kAXDescriptionAttribute, kAXTitleAttribute,  kAXValueAttribute,
      kAXSelectedAttribute,    kAXFocusedAttribute, kAXHiddenAttribute,
      kAXSizeAttribute,        CFSTR("AXDisclosureLevel"),
  };
  CFArrayRef attributeArray =
      CFArrayCreate(nullptr, (const void **)names, kAttributeCount, &kCFTypeArrayCallBacks);
  if (attributeArray == nullptr) {
    return false;
  }

  CFArrayRef values = nullptr;
  // Options MUST be 0. The only option defined by the SDK is
  // kAXCopyMultipleAttributeOptionStopOnError (0x1), which aborts the whole call on the
  // first unsupported attribute. With 0 the array simply carries per-position errors /
  // CFNull, which is exactly what this reader wants.
  AXError error = AXUIElementCopyMultipleAttributeValues(
      element, attributeArray, (AXCopyMultipleAttributeOptions)0, &values);
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
  out->description = readString(kIdxDescription);
  out->title = readString(kIdxTitle);
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

  CFTypeRef levelValue = CFArrayGetValueAtIndex(values, kIdxDisclosureLevel);
  if (levelValue != nullptr && CFGetTypeID(levelValue) == CFNumberGetTypeID()) {
    int level = 0;
    if (CFNumberGetValue((CFNumberRef)levelValue, kCFNumberIntType, &level)) {
      out->hasDisclosureLevel = true;
      out->disclosureLevel = level;
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
  std::string description;
  std::string title;
  std::string value;
  bool selected = false;
  bool focused = false;
  bool hidden = false;
  bool hasSize = false;
  double width = 0.0;
  double height = 0.0;
  bool hasDisclosureLevel = false;
  int disclosureLevel = 0;
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

  /** Children in live accessibility order (the snapshot itself is built depth-first). */
  std::vector<AxNode> ChildrenInLiveOrder(AxNode node) const {
    std::vector<AxNode> out;
    const AxNodeData *data = At(node);
    if (data == nullptr) {
      return out;
    }
    out = data->children;
    std::sort(out.begin(), out.end(), [this](AxNode a, AxNode b) {
      const AxNodeData *left = At(a);
      const AxNodeData *right = At(b);
      int leftIndex = left == nullptr ? -1 : left->liveChildIndex;
      int rightIndex = right == nullptr ? -1 : right->liveChildIndex;
      return leftIndex < rightIndex;
    });
    return out;
  }
};

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

/** Identity string of an element: accessibility description first, then title, then value. */
static std::string IdentityOf(const AxNodeData &data) {
  if (!data.description.empty()) {
    return data.description;
  }
  if (!data.title.empty()) {
    return data.title;
  }
  return data.value;
}

static std::string IdentityOf(const RawAttributes &data) {
  if (!data.description.empty()) {
    return data.description;
  }
  if (!data.title.empty()) {
    return data.title;
  }
  return data.value;
}

static bool HasAncestorRole(const AxSnapshot &snapshot, AxNode node, const char *role) {
  const AxNodeData *current = snapshot.At(node);
  while (current != nullptr && current->parent != kNoNode) {
    const AxNodeData *parent = snapshot.At(current->parent);
    if (parent == nullptr) {
      return false;
    }
    if (parent->role == role) {
      return true;
    }
    current = parent;
  }
  return false;
}

// ---------------------------------------------------------------------------
// Project-view outline: path reconstruction from disclosure levels and outline order.
// ---------------------------------------------------------------------------

struct OutlineRow {
  AxNode node = kNoNode;
  int level = -1;
  std::string description;
};

/** Direct AXOutlineRow children of the row's outline parent, in live order. */
static bool CollectOutlineRows(const AxSnapshot &snapshot, AxNode row, std::vector<OutlineRow> *out) {
  const AxNodeData *rowData = snapshot.At(row);
  if (rowData == nullptr || rowData->parent == kNoNode) {
    return false;
  }
  const AxNodeData *outline = snapshot.At(rowData->parent);
  if (outline == nullptr || outline->role != kRoleOutline) {
    return false;
  }
  for (AxNode child : snapshot.ChildrenInLiveOrder(rowData->parent)) {
    const AxNodeData *childData = snapshot.At(child);
    if (childData == nullptr || childData->role != kRoleRow ||
        childData->subrole != kSubroleOutlineRow) {
      continue;
    }
    OutlineRow entry;
    entry.node = child;
    entry.level = childData->hasDisclosureLevel ? childData->disclosureLevel : -1;
    entry.description = childData->description.empty() ? childData->title : childData->description;
    out->push_back(entry);
  }
  return !out->empty();
}

/**
 * Builds the outline chain for `row`: the ordered list of rows from the level-0 ancestor
 * down to `row`, following AXDisclosureLevel and outline order. Any level jump that cannot
 * be resolved is a hard failure (no chain).
 */
static bool BuildOutlineChain(
    const AxSnapshot &snapshot,
    AxNode row,
    std::vector<OutlineRow> *chain
) {
  std::vector<OutlineRow> rows;
  if (!CollectOutlineRows(snapshot, row, &rows)) {
    return false;
  }

  int index = -1;
  for (size_t i = 0; i < rows.size(); i++) {
    if (rows[i].node == row) {
      index = (int)i;
      break;
    }
  }
  if (index < 0) {
    return false;
  }

  std::vector<OutlineRow> reversed;
  int current = index;
  int guard = 0;
  while (current >= 0 && guard++ <= (int)rows.size()) {
    reversed.push_back(rows[(size_t)current]);
    int level = rows[(size_t)current].level;
    if (level < 0) {
      return false;
    }
    if (level == 0) {
      break;
    }
    int parent = -1;
    for (int candidate = current - 1; candidate >= 0; candidate--) {
      int candidateLevel = rows[(size_t)candidate].level;
      if (candidateLevel == level - 1) {
        parent = candidate;
        break;
      }
      if (candidateLevel >= level) {
        continue;
      }
      // A shallower but not exactly one level up row cannot be an ancestor.
      return false;
    }
    if (parent < 0) {
      return false;
    }
    current = parent;
  }
  if (reversed.empty() || reversed.back().level != 0) {
    return false;
  }
  std::reverse(reversed.begin(), reversed.end());
  *chain = reversed;
  return true;
}

/**
 * Proves that the outline chain reconstructs exactly the canonical registered path:
 * every chain component must equal the corresponding canonical path component, working
 * upwards from the file row; the level-0 row may instead prove itself through the
 * absolute path IntelliJ embeds in its description.
 *
 * When `workspaceRoot` is supplied it must contain (or equal) the level-0 row's root path,
 * which additionally ties the outline to the registered trusted workspace.
 */
/** Display name of an outline row: level-0 module rows carry an embedded path. */
static std::string OutlineRowDisplayName(const OutlineRow &row) {
  return row.level == 0 ? ModuleDisplayName(row.description) : RowDisplayName(row.description);
}

/**
 * Counts the ways the outline chain can be aligned onto the canonical path components.
 *
 * IntelliJ compacts single-child package chains into one row, so one row may stand for
 * several consecutive path components ("com.itmoxiao" for "com/itmoxiao"). Every row must
 * consume at least one component and the dot-join of the consumed components must equal the
 * row's display name exactly. The result is capped at 2 so the caller can reject ambiguity.
 */
static int CountAlignments(
    const std::vector<OutlineRow> &chain,
    const std::vector<std::string> &parts,
    int rowIndex,
    int partIndex
) {
  if (rowIndex < 0) {
    // All rows consumed: the leading canonical components above the outline root may remain.
    return 1;
  }
  if (partIndex < 0) {
    return 0;
  }

  const std::string displayName = OutlineRowDisplayName(chain[(size_t)rowIndex]);
  if (displayName.empty()) {
    return 0;
  }

  if (rowIndex == (int)chain.size() - 1) {
    // The leaf row must be exactly the registered basename.
    if (!RowNameMatchesBasename(displayName, parts[(size_t)partIndex])) {
      return 0;
    }
    return CountAlignments(chain, parts, rowIndex - 1, partIndex - 1);
  }

  int total = 0;
  for (int consumed = 1; consumed <= partIndex + 1; consumed++) {
    std::string joined;
    for (int k = partIndex - consumed + 1; k <= partIndex; k++) {
      if (!joined.empty()) {
        joined += ".";
      }
      joined += parts[(size_t)k];
    }
    if (joined != displayName) {
      continue;
    }
    total += CountAlignments(chain, parts, rowIndex - 1, partIndex - consumed);
    if (total >= 2) {
      return 2;
    }
  }
  return total;
}

/**
 * Proves that the outline chain reconstructs exactly the canonical registered path.
 *
 * The leaf row must be exactly the registered basename (a recognised source extension may
 * be omitted by the IDE). Every other row must align, component by component, with part of
 * the canonical directory path. Exactly ONE alignment must exist: ambiguity fails closed.
 * The level-0 row may alternatively anchor through the absolute path IntelliJ embeds in
 * its description, which additionally has to sit inside the trusted workspace root.
 */
static bool ProveOutlinePath(
    const std::vector<OutlineRow> &chain,
    const std::string &canonicalPath,
    const std::string &workspaceRoot
) {
  if (chain.size() < 2) {
    return false;
  }
  std::vector<std::string> parts = SplitPathComponents(canonicalPath);
  if (parts.size() < chain.size()) {
    return false;
  }

  const OutlineRow &leaf = chain.back();
  const std::string leafName = OutlineRowDisplayName(leaf);
  if (!RowNameMatchesBasename(leafName, parts.back())) {
    return false;
  }

  // Exactly one complete alignment of every row onto the canonical path is required:
  // zero alignments is "not proven" and two or more is ambiguous. Both fail closed.
  int alignments = CountAlignments(chain, parts, (int)chain.size() - 1, (int)parts.size() - 1);
  if (alignments == 1) {
    return true;
  }
  if (alignments > 1) {
    return false;
  }

  // The level-0 row did not align by name. Accept it only when IntelliJ embedded the real
  // module root path and that path is an exact component-boundary prefix of the registered
  // canonical path inside the registered workspace.
  const OutlineRow &top = chain.front();
  std::string rootPath = ExtractModuleRootPath(top.description);
  if (rootPath.empty() || !IsComponentPrefix(rootPath, canonicalPath)) {
    return false;
  }
  // Re-run the alignment with the top row excluded from name matching.
  std::vector<OutlineRow> rest(chain.begin() + 1, chain.end());
  if (rest.size() < 2) {
    return false;
  }
  int restAlignments =
      CountAlignments(rest, parts, (int)rest.size() - 1, (int)parts.size() - 1);
  if (restAlignments != 1) {
    return false;
  }
  if (!workspaceRoot.empty()) {
    std::string normalisedRoot = workspaceRoot;
    while (normalisedRoot.size() > 1 && normalisedRoot[normalisedRoot.size() - 1] == '/') {
      normalisedRoot.resize(normalisedRoot.size() - 1);
    }
    if (!IsComponentPrefix(normalisedRoot, rootPath) && normalisedRoot != rootPath) {
      return false;
    }
  }
  return true;
}

// ---------------------------------------------------------------------------
// Generic unique-target location
// ---------------------------------------------------------------------------

enum class LocateStatus { kFound, kNotFound, kAmbiguous, kTruncated };

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

/** Locates the unique project-view row that proves the canonical registered path. */
static LocateStatus LocateRegisteredFileRow(
    const AxSnapshot &snapshot,
    const std::string &canonicalPath,
    const std::string &workspaceRoot,
    AxNode *out
) {
  if (snapshot.nodes.empty() || snapshot.Truncated()) {
    return LocateStatus::kTruncated;
  }
  AxNode found = kNoNode;
  int matches = 0;
  for (int i = 1; i <= (int)snapshot.nodes.size(); i++) {
    AxNode node = (AxNode)i;
    const AxNodeData *data = snapshot.At(node);
    if (data == nullptr || data->role != kRoleRow || data->subrole != kSubroleOutlineRow) {
      continue;
    }
    if (!IsVisible(snapshot, node)) {
      continue;
    }
    std::vector<OutlineRow> chain;
    if (!BuildOutlineChain(snapshot, node, &chain)) {
      continue;
    }
    if (!ProveOutlinePath(chain, canonicalPath, workspaceRoot)) {
      continue;
    }
    if (matches == 0) {
      found = node;
    }
    matches++;
    if (matches > 1) {
      return LocateStatus::kAmbiguous;
    }
  }
  if (matches == 0) {
    return LocateStatus::kNotFound;
  }
  *out = found;
  return LocateStatus::kFound;
}

// ---------------------------------------------------------------------------
// Operations
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
          usable = [(__bridge NSString *)role
              isEqualToString:[NSString stringWithUTF8String:kRoleWindow]];
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

/**
 * Builds a bounded snapshot of the trusted window. Editor content subtrees are pruned:
 * their contents are never needed and would otherwise exhaust the node budget before the
 * project outline is reached.
 */
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
      data->description = attributes.description;
      data->title = attributes.title;
      data->value = attributes.value;
      data->selected = attributes.selected;
      data->focused = attributes.focused;
      data->hidden = attributes.hidden;
      data->hasSize = attributes.hasSize;
      data->width = attributes.width;
      data->height = attributes.height;
      data->hasDisclosureLevel = attributes.hasDisclosureLevel;
      data->disclosureLevel = attributes.disclosureLevel;
      data->parent = current.parent;
      data->liveChildIndex = current.liveChildIndex;
      if (current.parent != kNoNode) {
        out->nodes[current.parent - 1].children.push_back(node);
      }
      if (out->root == kNoNode) {
        out->root = node;
      }
    }

    const bool pruneContents = attributes.role == kRoleTextArea;
    if (node != kNoNode && !pruneContents && current.depth < kMaxSnapshotDepth) {
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

/**
 * Recovers the live accessibility element for a snapshot node by re-walking the recorded
 * live child indices, then re-checks role and identity immediately before dispatch.
 */
static AXUIElementRef CopyVerifiedElement(
    AXUIElementRef window,
    const AxSnapshot &snapshot,
    AxNode node,
    const std::string &expectedRole,
    const std::string &expectedIdentity
) {
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

  RawAttributes live;
  if (!ReadAttributes(element, &live) || live.hidden || live.role != expectedRole) {
    CFRelease(element);
    return nullptr;
  }
  if (!expectedIdentity.empty() && IdentityOf(live) != expectedIdentity) {
    CFRelease(element);
    return nullptr;
  }
  return element;
}

struct OperationOutcome {
  bool ok = false;
  bool verified = false;
  const char *code = "INTERNAL_ERROR";
  const char *detail = "unexpected bridge state";
};

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

/** OPEN_REGISTERED_FILE — open the uniquely proven registered file row. */
static napi_value OpenRegisteredFile(napi_env env, napi_callback_info info) {
  size_t argc = 2;
  napi_value argv[2];
  napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);

  std::string canonicalPath;
  std::string workspaceRoot;
  if (argc < 1 || !ReadStringArgument(env, argv[0], &canonicalPath)) {
    return MakeResult(env, false, false, "INVALID_ARGUMENT",
                      "registered path argument is not a bounded string");
  }
  ReadOptionalStringArgument(env, argc, argv, 1, &workspaceRoot);

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

  AxSnapshot pre;
  BuildSnapshot(window, &pre);

  AxNode target = kNoNode;
  LocateStatus located = LocateRegisteredFileRow(pre, canonicalPath, workspaceRoot, &target);
  if (located != LocateStatus::kFound) {
    CFRelease(window);
    CFRelease(application);
    return MakeResult(env, false, false, LocateStatusCode(located),
                      located == LocateStatus::kAmbiguous
                          ? "more than one project-view row proves the registered canonical path"
                          : "no project-view row proves the registered canonical path");
  }

  const AxNodeData *targetData = pre.At(target);
  std::string identity = targetData == nullptr ? std::string() : IdentityOf(*targetData);
  AXUIElementRef targetElement =
      CopyVerifiedElement(window, pre, target, kRoleRow, identity);
  if (targetElement == nullptr) {
    CFRelease(window);
    CFRelease(application);
    return MakeResult(env, false, false, "IDENTITY_CHANGED_BEFORE_ACTION",
                      "the project-view row identity no longer holds on the live element");
  }
  bool dispatched = PerformPress(targetElement);
  CFRelease(targetElement);

  if (!dispatched) {
    CFRelease(window);
    CFRelease(application);
    return MakeResult(env, false, false, "ACTION_NOT_DISPATCHED",
                      "accessibility could not activate the registered project-view row");
  }

  AxSnapshot post;
  BuildSnapshot(window, &post);

  // Post-action observation 1: the same row is still the unique proof and is selected.
  AxNode sameRow = kNoNode;
  bool rowStillUnique =
      LocateRegisteredFileRow(post, canonicalPath, workspaceRoot, &sameRow) == LocateStatus::kFound;
  const AxNodeData *rowData = post.At(sameRow);
  bool rowSelected = rowData != nullptr && rowData->selected;

  // Post-action observation 2: the active editor identifies the exact registered file.
  std::string basename = BasenameOf(canonicalPath);
  std::string displayName = basename;
  std::string extension;
  if (ExtensionOf(basename, &extension) && IsOmittedSourceExtension(extension)) {
    displayName = basename.substr(0, basename.size() - extension.size() - 1);
  }
  bool editorIdentified = false;
  for (int i = 1; i <= (int)post.nodes.size(); i++) {
    const AxNodeData *data = post.At((AxNode)i);
    if (data == nullptr || data->hidden) {
      continue;
    }
    if (data->role != kRoleTabGroup && data->role != kRoleTextArea) {
      continue;
    }
    std::string identity = IdentityOf(*data);
    if (identity == basename || identity == displayName) {
      editorIdentified = true;
      break;
    }
  }

  CFRelease(window);
  CFRelease(application);

  if (!editorIdentified || !rowStillUnique || !rowSelected) {
    return MakeResult(env, true, false, "STATE_NOT_VERIFIED",
                      "registered file row was activated but the active editor view was not confirmed");
  }
  return MakeResult(env, true, true, "OK",
                    "registered file opened and the active editor was verified after the action");
}

/** FOCUS_RUN_CONFIGURATION — focus only the uniquely proven run-configuration button. */
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

  const std::string runLabel = std::string(kRunVerbPrefix) + handle + "'";
  const std::string debugLabel = std::string(kDebugVerbPrefix) + handle + "'";

  AxSnapshot pre;
  BuildSnapshot(window, &pre);

  // The configuration button is the unique AXButton named exactly like the registered
  // configuration, inside the frame-title container, with the exact Run/Debug siblings
  // bound to the same name in the same parent.
  AxNode target = kNoNode;
  int matches = 0;
  for (int i = 1; i <= (int)pre.nodes.size(); i++) {
    AxNode node = (AxNode)i;
    const AxNodeData *data = pre.At(node);
    if (data == nullptr || data->role != kRoleButton || IdentityOf(*data) != handle) {
      continue;
    }
    if (!IsVisible(pre, node) || !HasAncestorRole(pre, node, kRoleGroup)) {
      continue;
    }
    bool inFrameTitle = false;
    const AxNodeData *cursor = pre.At(node);
    while (cursor != nullptr && cursor->parent != kNoNode) {
      const AxNodeData *parent = pre.At(cursor->parent);
      if (parent == nullptr) {
        break;
      }
      if (parent->role == kRoleGroup && parent->description == kFrameTitleGroupLabel) {
        inFrameTitle = true;
        break;
      }
      cursor = parent;
    }
    if (!inFrameTitle) {
      continue;
    }
    bool hasRunSibling = false;
    bool hasDebugSibling = false;
    for (AxNode sibling : pre.ChildrenInLiveOrder(data->parent)) {
      const AxNodeData *siblingData = pre.At(sibling);
      if (siblingData == nullptr || siblingData->role != kRoleButton) {
        continue;
      }
      if (siblingData->description == runLabel) {
        hasRunSibling = true;
      }
      if (siblingData->description == debugLabel) {
        hasDebugSibling = true;
      }
    }
    if (!hasRunSibling || !hasDebugSibling) {
      continue;
    }
    if (matches == 0) {
      target = node;
    }
    matches++;
    if (matches > 1) {
      break;
    }
  }

  if (matches == 0) {
    CFRelease(window);
    CFRelease(application);
    return MakeResult(env, false, false, "SELECTOR_NOT_IDENTIFIED",
                      "no run-configuration control with the exact registered identity and Run/Debug siblings was found");
  }
  if (matches > 1) {
    CFRelease(window);
    CFRelease(application);
    return MakeResult(env, false, false, "TARGET_AMBIGUOUS",
                      "more than one run-configuration control matched the registered identity");
  }

  const AxNodeData *targetData = pre.At(target);
  std::string identity = targetData == nullptr ? std::string() : IdentityOf(*targetData);
  AXUIElementRef targetElement = CopyVerifiedElement(window, pre, target, kRoleButton, identity);
  if (targetElement == nullptr) {
    CFRelease(window);
    CFRelease(application);
    return MakeResult(env, false, false, "IDENTITY_CHANGED_BEFORE_ACTION",
                      "the run-configuration control identity no longer holds on the live element");
  }
  // The registered action is FOCUS only: the selector is never pressed, so no menu opens
  // and no configuration is changed.
  bool dispatched =
      AXUIElementSetAttributeValue(targetElement, kAXFocusedAttribute, kCFBooleanTrue) ==
      kAXErrorSuccess;
  CFRelease(targetElement);

  if (!dispatched) {
    CFRelease(window);
    CFRelease(application);
    return MakeResult(env, false, false, "ACTION_NOT_DISPATCHED",
                      "accessibility could not focus the run-configuration control");
  }

  AxSnapshot post;
  BuildSnapshot(window, &post);

  bool verified = false;
  int postMatches = 0;
  for (int i = 1; i <= (int)post.nodes.size(); i++) {
    const AxNodeData *data = post.At((AxNode)i);
    if (data == nullptr || data->role != kRoleButton || IdentityOf(*data) != handle) {
      continue;
    }
    if (!data->focused && !data->selected) {
      continue;
    }
    postMatches++;
  }
  verified = postMatches == 1;

  CFRelease(window);
  CFRelease(application);

  if (!verified) {
    return MakeResult(env, true, false, "STATE_NOT_VERIFIED",
                      "the run-configuration control did not report the registered configuration as focused after the action");
  }
  return MakeResult(env, true, true, "OK",
                    "registered run configuration focused and verified on its control");
}

/**
 * SHOW_TEST_RESULT — reveal an existing results view.
 *
 * Calibration note: the live IntelliJ 2026.1.1 tree exposes no test-result tool window,
 * so the admissible role is deliberately narrow (a real tool-window tab) and positive
 * acceptance remains blocked until one exists. Editor tab roles are never
 * admissible, and there is no application-wide or contains-title fallback.
 */
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

  AxSnapshot pre;
  BuildSnapshot(window, &pre);

  AxNode target = kNoNode;
  int matches = 0;
  for (int i = 1; i <= (int)pre.nodes.size(); i++) {
    AxNode node = (AxNode)i;
    const AxNodeData *data = pre.At(node);
    if (data == nullptr || data->role != kRoleTab) {
      continue;
    }
    if (IdentityOf(*data) != handle || !IsVisible(pre, node)) {
      continue;
    }
    if (!HasAncestorRole(pre, node, kRoleTabGroup)) {
      continue;
    }
    if (matches == 0) {
      target = node;
    }
    matches++;
    if (matches > 1) {
      break;
    }
  }

  if (matches == 0) {
    CFRelease(window);
    CFRelease(application);
    return MakeResult(env, false, false, "RESULT_VIEW_NOT_IDENTIFIED",
                      "no tool-window result view with the exact registered identity is present");
  }
  if (matches > 1) {
    CFRelease(window);
    CFRelease(application);
    return MakeResult(env, false, false, "TARGET_AMBIGUOUS",
                      "more than one result view matched the registered identity");
  }

  const AxNodeData *targetData = pre.At(target);
  std::string identity = targetData == nullptr ? std::string() : IdentityOf(*targetData);
  AXUIElementRef targetElement = CopyVerifiedElement(window, pre, target, kRoleTab, identity);
  if (targetElement == nullptr) {
    CFRelease(window);
    CFRelease(application);
    return MakeResult(env, false, false, "IDENTITY_CHANGED_BEFORE_ACTION",
                      "the result view identity no longer holds on the live element");
  }
  bool dispatched = PerformPress(targetElement);
  CFRelease(targetElement);

  if (!dispatched) {
    CFRelease(window);
    CFRelease(application);
    return MakeResult(env, false, false, "ACTION_NOT_DISPATCHED",
                      "accessibility could not activate the registered result view");
  }

  AxSnapshot post;
  BuildSnapshot(window, &post);

  bool verified = false;
  int postMatches = 0;
  for (int i = 1; i <= (int)post.nodes.size(); i++) {
    const AxNodeData *data = post.At((AxNode)i);
    if (data == nullptr || data->role != kRoleTab || IdentityOf(*data) != handle) {
      continue;
    }
    if (!data->selected || !IsVisible(post, (AxNode)i)) {
      continue;
    }
    postMatches++;
  }
  verified = postMatches == 1;

  CFRelease(window);
  CFRelease(application);

  if (!verified) {
    return MakeResult(env, true, false, "STATE_NOT_VERIFIED",
                      "the registered result view is not the selected visible view after the action");
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
 * Fixture format, one node per line, depth-first:
 *   depth|role|subrole|identifier|description|title|value|flags|size|level
 * flags: "s" selected, "f" focused, "h" hidden. size: "WxH" or empty. level: integer or
 * empty. depth 0 is the traversal root (the window).
 */
static bool ParseFixture(const std::string &text, AxSnapshot *out) {
  std::vector<AxNode> stack;
  for (const std::string &rawLine : Split(text, '\n')) {
    std::string line = Trimmed(rawLine);
    if (line.empty() || line[0] == '#') {
      continue;
    }
    std::vector<std::string> fields = Split(line, '|');
    if (fields.size() < 10) {
      return false;
    }
    int depth = atoi(Trimmed(fields[0]).c_str());
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
    data->role = Trimmed(fields[1]);
    data->subrole = Trimmed(fields[2]);
    data->identifier = Trimmed(fields[3]);
    data->description = Trimmed(fields[4]);
    data->title = Trimmed(fields[5]);
    data->value = Trimmed(fields[6]);
    std::string flags = Trimmed(fields[7]);
    data->selected = flags.find('s') != std::string::npos;
    data->focused = flags.find('f') != std::string::npos;
    data->hidden = flags.find('h') != std::string::npos;
    std::string size = Trimmed(fields[8]);
    if (!size.empty()) {
      size_t x = size.find('x');
      if (x != std::string::npos) {
        data->hasSize = true;
        data->width = atof(size.substr(0, x).c_str());
        data->height = atof(size.substr(x + 1).c_str());
      }
    }
    std::string level = Trimmed(fields[9]);
    if (!level.empty()) {
      data->hasDisclosureLevel = true;
      data->disclosureLevel = atoi(level.c_str());
    }
    data->parent = parent;
    // Fixture lines are already in live order, so the live child index is the position.
    data->liveChildIndex = parent == kNoNode ? -1 : 0;
    if (parent != kNoNode) {
      AxNodeData *parentData = &out->nodes[parent - 1];
      data->liveChildIndex = (int)parentData->children.size();
      parentData->children.push_back(node);
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
  size_t argc = 5;
  napi_value argv[5];
  napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);

  std::string operation;
  std::string argument;
  std::string preText;
  std::string postText;
  std::string workspaceRoot;
  if (argc < 4 || !ReadStringArgument(env, argv[0], &operation) ||
      !ReadStringArgument(env, argv[1], &argument) || !ReadStringArgument(env, argv[2], &preText) ||
      !ReadStringArgument(env, argv[3], &postText)) {
    return MakeResult(env, false, false, "INVALID_ARGUMENT", "invalid test seam arguments");
  }
  ReadOptionalStringArgument(env, argc, argv, 4, &workspaceRoot);

  AxSnapshot pre;
  AxSnapshot post;
  if (!ParseFixture(preText, &pre) || !ParseFixture(postText, &post)) {
    return MakeResult(env, false, false, "INVALID_FIXTURE", "fixture could not be parsed");
  }

  if (operation == "OPEN_REGISTERED_FILE") {
    AxNode target = kNoNode;
    LocateStatus located = LocateRegisteredFileRow(pre, argument, workspaceRoot, &target);
    if (located != LocateStatus::kFound) {
      return MakeResult(env, false, false, LocateStatusCode(located),
                        "actuation target was not uniquely proven on the pre-action snapshot");
    }
    AxNode sameRow = kNoNode;
    bool rowStillUnique =
        LocateRegisteredFileRow(post, argument, workspaceRoot, &sameRow) == LocateStatus::kFound;
    const AxNodeData *rowData = post.At(sameRow);
    bool rowSelected = rowData != nullptr && rowData->selected;

    std::string basename = BasenameOf(argument);
    std::string displayName = basename;
    std::string extension;
    if (ExtensionOf(basename, &extension) && IsOmittedSourceExtension(extension)) {
      displayName = basename.substr(0, basename.size() - extension.size() - 1);
    }
    bool editorIdentified = false;
    for (int i = 1; i <= (int)post.nodes.size(); i++) {
      const AxNodeData *data = post.At((AxNode)i);
      if (data == nullptr || data->hidden) continue;
      if (data->role != kRoleTabGroup && data->role != kRoleTextArea) continue;
      std::string identity = IdentityOf(*data);
      if (identity == basename || identity == displayName) {
        editorIdentified = true;
        break;
      }
    }
    if (!editorIdentified || !rowStillUnique || !rowSelected) {
      return MakeResult(env, true, false, "STATE_NOT_VERIFIED",
                        "the required accessibility state was not observed after the action");
    }
    return MakeResult(env, true, true, "OK", "state verified after the action");
  }

  if (operation == "FOCUS_RUN_CONFIGURATION") {
    const std::string runLabel = std::string(kRunVerbPrefix) + argument + "'";
    const std::string debugLabel = std::string(kDebugVerbPrefix) + argument + "'";
    int matches = 0;
    for (int i = 1; i <= (int)pre.nodes.size(); i++) {
      const AxNodeData *data = pre.At((AxNode)i);
      if (data == nullptr || data->role != kRoleButton || IdentityOf(*data) != argument) continue;
      if (!IsVisible(pre, (AxNode)i)) continue;
      bool inFrameTitle = false;
      const AxNodeData *cursor = data;
      while (cursor != nullptr && cursor->parent != kNoNode) {
        const AxNodeData *parent = pre.At(cursor->parent);
        if (parent == nullptr) break;
        if (parent->role == kRoleGroup && parent->description == kFrameTitleGroupLabel) {
          inFrameTitle = true;
          break;
        }
        cursor = parent;
      }
      if (!inFrameTitle) continue;
      bool hasRun = false;
      bool hasDebug = false;
      for (AxNode sibling : pre.ChildrenInLiveOrder(data->parent)) {
        const AxNodeData *siblingData = pre.At(sibling);
        if (siblingData == nullptr || siblingData->role != kRoleButton) continue;
        if (siblingData->description == runLabel) hasRun = true;
        if (siblingData->description == debugLabel) hasDebug = true;
      }
      if (!hasRun || !hasDebug) continue;
      matches++;
      if (matches > 1) break;
    }
    if (matches == 0) {
      return MakeResult(env, false, false, "SELECTOR_NOT_IDENTIFIED",
                        "actuation target was not uniquely proven on the pre-action snapshot");
    }
    if (matches > 1) {
      return MakeResult(env, false, false, "TARGET_AMBIGUOUS",
                        "actuation target was not uniquely proven on the pre-action snapshot");
    }
    int postMatches = 0;
    for (int i = 1; i <= (int)post.nodes.size(); i++) {
      const AxNodeData *data = post.At((AxNode)i);
      if (data == nullptr || data->role != kRoleButton || IdentityOf(*data) != argument) continue;
      if (!data->focused && !data->selected) continue;
      postMatches++;
    }
    if (postMatches != 1) {
      return MakeResult(env, true, false, "STATE_NOT_VERIFIED",
                        "the required accessibility state was not observed after the action");
    }
    return MakeResult(env, true, true, "OK", "state verified after the action");
  }

  if (operation == "SHOW_TEST_RESULT") {
    int matches = 0;
    for (int i = 1; i <= (int)pre.nodes.size(); i++) {
      const AxNodeData *data = pre.At((AxNode)i);
      if (data == nullptr || data->role != kRoleTab || IdentityOf(*data) != argument) continue;
      if (!IsVisible(pre, (AxNode)i) || !HasAncestorRole(pre, (AxNode)i, kRoleTabGroup)) continue;
      matches++;
      if (matches > 1) break;
    }
    if (matches == 0) {
      return MakeResult(env, false, false, "RESULT_VIEW_NOT_IDENTIFIED",
                        "actuation target was not uniquely proven on the pre-action snapshot");
    }
    if (matches > 1) {
      return MakeResult(env, false, false, "TARGET_AMBIGUOUS",
                        "actuation target was not uniquely proven on the pre-action snapshot");
    }
    int postMatches = 0;
    for (int i = 1; i <= (int)post.nodes.size(); i++) {
      const AxNodeData *data = post.At((AxNode)i);
      if (data == nullptr || data->role != kRoleTab || IdentityOf(*data) != argument) continue;
      if (!data->selected || !IsVisible(post, (AxNode)i)) continue;
      postMatches++;
    }
    if (postMatches != 1) {
      return MakeResult(env, true, false, "STATE_NOT_VERIFIED",
                        "the required accessibility state was not observed after the action");
    }
    return MakeResult(env, true, true, "OK", "state verified after the action");
  }

  return MakeResult(env, false, false, "INVALID_ACTION", "unknown operation for the test seam");
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
