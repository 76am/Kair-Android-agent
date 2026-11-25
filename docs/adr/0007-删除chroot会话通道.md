# 0007. 删除 chroot 会话通道，`su` 降级为显式提权

- **状态**：**已接受**（负责人 2026-11-12 拍板：「删吧，su 保留」）
- **日期**：2026-11-12
- **关联**：`docs/架构与内核边界.md` §9.6（执行通道）、§9.7（两档对齐）、§9.9（工作区权限）；
  `docs/tasks/2026-11-08-chroot通道降权默认非root.md`（U65，被本条取代）；
  `docs/handoff/2026-11-07-用户终端PTY升级.md`（U59，两通道的来源）

## 背景

Kair 的容器有两个执行通道：`proot`（默认，不需要 Root）与 `chroot`（经 `su` 进真 Root 域再 chroot）。
chroot 档存在三个结构性问题，且都不是实现缺陷，是「真 Root」的定义性后果：

1. **落盘属主是真 root**。chroot 里 `mkdir` / `touch` 出来的文件属主是 `root:root`；
   切回 proot（应用 uid）后，`unlink` 需要父目录写权限，常出现「文件删不掉」。现有三道补救
   全是事后：`umask 000`（让新 inode 变 666/777）、`WorkspacePermissionRepair`（事后 `chown -R`）、
   `WorkspaceFileAccess`（面板删除/回收站恢复绕权限）。
2. **模型命令默认以真 Root 执行**。一次 prompt injection 或一个恶意仓库脚本，在 chroot 档就能动
   系统文件；proot 档的 `--root-id` 只是 ptrace 层伪造，落盘仍是应用 uid。两条通道的权限边界不一致。
3. **长尾功能却让全仓双档维护**。chroot 需要设备已 Root，普通手机用不上；但它牵连
   `app/src/main` **32 个文件 / 172 处**、`app/src/test` 与 `app/src/androidTest` **17 个文件 / 130 处**，
   以及面板、提示词、环境注记、挂载、后台脱离各一套分支。

此外还有一条**已知的假成功**：`WorkspaceFileAccess.repairWorkspacePermissions` 不区分通道，
一律把 `chown -R` 交给当前容器后端；在 proot 档下 `chown` 会被 `fake_id0` 伪装成功，
界面报「已修复」，`stat -c %u` 仍是 `0`。

U65 曾计划「保留 chroot、默认降权到应用 uid，显式 `as_root` 才用 Root」。技术负责人 2026-11-08 的
评估结论是：按「文件属主」这个理由不值得做（成本接近 U59 的 68 笔 / 77 文件），正解是 user namespace；
按「收敛 Root 安全面」这个理由值得做，但那是另一份方案、判据要重写。

## 决策

**删除 chroot 作为「会话通道」，`su` 保留为「显式提权的单条命令后端」。**

具体形状：

| 维度 | 决定 |
|---|---|
| 会话通道 | 只剩 `proot`。`SessionChannel` 不再有 `CHROOT`；面板无通道选择器；`containerMode` 设置项下线 |
| 终端 / PTY | 只有 proot 档；chroot 专属的 Ctrl-C 补刀、pgid 发现、`su` 树收尾全部删除 |
| 后台任务 / 脱离进程 | 只走 proot 档（U60 的前台进程 + `Process` + `killTree` 那套） |
| `su` | **保留现状，不新增机制**：`android_shell`（`LocalShellTool` → `LocalCommandRunner`）本来就支持 `su -c '<命令>'`，走宿主真 Root 跑单条命令，与容器无关 |
| 容器内的 `su` | **不注入**。不在 rootfs 里放 `/usr/local/bin/su`，不给 `alpine_shell` 加 `as_root` 参数，不引 `PrivilegedCommandRunner` 接口（U65 §4.4 那套随之作废） |
| `RootCommandRunner` | **保留**。继续负责挂载 bind、工作区权限迁移、显式提权这三种需要真 Root 的内部动作 |
| proot 的 `--root-id` | **不动**。装包（`apk add`）靠它伪造 uid 0，一直是可用的 |
| 存量设置 | `containerMode == "chroot"` 读取时归一为 `"proot"`（与既有 `auto → proot` 归一同一处） |
| 存量 root 属主文件 | 一次性迁移：走真 `su` 硬 `chown` 回应用 uid，**不经 proot**（否则假成功）。**负责人主力机重装后已无存量**（2026-11-12 确认），本条面向「从旧版升级、有 chroot 历史」的用户；验收时用**手工造**的 root 属主文件当场景，不依赖存量 |

