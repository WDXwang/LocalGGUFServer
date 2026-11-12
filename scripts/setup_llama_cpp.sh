#!/usr/bin/env bash
set -euo pipefail

# 拉取 llama.cpp 到 app/src/main/cpp/llama.cpp（固定版本，保证 JNI 接口匹配）
REPO="https://github.com/ggml-org/llama.cpp"
COMMIT="dd6b945be2188da875ab05a259ce94c6b2736e64"   # 2026-11-12 master

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT_DIR/app/src/main/cpp/llama.cpp"

if [ -f "$DEST/CMakeLists.txt" ]; then
    echo "[ok] 已存在 llama.cpp 源码: $DEST"
    echo "     如需重新拉取，请先删除该目录。"
    exit 0
fi

if ! command -v git >/dev/null 2>&1; then
    echo "[error] 未找到 git，请先安装 git。"
    exit 1
fi

echo "[info] 拉取 llama.cpp @ $COMMIT"
echo "[info] 目标目录: $DEST"

mkdir -p "$DEST"
git -C "$DEST" init -q
git -C "$DEST" remote add origin "$REPO"
git -C "$DEST" sparse-checkout init --cone
git -C "$DEST" sparse-checkout set include src ggml cmake vendor

if ! git -C "$DEST" fetch --depth 1 origin "$COMMIT"; then
    echo "[error] 拉取指定提交失败，请检查网络后重试。"
    echo "        也可以手动执行："
    echo "        git clone --depth 1 $REPO $DEST"
    echo "        注意：手动克隆最新版可能导致 JNI 接口不兼容。"
    exit 1
fi

git -C "$DEST" checkout -q FETCH_HEAD

echo "[ok] llama.cpp 准备完成"
echo
echo "后续步骤："
echo "  1. 用 Android Studio 打开项目根目录: $ROOT_DIR"
echo "  2. 在 SDK Manager 中安装 NDK 27.2.12479018 与 CMake 3.22.1"
echo "  3. Gradle Sync 后构建 APK（arm64-v8a / x86_64）"
