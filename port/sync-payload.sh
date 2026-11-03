#!/usr/bin/env bash
#
# 把 build-payload.py 产出的 Node 运行时同步进浏览器壳。
#
# 背景：payload（libnodebin.so 46MB + node-libs.zip 19MB + nodejs-project.zip 11MB）
# 不进版本库。克隆仓库后要按下面顺序重建：
#
#   # 1) 生成 payload（需要本机装有 dev-sidecar，取它的 app.asar 作为核心源码）
#   ./build.sh :netcore-js:jsNodeProductionLibraryDistribution
#   python port/build-payload.py --resources "<dev-sidecar-resources>"
#
#   # 2) 同步进壳
#   ./port/sync-payload.sh
#
# 用法：
#   ./port/sync-payload.sh                      # 默认同步到 lightning-browser
#   ./port/sync-payload.sh einkbro              # 同步到其它壳（目录名相对于仓库根）
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/app/src/main"

if [ ! -f "$SRC/jniLibs/arm64-v8a/libnodebin.so" ]; then
    echo "缺少 payload：$SRC/jniLibs/arm64-v8a/libnodebin.so" >&2
    echo "先运行：python port/build-payload.py --resources <dev-sidecar 安装目录>/resources" >&2
    exit 1
fi

TARGETS=("${@:-lightning-browser}")

for name in "${TARGETS[@]}"; do
    dst="$ROOT/$name/app/src/main"
    if [ ! -d "$dst" ]; then
        echo "跳过 $name：目录不存在" >&2
        continue
    fi

    mkdir -p "$dst/assets" "$dst/jniLibs/arm64-v8a" "$dst/res/raw"

    cp -f "$SRC/assets/node-libs.zip" "$SRC/assets/nodejs-project.zip" "$dst/assets/"
    cp -f "$SRC/assets/cacert.pem" "$SRC/assets/dsha_ca.crt" "$SRC/assets/dsha_ca.key.pem" "$dst/assets/"
    cp -f "$SRC/jniLibs/arm64-v8a/libnodebin.so" "$dst/jniLibs/arm64-v8a/"
    cp -f "$SRC/res/raw/dsha_ca.pem" "$dst/res/raw/"

    echo "已同步 → $name ($(du -sh "$dst/assets" | cut -f1) assets)"
done

echo "完成。现在可以构建：cd lightning-browser && ./gradlew assembleLightningPlusRelease"