## 后果

**收益**

- 「root 属主文件删不掉」这一类 bug 的**根因消失**，不再需要 `umask 000` 兜底与事后修补（修补代码保留，作为显式提权产物的兜底）。
- 模型默认不再跑在真 Root 上。要 Root 必须显式写 `su -c`，可进审批（`DangerCommand` 已有机制），安全面从「默认最高权限」变成「默认应用 uid」。
- 全仓从双档变单档：会话 / PTY / 后台 / 挂载 / 文案 / 测试各减一套分支，净删代码。
- U65（chroot 降权）整批作废；U72 的 C 层 P5（chroot 档不受影响）自动关账。
- 无 Root 手机与有 Root 手机行为一致 —— 不再有「同一份配置换台机器就换语义」。

**代价**

- 失去「整段会话以真 Root 运行」的能力：容器内 `<1024` 端口监听、`gdb`/`strace` attach 别的进程、`mknod`、真 `chown` 给别人，这些以后只能靠 `android_shell` 的 `su -c` 做，且**在容器外**执行（没有 rootfs 用户态）。要在容器里做这类事，得先用 `su -c` 手工起一次 chroot —— 那是显式的一次性动作，不再是产品通道。
- 失去 chroot 档 `setsid … &` 的存活语义（proot 有 `--kill-on-exit`）。替代是 U60 的前台进程 + `killTree` 收尾，功能上已有覆盖。
- 显式提权产生的文件仍会是 root 属主（这是 Root 的本性）。所以要保留权限修复入口，并**修好它的假成功**：chown 必须走真 `su`，且修完要能用 `stat -c %u` 自证。
- 删除之后想恢复通道，要重写 PTY / 后台 / 挂载三套分支 —— **回退成本高，这是本条进 ADR 的理由**。

**被否决的选项**

| 选项 | 为什么不做 |
|---|---|
| U65：保留 chroot、默认降权 + `as_root` | 成本接近 U59（新 native 二进制、plugin-api 版本、审批键、三条执行路径、迁移），而 Root 安全面照样保留（`as_root=true` 仍是真 Root）。花同样的钱买到更小的结果 |
| 收尾定向 `chown`（几十行脚本） | 只治标：通道与安全面都在，中间态（chroot 跑完立刻切 proot）照样删不掉 |
| user namespace（`unshare -Ur`） | 唯一能同时满足「落盘属主是 app uid」与「apk 可用」的正解，但 `CONFIG_USER_NS` / SELinux / seccomp 支持参差，要逐机型探测；删通道之后这个需求本身消失了 |
| 保留通道但默认 proot、chroot 藏在高级设置里 | 双档维护成本一分不少，只是把坑埋深 |

## 附：X11 图形界面场景评估（2026-11-12，负责人提出）

负责人后续想加 X11 图形界面，问「现有 chroot 代码对 X11 有用吗、让容器以 root 跑能否提升渲染性能」。
本节是核实结论，**维持删除**。

### X11 在容器里实际需要什么

三件事，都不涉及真 Root：一个 X server 常驻、客户端能连上（unix socket 或 TCP）、画面送到屏幕。
三条常见路径都跑在 proot 档：

| 路径 | 组成 | 端口 | 要 root 吗 |
|---|---|---|---|
| VNC | Xvfb + x11vnc + 安卓 VNC viewer | 5900 | 否 |
| Termux:X11 | 独立安卓 app 充当 X server 前端 | unix socket | 否 |
| Web | Xvfb + noVNC（websockify） | 6080 | 否 |

