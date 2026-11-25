package dev.kair.app.core.plugin.dynamic

import dev.kair.app.BuildConfig
import dev.kair.plugin.api.KairApi

/**
 * 第三方插件安装前的**兼容性检查**。
 *
 * 检查项（任一不满足即视为不兼容）：
 * 1. **插件 API 大版本**：dex 插件的 `targetApi` 不得高于宿主 [KairApi.VERSION]
 *    （前向兼容 —— 插件用它要求一个尚不存在的 API 时会被拦下）。
 * 2. **Kair 应用版本范围**：manifest 的 `minKairVersion` / `maxKairVersion` 与宿主
 *    [KAIR_VERSION] 比对，区分「版本过低」与「版本过高」。
 * 3. **插件 id 冲突**：不得与任何内置插件 id 相同。
 *
 * 结果是一组**中文**、面向用户的「不兼容原因」；为空表示兼容。宿主据此决定：
 * 严格模式下拒绝安装，非严格模式仅告警放行；用户也可对单个插件选择「强制安装」。
 */
object PluginCompatibility {

    /**
     * 宿主 Kair 应用版本：直接取 `BuildConfig.VERSION_NAME`（即 `app/build.gradle.kts`
     * 的 `versionName`），插件清单的 `minKairVersion` / `maxKairVersion` 与它比对。
     * 不在代码里维护第二份版本号 —— 双份必然漂移。
     */
    val KAIR_VERSION: String get() = BuildConfig.VERSION_NAME

    /**
     * 检查 [manifest] 与当前宿主 / 内置插件的兼容性；返回**不兼容原因**列表
     * （空列表 = 兼容）。[builtinPluginIds] 为空集时不检查 id 冲突。
     */
    fun check(
        manifest: PluginManifest,
        hostVersion: String = KAIR_VERSION,
        builtinPluginIds: Set<String> = emptySet(),
    ): List<String> = buildList {
        // 1) 插件 API 大版本（仅 dex 插件声明 targetApi）。
        if (manifest.kind == PluginManifest.KIND_DEX && manifest.targetApi > KairApi.VERSION) {
            add("插件 targetApi v${manifest.targetApi} 高于宿主 v${KairApi.VERSION}，请升级 Kair")
        }
        // 2) Kair 应用版本范围 —— 低于下限 / 高于上限分别给出不同原因。
        manifest.minKairVersion?.takeIf { it.isNotBlank() }?.let { min ->
            if (compareVersions(hostVersion, min) < 0) {
                add("要求 Kair ≥ $min，当前 $hostVersion（版本过低）")
            }
        }
        manifest.maxKairVersion?.takeIf { it.isNotBlank() }?.let { max ->
            if (compareVersions(hostVersion, max) > 0) {
                add("仅支持 Kair ≤ $max，当前 $hostVersion（版本过高）")
            }
        }
        // 3) 与内置插件 id 冲突。
        if (manifest.id in builtinPluginIds) {
            add("插件 id「${manifest.id}」与内置插件冲突")
        }
    }

    /**
     * 比较两个点分版本号（`major.minor.patch`，可带 `-pre` 后缀）。返回负数 / 0 /
     * 正数，语义同 [Comparable.compareTo]。段数不足按 0 补齐，非数字段按 0 处理
     * （容错，不抛异常）。
     */
    fun compareVersions(a: String, b: String): Int {
        val pa = parse(a)
        val pb = parse(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val cmp = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
            if (cmp != 0) return cmp
        }
        return 0
    }

    /** 把 `"1.2.3-beta"` 解析为 `[1, 2, 3]`；预发布后缀忽略。 */
    private fun parse(v: String): List<Int> =
        v.trim().substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
}

/**
 * 插件因兼容性问题被拒绝安装。[reasons] 为具体原因（中文，可直接展示给用户）；
 * UI 捕获本异常后弹出「强制安装」选项。
 */
class PluginIncompatibleException(val reasons: List<String>) : IllegalArgumentException("插件与当前 Kair 不兼容：${reasons.joinToString("；")}")
