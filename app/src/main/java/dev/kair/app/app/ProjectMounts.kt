package dev.kair.app.app

import dev.kair.app.core.proot.Mount
import dev.kair.app.core.proot.projectsMount

/**
 * 常驻终端的挂载清单：宿主**工作区父目录** → 容器 `/projects`（唯一一条）。
 *
 * 以前是「每个项目一条挂载（`/projects/<slug>`）」，项目一多的代价不只是挂载数：新建项目
 * 必须补挂、改名 / 删除还要懒卸载。现在项目就是父目录下的真实子目录，一次挂载后增删改即时可见。
 *
 * 代价（负责人 2026-10-30 明确选择）：agent 能看见并修改其它项目 —— 不再以挂载隔离
 * 工作区，作用域交给系统提示里的 `<cwd>` 与工具参数。
 *
 * **为什么放在这个文件而不是 `AppContainer.kt` 里**：`AppContainer.kt` 顶着 B8 棘轮登记值
 * （只准减不准增），而这段是「一次存储读取 + 纯函数」，与 P4-1 抽出的 `AgentSettingsFactory.kt`、
 * 批次 9 抽出的 `PluginBridgeSupport.kt` 同一处置方式 —— 装配层只留调用点。
 *
 * 返回空清单只有一种情况：存储后端没有稳定的父目录（自定义实现且一个项目都没有）。
 * 内置存储的 [dev.kair.app.core.storage.StorageBackend.workspaceBase] 恒非空，
 * 所以「先起终端、后建第一个项目」也能直接看到新目录。
 */
fun AppContainer.projectMounts(): List<Mount> =
    storage.workspaceBase()?.let(::projectsMount).orEmpty()
