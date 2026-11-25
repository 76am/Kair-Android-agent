package dev.kair.app.core.plugin.dynamic

import dev.kair.app.core.ai.Json
import dev.kair.app.core.plugin.PluginRegistry
import dev.kair.app.core.skills.SkillRegistry
import dev.kair.app.core.tools.ToolRegistry
import kotlinx.serialization.encodeToString
import java.io.File
import dev.kair.plugin.api.Plugin as ApiPlugin

/**
 * 动态插件宿主：在 `plugins/` 下发现并装载两类插件。
 *
 * - **script**（`plugins/<id>/manifest.json` + 脚本）：纯数据，宿主解释清单，
 *   不加载任何代码。
 * - **dex**（`plugins/<id>.kplug` 包，或已解包目录）：`manifest.json`（kind=dex）
 *   + `classes.dex`，经 [KplugLoader] 装载，贡献点由插件代码注册。安装同样
 *   强制审批（`install_plugin` 不可永久放行）。
 *
 * 覆盖语义与内核一致：**后注册者胜**。动态插件注册在内置插件之后，因此可以用
 * 同名工具覆盖内置工具（例如用脚本工具替换原装 `container_shell`）。
 *
 * 持久化：`plugins/disabled.json` 记录被卸载（禁用）的插件 id —— 卸载不清文件
 * （便于恢复），但下次启动 [discover] 不会再装它；重新 install 即解除禁用。
 *
 * 回滚：卸载回收全部贡献点；单例能力（命令后端 / 存储 / 压缩策略）由注册表的
 * 提供者历史栈回滚到上一个提供者，无需重启。dex 插件的**类**会驻留内存直到
 * 进程重启（与游戏 mod 相同：禁用即失效，类不卸载）。
 */
