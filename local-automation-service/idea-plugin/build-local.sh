#!/usr/bin/env bash
#
# StudyPilot Task 33 — build the IDEA bridge plugin.
#
# PRIMARY path: Gradle + IntelliJ Platform Gradle Plugin (`./gradlew buildPlugin`), which is
# the reproducible, version-pinned build (see build.gradle.kts and gradle.properties).
#
# FALLBACK path (this script): compile against the INSTALLED IntelliJ Platform and package the
# installable ZIP. It is used when Gradle cannot be provisioned locally (for example on an
# offline or proxy-restricted host). It compiles and packages only; it NEVER installs or runs
# the plugin.
#
# Usage: bash build-local.sh
# Output: build/distributions/study-pilot-automation-bridge-<version>.zip (+ SHA-256)

set -euo pipefail

PLUGIN_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${PLUGIN_DIR}"

VERSION="$(sed -n 's/^pluginVersion=//p' gradle.properties 2>/dev/null | head -1)"
VERSION="${VERSION:-1.0.0}"

IDEA_HOME="${IDEA_HOME:-/Applications/IntelliJ IDEA.app}"
if [[ ! -d "${IDEA_HOME}" ]]; then
  echo "build-local ERROR: IntelliJ IDEA not found at ${IDEA_HOME} (set IDEA_HOME)" >&2
  exit 1
fi

JAVAC="${JAVAC:-javac}"
JAR="${JAR:-jar}"

PLATFORM_CP="$(find "${IDEA_HOME}/Contents/lib" -maxdepth 1 -name '*.jar' | tr '\n' ':')"
PLUGIN_CP="$(find "${IDEA_HOME}/Contents/plugins" -mindepth 2 -maxdepth 3 -name '*.jar' 2>/dev/null | tr '\n' ':' || true)"
CLASSPATH="${PLATFORM_CP}${PLUGIN_CP}"

BUILD_DIR="${PLUGIN_DIR}/build/local"
CLASSES="${BUILD_DIR}/classes"
TEST_CLASSES="${BUILD_DIR}/test-classes"
DIST="${PLUGIN_DIR}/build/distributions"
STAGE="${BUILD_DIR}/stage"

echo "build-local: compiling plugin against $(basename "${IDEA_HOME}")"
rm -rf "${BUILD_DIR}"
mkdir -p "${CLASSES}" "${TEST_CLASSES}" "${DIST}" "${STAGE}"

MAIN_SOURCES="$(find src/main/java -name '*.java' | sort | tr '\n' ' ')"
# shellcheck disable=SC2086
"${JAVAC}" --release 21 -nowarn -cp "${CLASSPATH}" -d "${CLASSES}" ${MAIN_SOURCES}

echo "build-local: compiling self test"
TEST_SOURCES="$(find src/test/java -name '*.java' | sort | tr '\n' ' ')"
# shellcheck disable=SC2086
"${JAVAC}" --release 21 -nowarn -cp "${CLASSES}:${CLASSPATH}" -d "${TEST_CLASSES}" ${TEST_SOURCES}

echo "build-local: running self test"
java -Dstudypilot.plugin.source="${PLUGIN_DIR}/src/main/java" \
     -Dstudypilot.plugin.resources="${PLUGIN_DIR}/src/main/resources" \
     -cp "${TEST_CLASSES}:${CLASSES}:${CLASSPATH}" \
     com.studypilot.automation.idea.PluginSelfTest

echo "build-local: packaging"
cp -R src/main/resources/. "${CLASSES}/"
JAR_NAME="study-pilot-automation-bridge"
mkdir -p "${STAGE}/${JAR_NAME}/lib"
"${JAR}" --create --file "${STAGE}/${JAR_NAME}/lib/${JAR_NAME}.jar" -C "${CLASSES}" .

# Distinct name so the Gradle artifact (the primary path) is never overwritten by the fallback.
ZIP_NAME="study-pilot-automation-bridge-${VERSION}-local.zip"
(cd "${STAGE}" && "${JAR}" --create --file "${DIST}/${ZIP_NAME}" "${JAR_NAME}")

echo "build-local: artifact ${DIST}/${ZIP_NAME}"
echo "build-local: sha256 $(shasum -a 256 "${DIST}/${ZIP_NAME}" | awk '{print $1}')"
echo "build-local: INSTALLATION IS NOT PERFORMED — Codex review and explicit user confirmation are required first."
