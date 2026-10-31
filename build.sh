#!/usr/bin/env bash
# 构建脚本：使用本机已安装的 Gradle 8.14（兼容 AGP 8.7.3）
set -euo pipefail

cd "$(dirname "$0")"

GRADLE_BIN="${GRADLE_BIN:-$HOME/.gradle/wrapper/dists/gradle-8.14-all/c2qonpi39x1mddn7hk5gh9iqj/gradle-8.14/bin/gradle}"

if [ ! -x "$GRADLE_BIN" ]; then
  echo "找不到 Gradle，请设置 GRADLE_BIN 环境变量指向 gradle 可执行文件" >&2
  exit 1
fi

TASK="${1:-assembleDebug}"
echo ">>> gradle $TASK"
"$GRADLE_BIN" "$TASK" "${@:2}"
