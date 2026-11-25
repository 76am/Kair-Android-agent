package dev.kair.app.ui.plugins.builtin

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import java.io.File

/**
 * 面板内返回键的目标目录。
 *
 * 边界是**面板打开时的根**（[panelRoot]），不是文件策略的 `cap`：`cap` 是 Kair 根，
 * 拿它当返回边界会让用户在文件夹面板里一路翻到 `/data`，而按返回的本意是撤销
 * 「我刚刚下钻了哪一级」。两套语义各自一个实现点：「上级目录」按钮仍走 `FsPolicy.parentOf`。
 *
 * @return 退一级后的目录；已在 [panelRoot] 或已上翻到它之外时返回 null（交回 NavHost 退出面板）。
 */
internal fun backTarget(dir: String, panelRoot: String): String? =
    if (dir.startsWith("$panelRoot/")) File(dir).parentFile?.path else null

/**
 * 面板内**二级态**的返回接管 —— 三个入口按二级态的形状选，面板里**不许直接写 `BackHandler`**
 * （`PanelBackTest` 会红；它另有一条「内置返回页必须配对入口」的全源码判据，见下）。
 *
 * 为什么要收成入口：二级态「自己写一个 `BackHandler`」是纯手工约定，**漏接不会有任何信号**。
 * U53 上线后，U74 新增的整页编辑器就是一个 `editing` 布尔，没人想到还要补 `BackHandler` ——
 * 真机上按系统返回直接退到会话，内置返回按钮却正常（2026-11-11 复验发现）。
 * 根因不是「谁忘了」，而是「返回接管和状态声明是两处、可以只写一处」。
 *
 * 现在状态和返回接管**在同一处**产生：用了入口的状态就天然接上了返回。但静态守卫只能证明
 * 「写出来的形状合规」，证明不了「没有漏写的二级页」—— 新增二级页若绕过入口，
 * `PanelBackTest` 靠全源码扫 `onBack` 与入口的配对把它判红；剩余边界与已登记的已知欠账
 * 见该测试的 `KNOWN_UNWIRED` 与交接。真机按返回仍是最后一关。
 *
 * - 路径下钻 → [rememberPanelBackPath]
 * - 布尔开关（编辑态 / 展开态） → [rememberPanelBackOpen]
 * - 外部持有开合状态（推屏抽屉） → [rememberPanelBackToggle]
 * - 列表 → 详情的选中项 → [rememberPanelBackSelection]
 *
 * 二级态不是这几种形状时，别退回手写 `BackHandler`：先看能不能表述成上面之一，
 * 表述不了再加新入口（并在此处 KDoc 里写清形状与判据）。
 */
@Composable
internal fun rememberPanelBackPath(initial: String, panelRoot: String): MutableState<String> {
    val dir = remember(initial) { mutableStateOf(initial) }
    BackHandler(enabled = backTarget(dir.value, panelRoot) != null) {
        backTarget(dir.value, panelRoot)?.let { dir.value = it }
    }
    return dir
}

/** 布尔二级态（编辑态 / 展开态）：开着的时返回就是关上，关着时返回交回面板容器。 */
@Composable
internal fun rememberPanelBackOpen(): MutableState<Boolean> {
    val open = remember { mutableStateOf(false) }
    BackHandler(enabled = open.value) { open.value = false }
    return open
}

/**
 * 外部已经持有开合状态的布尔二级态（推屏抽屉，U115）：只装返回接管，不新建状态。
 * 形状是「`Boolean` + 关闭动作」——入口仍集中在当前文件，面板 / 抽屉里不手写 `BackHandler`。
 */
@Composable
internal fun rememberPanelBackToggle(open: Boolean, onClose: () -> Unit) {
    BackHandler(enabled = open) { onClose() }
}

/**
 * 列表 → 详情的选中项（null = 在列表）。
 *
 * [enabledWhen] 必须吃**解析后的选中项**、而不是原始 id：记录被 prune / remove 回收后 id 还在、
 * 界面已退回列表，此时按返回必须退出面板，不能被一个指向不存在记录的 id 吞掉。
 */
@Composable
internal fun rememberPanelBackSelection(enabledWhen: (String?) -> Boolean): MutableState<String?> {
    val selected = rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = enabledWhen(selected.value)) { selected.value = null }
    return selected
}
