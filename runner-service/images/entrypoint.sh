#!/bin/sh
set -eu

# /source 是只读登记工作区；所有构建副作用只发生在容器 tmpfs 中。
cp -R /source/. /workspace/
find /workspace -mindepth 1 -exec chmod u+w {} +
mkdir -p /workspace/.jansi

# 工作目录由 Java 侧校验、Runner 侧复核；这里只接受 /workspace 之下并显式 cd。
target="${STUDYPILOT_WORKDIR:-/workspace}"
case "$target" in
    /workspace|/workspace/*) ;;
    *)
        echo "invalid working directory" >&2
        exit 64
        ;;
esac
cd "$target"
exec "$@"
