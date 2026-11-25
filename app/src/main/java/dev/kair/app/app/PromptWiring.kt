package dev.kair.app.app

import java.io.File

/**
 * 装配层里**能脱离 Android 测**的那部分：算出「这一回合该给模型什么」，不碰 `Context`。
 *
 * 现在只放了 `<cwd>` 的解析（P3-3B step5 的双路径判据落在这里）；批次 9（A2）会把
 * 「设置 → `AgentSettings`」整体搬进来，届时本文件就是装配接线唯一的可测边界。
 */

/**
 * 系统提示 `<cwd>` 段的取值：命令**实际执行处**。
 *
 * - 容器生效时（[containerPath] 非空）取 `CommandRunner.containerWorkspacePathFor(workspace)`
 *   的结果（`/projects/<目录名>`）—— 这个路径只存在于容器里，App 进程里没有；
 * - 否则取宿主侧真实工作区路径。
 *
 * 为什么单独抽出来：两条路径**各要一条断言**，只有一条就是没覆盖（P3-3B step5 的验收要求）。
 * 这个值进入缓存前缀，改一个字节就会作废会话已有的前缀缓存，所以表达式与
 * `CwdContributor` 的字节契约一起被 `PromptWiringTest` 钉住。
 */
internal fun promptCwd(containerPath: String?, workspace: File): String =
    containerPath ?: workspace.path
