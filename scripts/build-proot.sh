#!/usr/bin/env bash
#
# scripts/build-proot.sh — 从源码可复现构建 proot 原生资产（不引入预编译二进制）。
#
# 产物（安装到 app/src/main/jniLibs/<abi>/，随仓库提交，可随时用本脚本重验）：
#   libproot_exec.so      proot 主程序（PIE 可执行、talloc 静态打入、仅依赖 libc.so）
#   libproot_loader.so    64 位 ptrace 注入 loader（-ffreestanding 静态 ELF）
#   libproot_loader32.so  32 位 loader（容器内跑 32 位程序时用；可缺省）
#
# 锁定的上游版本：
#   termux/proot @ a179d3e8a4e045aaa1fb8cc3284f23509d96d353
#   talloc 2.4.3（sha256 见下方 TALLOC_SHA256）
#
# 必打补丁（U72，sha256 见下方 PATCH_SHA256）：
#   patches/proot-unlink-eisdir-retry.patch —— 容器内 libc remove() 删目录恒
#   ENOENT 的根治。musl remove() 拿到 EISDIR 后复用路径寄存器重试
#   unlinkat(AT_REMOVEDIR)；seccomp 加速下 unlinkat 的 exit 停止被跳过，
#   enter 阶段改写过的 SYSARG_2 无人复原，重试路径被二次 re-root。
#   补丁给 PR_unlinkat 标 FILTER_SYSEXIT，强制走 exit 复原。不打此补丁，
#   工位就得继续靠 LD_PRELOAD shim 才能建 androidTest APK（原理与验证记录
#   见补丁头部和 docs/tasks/2026-11-09-proot-remove目录失效根治.md）。
#
# 依赖：Android NDK r29+（默认 /opt/android-sdk/ndk/29.0.14206865，可用 NDK_HOME 覆盖）、
#       git、curl、tar、gawk、GNU make。
#
# 已知坑（改脚本前先读）：
# - loader.c 在 -ffreestanding 下自带 basename 实现，全局 -include string.h 会与
#   bionic 的 basename 声明冲突 → loader 与主程序分两遍 make，仅主程序带该标志。
# - 命令行传入的 make 变量会完全覆盖 GNUmakefile 内的 +=，因此 PROOT_UNBUNDLE_LOADER
#   既要作为 make 变量传（决定 loader 独立产出），也要进 CFLAGS（编译期宏）。
# - arm64 的 HAS_POKEDATA_WORKAROUND 需要用 readelf 从 loader 提取符号表生成
#   loader-info.c：宿主 GNU readelf 未必支持目标架构，PATH 前置 llvm-readelf。

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
BUILD_DIR="$ROOT_DIR/.proot-build"          # 中间产物，已 gitignore
OUT_BASE="$ROOT_DIR/app/src/main/jniLibs"   # 最终安装位置

PROOT_REPO="https://github.com/termux/proot.git"
PROOT_REV="a179d3e8a4e045aaa1fb8cc3284f23509d96d353"
TALLOC_URL="https://www.samba.org/ftp/talloc/talloc-2.4.3.tar.gz"
TALLOC_SHA256="dc46c40b9f46bb34dd97fe41f548b0e8b247b77a918576733c528e83abd854dd"
TALLOC_VER="2.4.3"
PATCH_FILE="proot-unlink-eisdir-retry.patch"
PATCH_SHA256="9a3c3c3c293660964942117c974ea691e4db32aa2abfd9796a18a271b0baeb8e"

: "${NDK_HOME:=/opt/android-sdk/ndk/29.0.14206865}"
TOOLCHAIN="$NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
API=26 # 与 app minSdk 一致

# 默认双 ABI：真机（arm64-v8a）+ 模拟器（x86_64）。可用参数覆盖：./build-proot.sh arm64-v8a
ABIS=("$@")
[ ${#ABIS[@]} -gt 0 ] || ABIS=(arm64-v8a x86_64)

abi_cc() {
    case "$1" in
        arm64-v8a) echo "$TOOLCHAIN/aarch64-linux-android$API-clang" ;;
        x86_64)    echo "$TOOLCHAIN/x86_64-linux-android$API-clang" ;;
        *) echo "不支持的 ABI: $1（当前只编 arm64-v8a / x86_64）" >&2; return 1 ;;
    esac
}

# ----------------------------------------------------------------------------

fetch_sources() {
    mkdir -p "$BUILD_DIR/src"
    if [ ! -d "$BUILD_DIR/src/proot/.git" ]; then
        echo "==> clone termux/proot"
        git clone -q "$PROOT_REPO" "$BUILD_DIR/src/proot"
    fi
    git -C "$BUILD_DIR/src/proot" fetch -q origin master 2>/dev/null || true
    git -C "$BUILD_DIR/src/proot" checkout -q "$PROOT_REV"

    # U72：锁定 rev 后必打 unlinkat EISDIR 补丁（为什么必须打见文件头）；失败即中止。
    echo "$PATCH_SHA256  $ROOT_DIR/patches/$PATCH_FILE" | sha256sum -c --status
    if git -C "$BUILD_DIR/src/proot" apply -R --check "$ROOT_DIR/patches/$PATCH_FILE" 2>/dev/null; then
        echo "==> 补丁已在树上: $PATCH_FILE"
    elif git -C "$BUILD_DIR/src/proot" apply "$ROOT_DIR/patches/$PATCH_FILE"; then
        echo "==> 补丁已应用: $PATCH_FILE"
    else
        echo "补丁应用失败: $PATCH_FILE（源码树与锁定 rev 不一致或有额外改动）" >&2
        exit 1
    fi

    local tarball="$BUILD_DIR/src/talloc-$TALLOC_VER.tar.gz"
    if [ ! -f "$tarball" ]; then
        echo "==> 下载 talloc $TALLOC_VER"
        curl -sfL --retry 3 --retry-delay 2 -o "$tarball" "$TALLOC_URL"
    fi
    echo "$TALLOC_SHA256  $tarball" | sha256sum -c --status
    [ -d "$BUILD_DIR/src/talloc-$TALLOC_VER" ] || tar xzf "$tarball" -C "$BUILD_DIR/src"
}

