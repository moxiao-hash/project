#!/bin/sh
set -eu

# /source 是只读登记工作区；所有构建副作用只发生在容器 tmpfs 中。
cp -R /source/. /workspace/
find /workspace -mindepth 1 -exec chmod u+w {} +
mkdir -p /workspace/.jansi
exec "$@"
