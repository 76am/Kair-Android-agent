#!/usr/bin/env bash
#
# scripts/build-pty.sh — 可复现构建用户终端的 PTY native 薄层（U59 路线 H）。
#
# 产物（安装到 app/src/main/jniLibs/<abi>/，随仓库提交，可随时用本脚本重验）：
#   libkair_pty.so    openpty / spawn(fork+setsid+TIOCSCTTY+dup2+execve) / resize / close
#
# 锁定的工具链：Android NDK 23.1.7779620（bionic 的 openpty 自 API 23 起可用，
# app minSdk = 26；API 26 的 wrapper 直接编译，不引 AGP 的 externalNativeBuild）。
#
# 依赖：NDK（默认 /opt/android-sdk/ndk/23.1.7779620，可用 NDK_HOME 覆盖）、clang。
# 本工位（WSL2）用：
#   NDK_HOME=/mnt/f/android-sdk/ndk/23.1.7779620 ./scripts/build-pty.sh
#
# 产物 sha256（与脚本同提交，重产出必须逐字相同）：
#   arm64-v8a: ea16aefd161328455c1741928258dd9a805b73c979fae62bc0254dd96f7f88d7
#   x86_64:    d012c31a903d49b92299e7640aec00a9ad7c13e16f96d265bb4cc97988498a99

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
SRC="$ROOT_DIR/app/src/main/cpp/pty_jni.c"
OUT_BASE="$ROOT_DIR/app/src/main/jniLibs"

: "${NDK_HOME:=/opt/android-sdk/ndk/23.1.7779620}"
TOOLCHAIN="$NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
API=26 # 与 app minSdk 一致

ABIS=("$@")
[ ${#ABIS[@]} -gt 0 ] || ABIS=(arm64-v8a x86_64)

abi_cc() {
    case "$1" in
        arm64-v8a) echo "$TOOLCHAIN/aarch64-linux-android$API-clang" ;;
        x86_64)    echo "$TOOLCHAIN/x86_64-linux-android$API-clang" ;;
        *) echo "不支持的 ABI: $1（当前只编 arm64-v8a / x86_64）" >&2; return 1 ;;
    esac
}

[ -f "$SRC" ] || { echo "缺少源文件: $SRC" >&2; exit 1; }
[ -x "$TOOLCHAIN/clang" ] || {
    echo "NDK 工具链不存在: $TOOLCHAIN（用 NDK_HOME=... 覆盖默认路径）" >&2
    exit 1
}

for abi in "${ABIS[@]}"; do
    cc="$(abi_cc "$abi")"
    jni="$OUT_BASE/$abi"
    mkdir -p "$jni"
    echo "==> libkair_pty ($abi)"
    "$cc" -O2 -Wall -Wextra -fPIC -shared \
        -Wl,-z,noexecstack \
        -o "$jni/libkair_pty.so" "$SRC"
    "$TOOLCHAIN/llvm-strip" --strip-unneeded "$jni/libkair_pty.so"
    printf '    %-18s %8s  %s\n' "libkair_pty.so" "$(du -h "$jni/libkair_pty.so" | cut -f1)" \
        "$(sha256sum "$jni/libkair_pty.so" | cut -d' ' -f1)"
done

echo "==> 完成：产物已安装到 $OUT_BASE"
