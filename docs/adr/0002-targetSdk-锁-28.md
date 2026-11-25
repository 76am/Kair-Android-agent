# 0002. targetSdk 锁 28（proot exec 权限）

- **状态**：已接受
- **日期**：2026-10（追记；实现见 `app/build.gradle.kts:11-20`）

## 背景

Kair 的 Linux 工作区靠 proot / chroot 在 app 私有目录里跑 rootfs 里的二进制。
Android 10（API 29）起，`targetSdk ≥ 29` 的应用被 SELinux 放进 `untrusted_app_29+` 域，
**禁止 exec / dlopen 应用私有目录中的文件**——rootfs 里所有二进制（含 busybox）都起不来。

同款问题 Termux 也遇到过，它的解法是留在 `untrusted_app_27` 兼容域。

## 决策

```kotlin
compileSdk = 35   // 用新 SDK 编译，能拿新 API
minSdk = 26
targetSdk = 28    // 而不是 35
```

**编译版本与目标版本解耦**：`compileSdk` 跟进最新只是「能调用新 API」，
`targetSdk` 才决定运行时的 SELinux 域与兼容行为。

## 后果

**代价（已知且接受）**：

- **无法上架 Google Play**（要求 targetSdk 34+）；侧载 / 自建分发不受影响；
- 新系统的行为变更不会自动生效（分区存储、通知权限、前台服务类型等），需要代码自己适配；
- 每次系统大版本更新都要重新验证 `untrusted_app_27` 域是否还在。

**触发重新评估的条件**（任一条成立就必须新写 ADR）：

1. 需要上架 Play；
2. 目标 Android 版本移除 `untrusted_app_27` 兼容域；
3. 找到不依赖 exec 私有目录的容器方案（例如纯 ptrace 解释执行，性能代价另行评估）。

## 被否决的选项

| 选项 | 否决理由 |
|---|---|
| 跟进 targetSdk 35 | rootfs 二进制全部无法执行，容器能力归零——这是产品核心能力 |
| 依赖用户另装 Termux 提供 shell | 把核心能力外包给另一个 app；文件权限、生命周期、UI 都没法保证 |
| 只做本地 `local_shell`（toybox） | 没有包管理器、没有编译工具链，「编码 Agent」退化成文本编辑器 |