class DynamicPluginHost(
    private val root: File,
    private val registry: PluginRegistry,
    private val tools: ToolRegistry,
    private val skills: SkillRegistry,
    /** 贡献点变化后由宿主调用，重建执行注册表的最终顺序（内置 → 插件 → 宿主工具）。 */
    private val resync: () -> Unit,
    /** dex 插件的优化输出目录（建议传应用的 codeCacheDir 子目录）。 */
    dexOptDir: File = File(root, "dex-opt"),
    /** dex 装载器；注入以便 JVM 单测用假类加载器覆盖整条链路。 */
    dexLoader: KplugLoader? = null,
    /** 内置插件 id 快照；安装前用于检测第三方插件 id 冲突（空集表示不检查）。 */
    private val builtinPluginIds: () -> Set<String> = { emptySet() },
    /** 兼容检查是否**严格模式**：true（默认）拒绝不兼容插件，false 仅告警放行。 */
    private val strictCompat: () -> Boolean = { true },
) {
    private val effectiveDexLoader: KplugLoader by lazy {
        dexLoader ?: KplugLoader(dexOptDir, javaClass.classLoader!!)
    }

    /** 已装载的动态插件（脚本或 dex），供卸载与预览用。 */
    private sealed interface Installed {
        val label: String
    }

    private class ScriptInstalled(val plugin: DynamicPlugin) : Installed {
        override val label: String get() = plugin.name
    }

    private class DexInstalled(val plugin: ApiPlugin) : Installed {
        override val label: String get() = plugin.name
    }

    private val installed = mutableMapOf<String, Installed>()
    private val disabledFile = File(root, "disabled.json")

    /**
     * 已安装插件的**运行期二进制兼容问题**：插件 id → 缺失的宿主契约成员。
     * 空列表 / 不在表里 = 兼容。见 [PluginBinaryCompat]。
     */
    private val compatIssues = mutableMapOf<String, List<String>>()

    /**
     * 已安装（磁盘上有）但**装载失败**的插件 id → 原因（activate 抛错、清单不兼容等）。
     * 这些插件没有贡献点，但必须让用户看到，否则更新宿主后它们会静默消失。
     */
    private val loadFailures = mutableMapOf<String, String>()

    /** 与内置插件同 id、因此未装载外部版本（内置优先）；插件页给「卸载外部版本」入口。 */
    private val shadowed = mutableSetOf<String>()

    /**
     * 插件 id → 清单 `version`（安装时解析并缓存）。供插件列表展示；
     * 停用 / 装载失败的插件也保留，因为它们不再有实例可读版本。
     */
    private val manifestVersions = mutableMapOf<String, String>()

    /**
     * 扫描根目录并安装全部「未禁用」的合法插件；返回「插件 id → 结果」的日志。
     * 目录（含 manifest.json）与 `.kplug` 包都会被发现。
     */
    fun discover(): Map<String, Result<String>> {
        if (!root.isDirectory) return emptyMap()
        val disabled = disabled()
        val results = mutableMapOf<String, Result<String>>()
        // 先清空再重建：否则用户手工删掉 plugins/<id> 后，「装载失败」条目会一直幽灵残留。
        loadFailures.clear()
        shadowed.clear()
        root.listFiles().orEmpty()
            .filter { it.isDirectory }
            // 没有 manifest.json 的目录不是插件（杂物目录、staging 残留），静默跳过；
            // 有清单但装失败的仍会出现在结果日志里。
            .filter { File(it, MANIFEST_FILE).isFile }
            .map { it.name }
            .filterNot { it in disabled }
            .sorted()
            .forEach { results[it] = install(it) }
        root.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(Kplug.EXTENSION) }
            .forEach { zip ->
                val peek = readManifest(zip)
                val id = peek.getOrNull()?.id ?: zip.name.removeSuffix(Kplug.EXTENSION)
                if (id in disabled) return@forEach
                // 同名目录版本已经装好时跳过：两趟扫描不互斥，重复 installFrom 会
                // deleteRecursively 掉刚装好、可能正在使用的目录再重装一遍。
                if (id in installed) {
                    results[id] = Result.success("已安装")
                    return@forEach
                }
                results[id] = peek.fold(
                    onSuccess = { installFrom(zip, id) },
                    onFailure = { Result.failure(it) },
                )
            }
        // 装载失败（旧版插件在 activate 里触发缺失方法、清单不兼容等）也记下来：
        // 插件没有贡献点、不在 installed 里，但必须能在插件页看到原因。
        results.forEach { (id, result) ->
            if (result.isSuccess) {
                loadFailures.remove(id)
            } else if (id !in installed) {
                loadFailures[id] = result.exceptionOrNull()?.message ?: "装载失败"
            }
        }
        return results
    }

    /** 已安装的动态插件 id（排序）。 */
    fun installedIds(): List<String> = installed.keys.sorted()

    /**
     * 已安装插件的运行期兼容性问题（缺失的契约成员）；空列表 = 兼容。
     * 宿主升级后由插件页据此渲染红色提示 —— 而不是等某次工具调用闪退。
     */
    fun compatIssues(id: String): List<String> = compatIssues[id].orEmpty()

    /** 磁盘上存在但装载失败的插件 id（排序）。 */
    fun failedIds(): List<String> = loadFailures.keys.sorted()

    /** 与内置插件同 id、未被装载的外部插件 id（排序）；插件页据此给「卸载外部版本」入口。 */
    fun shadowedIds(): List<String> = shadowed.sorted()

    /** 装载失败的原因（中文，可直接展示）。 */
    fun failureReason(id: String): String? = loadFailures[id]

    /**
     * 插件展示用版本号：优先取插件代码声明的 `Plugin.version`，其次清单 `version`
     * （会缓存）。停用 / 装载失败的插件也能读到；磁盘上没有清单时返回 null。
     */
    fun version(id: String): String? {
        (installed[id] as? DexInstalled)?.plugin?.version?.takeIf { it.isNotBlank() }?.let { return it }
        manifestVersions[id]?.let { return it }
        val manifestFile = File(File(root, id), MANIFEST_FILE)
        val fromDisk = runCatching {
            Json.decodeFromString<PluginManifest>(manifestFile.readText())
        }.getOrNull()?.version?.takeIf { it.isNotBlank() } ?: return null
        manifestVersions[id] = fromDisk
        return fromDisk
    }

    /** 插件根目录（`plugins/`）的绝对路径；UI 提示用户手动投放入口用。 */
    val rootPath: String get() = root.absolutePath

    /** 已禁用的插件 id（排序快照）。 */
    fun disabledIds(): List<String> = disabled().sorted()

    /**
     * 从工作区安装：[source] 可以是包含 manifest.json 的**目录**（agent 写插件的
     * 入口），也可以是一个 `.kplug` **包文件**（社区分发 / agent 下载）。
     * 宿主把它复制到 `plugins/<id>/` 再装载。
     *
     * [force] 为 true 时跳过兼容检查强制安装（用户自行承担风险）。
     */
    fun installFrom(source: File, id: String, force: Boolean = false): Result<String> {
        if (id.isBlank() || id.contains("..") || id.contains('/')) {
            return Result.failure(IllegalArgumentException("非法的插件目录名：$id"))
        }

        if (source.isFile && source.name.endsWith(Kplug.EXTENSION)) {
            val staging = File(root, ".staging-$id")
            Kplug.extract(source, staging).getOrElse { return Result.failure(it) }
            val manifest = readManifest(staging).getOrElse {
                staging.deleteRecursively()
                return Result.failure(it)
            }
            if (manifest.id != id) {
                staging.deleteRecursively()
                return Result.failure(
                    IllegalArgumentException("manifest.id（${manifest.id}）与请求的 id（$id）不一致"),
                )
            }
            val target = File(root, id)
            if (target.exists()) target.deleteRecursively()
            if (!staging.renameTo(target)) {
                if (!staging.copyRecursively(target, overwrite = true)) {
                    staging.deleteRecursively()
                    return Result.failure(IllegalArgumentException("移动插件目录失败：$staging → $target"))
                }
                staging.deleteRecursively()
            }
            return install(id, force)
        }

        if (!source.isDirectory) {
            return Result.failure(IllegalArgumentException("源目录不存在：$source"))
        }
        if (!File(source, MANIFEST_FILE).isFile) {
            return Result.failure(IllegalArgumentException("源目录缺少 $MANIFEST_FILE"))
        }
        val target = File(root, id)
        if (target.exists()) target.deleteRecursively()
        target.parentFile?.mkdirs()
        if (!source.copyRecursively(target, overwrite = true)) {
            return Result.failure(IllegalArgumentException("复制插件目录失败：$source → $target"))
        }
        return install(id, force)
    }

    /**
     * 安装用户选中的插件包（`.kplug` 文件或含 manifest.json 的目录）：
     * 先读清单取得 id，再走 [installFrom]。供 UI「安装 .kplug」入口使用——
     * 该路径是用户显式操作，不再经过 agent 侧 `install_plugin` 工具的审批闸门。
     */
    fun installPackage(source: File, force: Boolean = false): Result<String> {
        val manifest = readManifest(source).getOrElse { return Result.failure(it) }
        if (manifest.id.isBlank()) {
            return Result.failure(IllegalArgumentException("manifest.id 不能为空"))
        }
        return installFrom(source, manifest.id, force)
    }

    /**
     * 安装（或重载）`plugins/<id>/manifest.json`。
     * 先校验清单、兼容性与脚本/dex，失败即返回错误、不改动任何状态。
     *
     * [force] 为 true 时跳过兼容检查强制安装（用户自行承担风险）。
     */
    fun install(id: String, force: Boolean = false): Result<String> {
        if (id.isBlank() || id.contains("..") || id.contains('/')) {
            return Result.failure(IllegalArgumentException("非法的插件目录名：$id"))
        }
        val dir = File(root, id)
        val manifestFile = File(dir, MANIFEST_FILE)
        if (!manifestFile.isFile) {
            return Result.failure(IllegalArgumentException("缺少 $MANIFEST_FILE：plugins/$id/"))
        }

        val manifest = try {
            Json.decodeFromString<PluginManifest>(manifestFile.readText())
        } catch (e: Exception) {
            return Result.failure(IllegalArgumentException("manifest.json 解析失败：${e.message}"))
        }

        if (manifest.id.isBlank()) return Result.failure(IllegalArgumentException("manifest.id 不能为空"))
        if (manifest.id != id) {
            return Result.failure(IllegalArgumentException("manifest.id（${manifest.id}）与目录名（$id）不一致"))
        }
        // 版本先记下：后面即使兼容检查 / 装载失败，插件列表也要能显示版本。
        manifestVersions[id] = manifest.version.trim()

        // 内置优先：同名插件不装载外部版本；单独记成 shadowed（不算失败，插件页给卸载入口）。
        if (manifest.id in builtinPluginIds()) {
            shadowed += manifest.id
            loadFailures.remove(manifest.id)
            return Result.success("插件 ${manifest.id} 已内置：内置版优先，外部版本未装载")
        }

        // 兼容性检查：不兼容时，严格模式直接拒绝（UI 可据此弹「强制安装」）；
        // 非严格模式或用户强制时放行，结果里附告警。
        val incompatibilities = PluginCompatibility.check(
            manifest = manifest,
            builtinPluginIds = builtinPluginIds(),
        )
        if (incompatibilities.isNotEmpty() && !force && strictCompat()) {
            return Result.failure(PluginIncompatibleException(incompatibilities))
        }

        val outcome = when (manifest.kind) {
            PluginManifest.KIND_DEX -> installDex(id, dir, manifest)
            PluginManifest.KIND_SCRIPT -> installScript(id, dir, manifest)
            else -> Result.failure(IllegalArgumentException("未知 kind：${manifest.kind}"))
        }
        if (outcome.isSuccess) {
            loadFailures.remove(id)
            // 清单级不兼容（强制安装放行）与类级二进制问题合并展示，插件页一次看全。
            compatIssues[id] = (compatIssues[id].orEmpty() + incompatibilities).distinct()
        }
        return outcome.map { msg ->
            if (incompatibilities.isEmpty()) msg
            else "$msg（已跳过兼容检查：${incompatibilities.joinToString("；")}）"
        }
    }

    private fun installScript(id: String, dir: File, manifest: PluginManifest): Result<String> {
        compatIssues.remove(id) // 脚本插件没有代码，不存在类级兼容问题；清掉旧版本残留。
        manifest.tools.forEach { spec ->
            if (spec.name.isBlank()) return Result.failure(IllegalArgumentException("工具缺少 name"))
            if (spec.script.isBlank()) return Result.failure(IllegalArgumentException("工具 ${spec.name} 缺少 script"))
            if (!File(dir, spec.script).isFile) {
                return Result.failure(IllegalArgumentException("工具 ${spec.name} 的脚本不存在：${spec.script}"))
            }
        }

        // 重载：先卸载旧版本，避免残留贡献点。
        if (installed.containsKey(id)) uninstall(id)

        val plugin = DynamicPlugin(manifest, dir, skills)
        registry.install(plugin)
        installed[id] = ScriptInstalled(plugin)
        setDisabled(disabled() - id)
        resync()

        val parts = buildList {
            if (manifest.tools.isNotEmpty()) add("${manifest.tools.size} 个工具")
            if (manifest.prompts.isNotEmpty()) add("${manifest.prompts.size} 段系统提示")
            if (!manifest.skills.isNullOrBlank()) add("技能根 ${manifest.skills}")
        }
        val detail = if (parts.isEmpty()) "无贡献点" else parts.joinToString("、")
        return Result.success("已安装插件 $id（${manifest.name}）：$detail")
    }

    private fun installDex(id: String, dir: File, manifest: PluginManifest): Result<String> {
        if (manifest.entryClass.isNullOrBlank()) {
            return Result.failure(IllegalArgumentException("dex 插件缺少 entryClass"))
        }
        if (!File(dir, KplugLoader.DEX_FILE).isFile) {
            return Result.failure(IllegalArgumentException("缺少 ${KplugLoader.DEX_FILE}：plugins/$id/"))
        }
        if (manifest.tools.isNotEmpty() || manifest.prompts.isNotEmpty() || manifest.skills != null) {
            return Result.failure(
                IllegalArgumentException("dex 插件的清单不得声明 tools/prompts/skills（能力由 activate() 注册）"),
            )
        }

        // 重载：先卸载旧版本（旧类驻留内存，但贡献点会被回收）。
        if (installed.containsKey(id)) uninstall(id)
        compatIssues.remove(id) // 巡检结果按最新一次装载重算，避免旧结果残留。

        val apiPlugin = effectiveDexLoader.load(dir, manifest.entryClass).getOrElse { e ->
            return Result.failure(IllegalArgumentException("dex 插件装载失败：${e.message}", e))
        }
        // activate() 由插件代码执行，可能因宿主升级后类/方法不匹配而抛异常。
        // 不能让 Error（AbstractMethodError / NoSuchMethodError）冒到启动路径把应用带崩：
        // 收敛成「装载失败」，由插件页红字展示。
        val adapter = ApiPluginAdapter(apiPlugin)
        val activation = runCatching { registry.install(adapter) }
        val failure = activation.exceptionOrNull()
        if (failure != null) {
            registry.uninstall(adapter.id)
            compatIssues.remove(id)
            val detail = failure.message?.takeIf { it.isNotBlank() } ?: failure.javaClass.simpleName
            val hint = if (failure is AbstractMethodError || failure is NoSuchMethodError || failure is NoClassDefFoundError) {
                "插件与当前 Kair 版本不兼容（$detail），请更新插件"
            } else {
                "dex 插件激活失败：$detail"
            }
            return Result.failure(IllegalArgumentException(hint, failure))
        }
        installed[id] = DexInstalled(apiPlugin)
        setDisabled(disabled() - id)
        resync()
        // activate() 已完成：此刻才能做二进制兼容巡检 —— 已注册的贡献对象都在内存里。
        // 清单级不兼容已在 install() 融合进 compatIssues，这里补上类级缺失成员。
        compatIssues[id] = (compatIssues[id].orEmpty() + adapter.binaryIssues()).distinct()
        return Result.success("已安装代码插件 $id（${manifest.name}）：贡献点由 activate() 注册")
    }

    /** 卸载（禁用）一个动态插件；文件保留在磁盘上，重新 install 可恢复。 */
    fun uninstall(id: String): Boolean {
        val entry = installed.remove(id) ?: return false
        compatIssues.remove(id)
        loadFailures.remove(id)
        if (entry is ScriptInstalled) entry.plugin.deactivate()
        registry.uninstall(id)
        setDisabled(disabled() + id)
        resync()
        return true
    }

    /**
     * 删除一个动态插件：先卸载贡献点（若已装载），再删除磁盘上的插件目录 /
     * `.kplug` 包，并从 `disabled.json` 移除。**不可恢复** —— 与 [uninstall]
     * 的「停用」相对（停用只回收贡献点、保留文件、可重新 install 恢复）。
     * 返回是否确实删掉了东西（无论它此前是启用还是停用状态）。
     */
    fun delete(id: String): Boolean {
        if (id.isBlank() || id.contains("..") || id.contains('/')) return false
        compatIssues.remove(id)
        loadFailures.remove(id)
        shadowed.remove(id)
        manifestVersions.remove(id)
        if (installed.containsKey(id)) uninstall(id)
        val targets = listOf(File(root, id), File(root, "$id${Kplug.EXTENSION}"))
            .filter { it.exists() }
        // deleteRecursively 失败时不能报成功：文件还在，且 disabled 标记已被清掉，
        // 下次启动 discover 会把插件重新装回来，用户会以为「删了又自己出现」。
        targets.forEach { runCatching { it.deleteRecursively() } }
        if (targets.any { it.exists() }) {
            loadFailures[id] = "文件删除失败，可能被正在运行的进程占用"
            resync()
            return false
        }
        val wasDisabled = id in disabled()
        setDisabled(disabled() - id)
        resync()
        return targets.isNotEmpty() || wasDisabled
    }

    /**
     * 审批前预览一个待安装插件：读 [source]（目录或 .kplug 包）的清单，
     * 拼出「它会带来什么」。任何失败返回 null（调用方回退到通用摘要）。
     */
    fun previewInstall(source: File): String? {
        val manifest = readManifest(source).getOrNull() ?: return null
        return if (manifest.kind == PluginManifest.KIND_DEX) {
            "安装代码插件：${manifest.name}（${manifest.id}）——能力由代码声明" +
                "（入口 ${manifest.entryClass}），可注册工具或替换命令后端 / 存储 / 压缩策略"
        } else {
            val parts = buildList {
                if (manifest.tools.isNotEmpty()) {
                    add("工具 ${manifest.tools.joinToString("、") { it.name }}")
                }
                if (manifest.prompts.isNotEmpty()) add("${manifest.prompts.size} 段系统提示")
                if (!manifest.skills.isNullOrBlank()) add("技能根 ${manifest.skills}")
            }
            val detail = if (parts.isEmpty()) "无贡献点" else parts.joinToString("；")
            "安装脚本插件：${manifest.name}（${manifest.id}）——$detail"
        }
    }

    /** 审批前预览卸载：返回已装插件的名字，未安装返回 null。 */
    fun previewUninstall(id: String): String? =
        installed[id]?.let { "卸载插件：${it.label}（$id）" }

    /** 读清单：目录直读；.kplug 包先解到临时目录（读完即清）。 */
    private fun readManifest(file: File): Result<PluginManifest> = runCatching {
        when {
            file.isDirectory -> {
                val manifestFile = File(file, MANIFEST_FILE)
                require(manifestFile.isFile) { "缺少 $MANIFEST_FILE：$file" }
                Json.decodeFromString<PluginManifest>(manifestFile.readText())
            }
            file.isFile && file.name.endsWith(Kplug.EXTENSION) -> {
                val staging = File(root, ".staging-peek-${file.nameWithoutExtension}")
                try {
                    Kplug.extract(file, staging).getOrThrow()
                    val manifestFile = File(staging, MANIFEST_FILE)
                    require(manifestFile.isFile) { "插件包缺少 $MANIFEST_FILE" }
                    Json.decodeFromString<PluginManifest>(manifestFile.readText())
                } finally {
                    staging.deleteRecursively()
                }
            }
            else -> throw IllegalArgumentException("不是插件目录或 ${Kplug.EXTENSION} 包：$file")
        }
    }

    private fun disabled(): Set<String> = readDisabled(root)

    private fun setDisabled(ids: Set<String>) = writeDisabled(root, ids)

    companion object {
        const val MANIFEST_FILE = "manifest.json"

        /** 禁用清单文件（`plugins/disabled.json`）。 */
        fun disabledFile(root: File): File = File(root, "disabled.json")

        /** 读取被禁用（停用）的插件 id 集合；文件缺失 / 损坏时返回空集。 */
        fun readDisabled(root: File): Set<String> {
            val file = disabledFile(root)
            if (!file.isFile) return emptySet()
            return runCatching {
                Json.decodeFromString<Set<String>>(file.readText())
            }.getOrDefault(emptySet())
        }

        /** 写入被禁用的插件 id 集合。 */
        fun writeDisabled(root: File, ids: Set<String>) {
            root.mkdirs()
            disabledFile(root).writeText(Json.encodeToString(ids))
        }

        /**
         * 追加禁用若干插件（写 `disabled.json`）：供崩溃页在独立进程里直接落盘 ——
         * 下次启动 [discover] 不再装载它们。已崩溃进程无法走注册表卸载，只能改这份清单。
         */
        fun disable(root: File, ids: Set<String>) {
            if (ids.isEmpty()) return
            writeDisabled(root, readDisabled(root) + ids)
        }
    }
}
