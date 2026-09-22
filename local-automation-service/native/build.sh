#!/usr/bin/env bash
#
# StudyPilot Task 33 — build the IntelliJ IDEA Accessibility bridge.
#
# BUILD-TIME ONLY. This script compiles the native addon. The automation service runtime
# never invokes a shell, a child process, osascript, AppleScript, JXA, or `open`; it only
# loads the compiled .node binding in-process.
#
# Two artifacts are produced from the same single source of truth:
#   native/build/idea_ax_bridge.node       production   (exactly four exports)
#   native/build/idea_ax_bridge.test.node  test seam     (four exports + __testEvaluate)
#
# The production artifact never contains the test seam, and the TypeScript export-surface
# guard rejects any module that exposes an extra export.
#
# Usage: bash native/build.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SOURCE="${SCRIPT_DIR}/idea_ax_bridge.mm"
OUT_DIR="${SCRIPT_DIR}/build"

NODE_BIN="${NODE:-$(command -v node || true)}"
if [[ -z "${NODE_BIN}" ]]; then
  echo "build:native ERROR: node executable not found on PATH" >&2
  exit 1
fi

NODE_INCLUDE="$(dirname "$(dirname "${NODE_BIN}")")/include/node"
if [[ ! -f "${NODE_INCLUDE}/node_api.h" ]]; then
  echo "build:native ERROR: Node-API headers not found at ${NODE_INCLUDE}" >&2
  exit 1
fi

if ! command -v clang++ >/dev/null 2>&1; then
  echo "build:native ERROR: clang++ is required (install the Xcode command line tools)" >&2
  exit 1
fi

mkdir -p "${OUT_DIR}"

COMMON_FLAGS=(
  -std=c++20
  -fobjc-arc
  -fblocks
  -O2
  -Wall
  -bundle
  -undefined dynamic_lookup
  -I"${NODE_INCLUDE}"
  -framework AppKit
  -framework ApplicationServices
  -framework Foundation
)

echo "build:native -> production artifact"
clang++ "${COMMON_FLAGS[@]}" \
  -DNODE_GYP_MODULE_NAME=idea_ax_bridge \
  -o "${OUT_DIR}/idea_ax_bridge.node" \
  "${SOURCE}"

echo "build:native -> test-seam artifact"
clang++ "${COMMON_FLAGS[@]}" \
  -DAX_BRIDGE_TEST_SEAM=1 \
  -DNODE_GYP_MODULE_NAME=idea_ax_bridge \
  -o "${OUT_DIR}/idea_ax_bridge.test.node" \
  "${SOURCE}"

echo "build:native OK -> ${OUT_DIR}/idea_ax_bridge.node"
echo "build:native OK -> ${OUT_DIR}/idea_ax_bridge.test.node"