chroot 独有的四样能力（`<1024` 端口、attach 别的进程、`mknod`、真 `chown`）**在 X11 场景全部用不上**：
X11 的 display 端口是 6000、VNC 是 5900、noVNC 是 6080，都 >1024。

### root 能不能提升渲染性能：不能

渲染快慢取决于有没有拿到 GPU，root 改的是**权限**不是**驱动**：

- 容器（无论 proot 还是 chroot）不在安卓图形栈里 —— 没有 `SurfaceFlinger` 的 surface，拿不到
  `EGL` / `GLES` 上下文，画面只能软件光栅化（llvmpipe）后整帧搬运。
- 想要硬件加速，得有 DRM render node（`/dev/dri/renderD128`）**加匹配的用户态驱动**。安卓的 GPU 驱动是
  闭源 gralloc + EGL 实现，不提供标准 DRM 用户态接口；`freedreno` 要定制内核，`turnip` / `zink` 是实验路线
  且要求能 `dlopen` 厂商 `libvulkan.so`（这件事 proot 里 bind `/dev` 同样能尝试，与 chroot 无关）。
- root 能做的是 `chmod 666 /dev/dri/renderD128` 打开节点权限；打开之后没有可用驱动，照样退回软件渲染。
  **权限不等于性能。**
- 软件渲染的瓶颈在 llvmpipe 的 CPU 光栅化循环（纯计算）；chroot 相对 proot 省的是 **syscall 翻译开销**
  （ptrace 中断）。省下的这点开销对纯计算循环几乎无感，它不是图形场景的瓶颈。
- chroot 档真正有优势的是 **CPU 密集型负载**（编译、打包），与图形无关。

### 反例：chroot 跑 X server 是负资产

X server 若以真 root 起在 chroot 档，它生成的 socket、`/tmp/.X11-unix/X0`、锁文件属主都是 `root:root`；
切回 proot（应用 uid）后连不上、也删不掉 —— 正是本条背景第 1 条那个 bug 在图形场景的原样复现。

### proot 档的已知坑（提前知道即可，不构成保留 chroot 的理由）

- `SysV IPC` / `MIT-SHM` 在 proot 下可能不可用：X server 加 `-extension MIT-SHM` 关掉共享内存，
  可用、性能略降。
- X server 必须常驻：走 U60 的后台任务（前台进程 + `Process` + `killTree`），不是 `setsid … &`。
- unix socket 与 `XDG_RUNTIME_DIR` 落在 rootfs 内可写目录，别指向宿主 `/tmp`。

**结论**：X11 图形界面按 proot 单档设计（VNC 或 Termux:X11 前端），立批时按本节走。

### 图形加速场景下 `su` 的用法（负责人 2026-11-12 拍板）

负责人明确：**必须支持天玑 8100 主力机**；「如果有 su，可以按单条命令提权增加性能，不需要整个会话
以 root 运行产生一堆 root 属性文件」。

这条正好落在本 ADR 的设计上 —— 需要真 Root 的动作全部走 `android_shell` 的 `su -c '<单条命令>'`，
**不恢复会话通道**：

| 动作 | 命令形态 | 频率 |
|---|---|---|
| 放开 GPU 设备节点 | `su -c 'chmod 666 /dev/mali0'`（或 `/dev/dri/renderD128`、`/dev/kgsl-3d0`） | 开机后一次（节点权限重置后需重做） |
| 把厂商图形库挂进容器 | `su -c 'mount --bind /vendor <rootfs>/vendor'`，或走 `RootCommandRunner` 既有挂载路径 | 容器启动一次 |
| 一次性修正存量 root 属主文件 | `su -c 'chown -R <uid>:<uid> <path>'` | 升级后一次 |

三条都不在容器里留下 root 属主文件：`chmod` / `mount` 不产生 inode，`chown` 是把属主**改回**应用 uid。
这正是「单条提权」与「整段会话 root」在文件系统上的本质差别。