# talloc：单文件编译（waf 交叉编译要求 qemu，见 proot-talloc-replace.h 头注释）
build_talloc() { # $1=abi $2=cc
    local abi="$1" cc="$2" src="$BUILD_DIR/src/talloc-$TALLOC_VER" out="$BUILD_DIR/out/$abi"
    echo "==> talloc ($abi)"
    mkdir -p "$out/include"
    cp "$SCRIPT_DIR/proot-talloc-replace.h" "$out/include/replace.h"
    "$cc" -O2 -Wall -Wextra \
        -I"$out/include" -I"$src" \
        -DTALLOC_BUILD_VERSION_MAJOR=2 \
        -DTALLOC_BUILD_VERSION_MINOR=4 \
        -DTALLOC_BUILD_VERSION_RELEASE=3 \
        -c "$src/talloc.c" -o "$out/talloc.o"
    "$TOOLCHAIN/llvm-ar" rcs "$out/libtalloc.a" "$out/talloc.o"
}

# proot 主程序 + loader
build_proot() { # $1=abi $2=cc
    local abi="$1" cc="$2"
    local src="$BUILD_DIR/work/proot-$abi" out="$BUILD_DIR/out/$abi"
    echo "==> proot ($abi)"
    rm -rf "$src"
    mkdir -p "$src" "$BUILD_DIR/work"
    cp -r "$BUILD_DIR/src/proot/." "$src/"

    # loader-info.c 的生成规则直接调 `readelf`，必须是能读目标架构的 llvm-readelf
    local bin_shim="$BUILD_DIR/work/bin-$abi"
    mkdir -p "$bin_shim"
    ln -sf "$TOOLCHAIN/llvm-readelf" "$bin_shim/readelf"

    local common=(
        CC="$cc"
        STRIP="$TOOLCHAIN/llvm-strip"
        OBJCOPY="$TOOLCHAIN/llvm-objcopy"
        OBJDUMP="$TOOLCHAIN/llvm-objdump"
        CPPFLAGS="-D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -I. -I$BUILD_DIR/src/talloc-$TALLOC_VER"
        PROOT_UNBUNDLE_LOADER=/libexec/proot
    )

    # 1) loader 先行：不带 -include string.h（见文件头「已知坑」）
    (cd "$src/src" && make -j"$(nproc)" "${common[@]}" \
        CFLAGS="-O2 -Wall -Wextra" \
        LDFLAGS="-Wl,-z,noexecstack" \
        loader/loader loader/loader-m32)

    # 2) 主程序：-include string.h 补齐老代码缺失的隐式声明（clang 16+ 视为 error）。
    #    宏值里的引号要穿过 bash → make → shell 三层，所以是 \\\"。
    (cd "$src/src" && PATH="$bin_shim:$PATH" make -j"$(nproc)" "${common[@]}" \
        CFLAGS="-O2 -Wall -Wextra -include string.h -DPROOT_UNBUNDLE_LOADER=\\\"/libexec/proot\\\"" \
        LDFLAGS="$out/libtalloc.a -Wl,-z,noexecstack" \
        proot)

    # 3) strip + 安装到 jniLibs
    local jni="$OUT_BASE/$abi"
    mkdir -p "$jni"
    for pair in \
        "src/proot:libproot_exec.so" \
        "src/loader/loader:libproot_loader.so" \
        "src/loader/loader-m32:libproot_loader32.so"; do
        local rel="${pair%%:*}" name="${pair##*:}"
        "$TOOLCHAIN/llvm-strip" --strip-unneeded "$src/$rel"
        cp "$src/$rel" "$jni/$name"
    done
}

report() { # $1=abi
    local jni="$OUT_BASE/$1"
    echo "    $1:"
    local f
    for f in libproot_exec.so libproot_loader.so libproot_loader32.so; do
        [ -f "$jni/$f" ] || { echo "    缺产物: $jni/$f"; exit 1; }
        printf '    %-22s %8s  %s\n' "$f" "$(du -h "$jni/$f" | cut -f1)" \
            "$("$TOOLCHAIN/llvm-readelf" -h "$jni/$f" | awk '/Class|Machine/{printf "%s ", $NF}')"
    done
}

# ----------------------------------------------------------------------------

for tool in git curl tar make awk; do
    command -v "$tool" >/dev/null || { echo "缺少工具: $tool" >&2; exit 1; }
done
[ -x "$TOOLCHAIN/clang" ] || {
    echo "NDK 工具链不存在: $TOOLCHAIN（用 NDK_HOME=... 覆盖默认路径）" >&2
    exit 1
}

fetch_sources
for abi in "${ABIS[@]}"; do
    cc="$(abi_cc "$abi")"
    build_talloc "$abi" "$cc"
    build_proot "$abi" "$cc"
    report "$abi"
done

echo "==> 完成：产物已安装到 $OUT_BASE"
