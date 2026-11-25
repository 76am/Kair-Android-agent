/*
 * lib/replace 的最小替代：绕过 talloc 的 waf 交叉编译（要求 qemu --cross-execute），
 * 用 Android NDK clang 直接编译 talloc.c。只提供 talloc.c / talloc.h 实际用到的
 * 标准头与宏。由 scripts/build-proot.sh 以 -I 注入。
 */
#ifndef _TALLOC_REPLACE_SHIM_H
#define _TALLOC_REPLACE_SHIM_H

#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdbool.h>
#include <errno.h>
#include <limits.h>

#define _PUBLIC_
#define _DEPRECATED_
#define HAVE_CONSTRUCTOR_ATTRIBUTE 1
#define HAVE_VA_COPY 1

#ifndef MIN
#define MIN(a, b) ((a) < (b) ? (a) : (b))
#endif

#define XSTR(x) #x
#define STR(x) XSTR(x)
#define __location__ __FILE__ ":" STR(__LINE__)

#ifndef PRINTF_ATTRIBUTE
#define PRINTF_ATTRIBUTE(a, b) __attribute__((format(__printf__, a, b)))
#endif

#define discard_const_p(type, ptr) ((type *)(uintptr_t)(ptr))
#define discard_const(ptr) discard_const_p(const void *, (ptr))

#endif /* _TALLOC_REPLACE_SHIM_H */
