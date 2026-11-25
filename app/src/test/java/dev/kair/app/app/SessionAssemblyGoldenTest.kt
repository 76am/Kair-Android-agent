package dev.kair.app.app

import dev.kair.app.app.fakes.FakeSharedPreferences
import dev.kair.app.core.agent.CompactionSettings
import dev.kair.app.core.agent.RequestAssembler
import dev.kair.app.core.agent.SegmentKind
import dev.kair.app.core.ai.SamplingOptions
import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Part
import dev.kair.app.core.model.Role
import dev.kair.app.core.settings.SavedModel
import dev.kair.app.core.settings.SettingsStore
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * 会话装配「解析族」的**字节对拍** golden（U31 ③-a 的主判据）。
 *
 * 为什么不用逐语句对拍：`sessionModel` / `providerConfigFor` / `agentSettings` /
 * `toolEnabled` / `compactionSettings` / `resolveContextWindow` / `toolsetFingerprint`
 * 里有多条直接产出**请求前缀字节**；「代码搬过去长得一样」证明不了「发出去的字节一样」
 * （上一批就漏了一条没牙的判据）。这里把每例的产物写成**字段顺序显式钉死**的规范文本，
 * 再取 SHA-256 常量：搬家前后同一个测试文件一行不改，digest 必须逐字相同。
 *
 * 输入全部钉死：设置用 [FakeSharedPreferences]、工作区用固定字面量路径（不走临时目录 ——
 * 那个路径会经 `commandRunner` → `promptCwd` 进系统提示的 `<cwd>`，每次跑都不一样）。
 * 三个 private 成员（`compactionSettings` / `resolveContextWindow` / `toolsetFingerprint`）
 * 经反射取：容器搬家后仍保留**同名同签名**的 private 委托，所以反射点不必跟着改。
 *
 * 这条测试红了**不等于**行为错了：先看是哪个分组的 digest 变了，再看那一组里多了 /
 * 少了哪一段正文。修改期望值必须同时交代「为什么这次字节变化是有意的」。
 */
class SessionAssemblyGoldenTest {

    @Test
    fun `eight golden scenarios keep the request prefix bytes`() {
        // 钉值的前提：工作区字面量在两平台上逐字相等。做不到就先红在这里，
        // 别让人拿着一份平台专属的 digest 去猜「是不是搬家改坏了」。
        check(WORKSPACE.path.none { it == '/' || it == '\\' }) {
            "golden 的工作区字面量含路径分隔符（实际：${WORKSPACE.path}）—— " +
                "分隔符的形态属于平台（Windows 会把 /x/y 渲染成 \\x\\y），" +
                "带它去钉 digest 就等于把判据钉死在钉值那台机器的平台上。用单段相对名。"
        }
        val container = container()
        val settings = container.settings
        pinSettings(settings)
        val sink = Sink()

        // 1. 没有任何模型：默认 provider、非视觉 → read_image 不进 schema。
        record(sink, "1-no-model", container, null, "golden-fp-default")
        // 2. 视觉模型：read_image 进 schema（与 1 的 TOOLS / 指纹两组互为对照）。
        record(sink, "2-vision", container, model("golden-vision"), "golden-fp-vision")
        // 3. 自定义 provider 覆盖 transportId / baseUrl / apiKey。
        record(sink, "3-custom-provider", container, model("golden-custom"), "golden-fp-custom")
        // 4. 会话级覆盖 + 本次运行级覆盖：运行级必须压过会话级。
        record(
            sink,
            "4-session-override",
            container,
            requireNotNull(container.sessionModel("golden-session")),
            "golden-session",
        )
        record(
            sink,
            "4-run-override-wins",
            container,
            requireNotNull(container.sessionModel("golden-session", "golden-vision")),
            "golden-vision-session",
        )
        // 5. 子智能体思考强度覆盖：设置页的 high 叠到 reasoningLevels 上。
        record(sink, "5-subagent-reasoning", container, container.subagentModel(null), "golden-fp-custom")
        // 6. contextWindow 有值（2）与无值（这里）两条分支。
        record(sink, "6-context-window-fallback", container, model("golden-fallback"), "golden-fp-fallback")

        // 7. 压缩三项开 / 关。
        settings.autoCompaction = true
        settings.compactionReserveTokens = 5000
        settings.compactionKeepRecentTokens = 3000
        sink.add("compaction", "7-on|${compactionLine(container)}")
        settings.autoCompaction = false
        settings.compactionReserveTokens = 9000
        settings.compactionKeepRecentTokens = 7000
        sink.add("compaction", "7-off|${compactionLine(container)}")

        // 8. 指纹：同一份工具集在「未勾视觉 / 勾了视觉」两个会话里不同。
        sink.add("fingerprint", "8-explicit|golden-fp-default|${fingerprint(container, "golden-fp-default")}")
        sink.add("fingerprint", "8-explicit|golden-fp-vision|${fingerprint(container, "golden-fp-vision")}")

        // 一次跑出全部分组的差异：第一组失败就短路的话，M-c / M-d 会掩盖后面的 TOOLS / 指纹证据。
        val mismatches = mutableListOf<String>()
        fun check(label: String, expected: String, actual: String) {
            if (expected != actual) mismatches += "$label 不一致 expected=<$expected> actual=<$actual>"
        }
        check("provider 字段（M-a 的判据）", PROVIDER_DIGEST, sha256(sink.text("provider")))
        check("SYSTEM 段与装配头部（M-b 的判据）", SYSTEM_DIGEST, sha256(sink.text("system")))
        check("TOOLS 段（M-c / M-d 的判据）", TOOLS_DIGEST, sha256(sink.text("tools")))
        check("诊断条数与 id 清单", ISSUES_DIGEST, sha256(sink.text("issues")))
        check("四段 segment 的 kind / canonical", SEGMENTS_DIGEST, sha256(sink.text("segments")))
        check("压缩三项", COMPACTION_DIGEST, sha256(sink.text("compaction")))
        check("上下文窗口两例", WINDOW_DIGEST, sha256(sink.text("window")))
        check("工具集指纹（M-d 的判据）", FINGERPRINT_DIGEST, sha256(sink.text("fingerprint")))
        check("八例的整体 digest", GOLDEN_DIGEST, sha256(sink.canonical()))
        if (mismatches.isNotEmpty()) fail(mismatches.joinToString("\n"))
    }

    // ------------------------------------------------------------------ 夹具

    private fun container(): AppContainer {
        val tmp = Files.createTempDirectory("kair-golden").toFile()
        return AppContainer(
            AppEnvironment(storageRoot = File(tmp, "kair"), nativeLibraryDir = "/lib", primaryAbi = "arm64-v8a"),
            SettingsStore(FakeSharedPreferences()),
        )
    }

    private fun pinSettings(settings: SettingsStore) {
        settings.savedModels = listOf(DEFAULT_MODEL, VISION_MODEL, CUSTOM_MODEL, FALLBACK_MODEL)
        settings.activeModelId = DEFAULT_MODEL.id
        settings.subagentModelId = CUSTOM_MODEL.id
        settings.subagentReasoningEffort = "high"
        settings.maxSteps = 17
        settings.systemPromptAddendum = "GOLDEN-ADDENDUM"
        settings.setSessionModelId("golden-session", CUSTOM_MODEL.id)
        settings.setSessionModelId("golden-vision-session", VISION_MODEL.id)
        settings.setSessionModelId("golden-fp-default", DEFAULT_MODEL.id)
        settings.setSessionModelId("golden-fp-vision", VISION_MODEL.id)
        settings.setSessionModelId("golden-fp-custom", CUSTOM_MODEL.id)
        settings.setSessionModelId("golden-fp-fallback", FALLBACK_MODEL.id)
    }

    private fun model(id: String): SavedModel = when (id) {
        DEFAULT_MODEL.id -> DEFAULT_MODEL
        VISION_MODEL.id -> VISION_MODEL
        CUSTOM_MODEL.id -> CUSTOM_MODEL
        FALLBACK_MODEL.id -> FALLBACK_MODEL
        else -> error("golden 夹具里没有 $id")
    }

    private fun record(
        sink: Sink,
        scenario: String,
        container: AppContainer,
        model: SavedModel?,
        fingerprintSession: String,
    ) {
        val built = container.agentSettings(
            model,
            WORKSPACE,
            projectId = "golden-project",
            sessionId = "golden-diagnostics",
        )
        val config = container.providerConfigFor(model)
        sink.add(
            "provider",
            "$scenario|${config.providerId}|${config.transportId}|${config.baseUrl}|${config.apiKey}",
        )
        val s = built.settings
        sink.add("system", "$scenario|${s.model}|${s.maxSteps}|${s.replayThinking}|${sampling(s.sampling)}")
        sink.add("system", "$scenario|prompt|${s.systemPrompt}")
        sink.add(
            "issues",
            "$scenario|${built.issues.size}|" +
                built.issues.joinToString(";") { "${it.id}:${it.kind}:${it.detail.orEmpty()}" },
        )
        val request = RequestAssembler.assemble(
            model = s.model,
            systemPrompt = s.systemPrompt,
            messages = HISTORY,
            tools = container.tools.specs(container.toolEnabled(model)),
            options = s.sampling,
            sessionId = "golden-request",
            cache = true,
        )
        request.segments.forEach { sink.add("segments", "$scenario|${it.kind}|${it.canonical}") }
        request.segments.filter { it.kind == SegmentKind.TOOLS }
            .forEach { sink.add("tools", "$scenario|${it.canonical}") }
        sink.add("compaction", "$scenario|${compactionLine(container)}")
        val windowFromModel = privateCall(
            container,
            "resolveContextWindow",
            arrayOf(SavedModel::class.java to model, String::class.java to "golden-fallback"),
        ) as Int
        val windowFromName = privateCall(
            container,
            "resolveContextWindow",
            arrayOf(SavedModel::class.java to null, String::class.java to "deepseek-chat"),
        ) as Int
        sink.add("window", "$scenario|$windowFromModel|$windowFromName")
        sink.add("fingerprint", "$scenario|$fingerprintSession|${fingerprint(container, fingerprintSession)}")
    }

    private fun compactionLine(container: AppContainer): String {
        val c = privateCall(container, "compactionSettings") as CompactionSettings
        return "${c.enabled}|${c.reserveTokens}|${c.keepRecentTokens}"
    }

    private fun fingerprint(container: AppContainer, sessionId: String): String =
        privateCall(container, "toolsetFingerprint", arrayOf(String::class.java to sessionId)) as String

    /**
     * 取 private 成员：搬家前在 `AppContainer`，搬家后容器仍保留**同名同签名**的
     * private 委托 —— 所以这里写死 `AppContainer::class.java`，测试文件搬家前后不改。
     */
    private fun privateCall(
        target: Any,
        name: String,
        params: Array<Pair<Class<*>, Any?>> = emptyArray(),
    ): Any? {
        val method = AppContainer::class.java.getDeclaredMethod(
            name,
            *params.map { it.first }.toTypedArray(),
        )
        method.isAccessible = true
        return method.invoke(target, *params.map { it.second }.toTypedArray())
    }

    private fun sampling(o: SamplingOptions): String =
        "${o.temperature}|${o.maxTokens}|${o.reasoningEffort}|" +
            "${o.frequencyPenalty}|${o.presencePenalty}|${o.topP}|${o.seed}"

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    /** 按分组收集规范文本：每组一个 digest，整体再拼一个。 */
    private class Sink {
        private val groups = linkedMapOf<String, StringBuilder>()

        fun add(group: String, line: String) {
            groups.getOrPut(group) { StringBuilder() }.appendLine(line)
        }

        fun text(group: String): String = groups[group]?.toString().orEmpty()

        fun canonical(): String = groups.entries.joinToString("\n") { "${it.key}\n${it.value}" }
    }

    private companion object {
        /**
         * 固定字面量：这个路径会进系统提示的 `<cwd>`，不许用临时目录。
         *
         * **而且必须不含路径分隔符**（下面 `WORKSPACE_LETTER` 那条检查守着）：
         * `File("/kair-golden/ws").path` 在 Windows 上渲染成 `\kair-golden\ws`，
         * 评审实测（`9b90c5f`，两平台各跑一次并 diff 规范文本）—— 七个 `<cwd>` 就是
         * Linux 与 Windows 之间**唯一**的字节差异。那是路径分隔符的平台形态，
         * 不是装配产物变了；拿它去钉 digest 就把判据钉死在「钉值那一台机器的平台」上。
         * 单段相对名在两平台 `.path` 逐字相等，digest 因此是**真跨平台**的。
         */
        val WORKSPACE = File("kair-golden-ws")

        val DEFAULT_MODEL = SavedModel(
            id = "golden-default",
            name = "golden-default",
            providerId = "openai",
            model = "gpt-4.1",
        )

        val VISION_MODEL = SavedModel(
            id = "golden-vision",
            name = "golden-vision",
            providerId = "gemini",
            model = "gemini-2.0",
            contextWindow = 222_222,
            isVisionModel = true,
        )

        val CUSTOM_MODEL = SavedModel(
            id = "golden-custom",
            name = "golden-custom",
            providerId = "longcat",
            transportId = "anthropic-messages",
            baseUrl = "https://golden.example/v1",
            apiKey = "sk-golden",
            model = "golden-model",
            maxTokens = 1234,
            temperature = 0.25,
            topP = 0.9,
            frequencyPenalty = 0.1,
            presencePenalty = 0.2,
            seed = 7,
            isReasoningModel = true,
            reasoningLevels = listOf("low", "high"),
            thinkingLevelMap = mapOf("high" to "HIGH"),
            reasoningEffort = "low",
            replayThinking = false,
        )

        val FALLBACK_MODEL = SavedModel(
            id = "golden-fallback",
            name = "golden-fallback",
            providerId = "openai",
            model = "golden-mystery-model",
        )

        val HISTORY = listOf(
            ChatMessage(
                id = "golden-u1",
                role = Role.USER,
                parts = listOf(Part.Text("line one\nline two")),
            ),
            ChatMessage(
                id = "golden-a1",
                role = Role.ASSISTANT,
                parts = listOf(Part.Text("ack")),
                model = "golden-model",
            ),
        )

        // 钉值过程（评审方 2026-11-03 补记，见交接 §6.3）：
        // 这九个数是在 **搬家前**的树（`ea5c4e6`，容器 934 行）上钉的，且 **Windows 与 Linux 各钉一次、
        // 四格相同**（搬前 / 搬后 × Windows / Linux）；搬家后的树（`14d4200` 起）同一个文件继续给出一样的数。
        // 上一版把工作区写成 `File("/kair-golden/ws")`，Windows 实测把 `<cwd>` 渲染成
        // `\kair-golden\ws`，于是 SYSTEM / segments / overall 三组变成平台专属（其余六组两平台相同）——
        // 现在用单段相对名，判据回到「真跨平台」。
        // 2026-11-07 U58 重算 SYSTEM / TOOLS / SEGMENTS / FINGERPRINT / 整体五个 digest：
        // 新增 `compact_context` 工具进入工具列表与系统提示的「可用工具」段 —— 前缀字节**有意**
        // 变化一次（新增工具的唯一代价）；provider / issues / compaction / window 四组未动。
        // 2026-11-08 再重算同样五组：工具改名 (`bash` → `container_shell`、`local_shell` → `android_shell`)、
        // 描述与守则同步改写、能力段补 `android_shell` 一句 —— 前缀字节**有意**变化一次（改名的代价）。
        // 2026-11-08 U64 重算 SYSTEM / SEGMENTS / 整体三组：记忆插件内置化新增 order=25 的记忆段
        // （空工作区正文 =「## 跨会话记忆 / （当前无记忆）/ 维护规则…」）—— 前缀字节**有意**变化一次；
        // TOOLS / PROVIDER / ISSUES / COMPACTION / WINDOW / FINGERPRINT 六组逐字未动（工具与诊断无变化）。
        // 2026-11-12 U78（删除 chroot 会话通道）重算 SYSTEM / SEGMENTS / 整体三组：
        // 能力段去掉「proot or chroot」与「under chroot it lands in the rootfs」，`container_shell`
        // 守则去掉按通道分叉的两句、只留 proot 单档 —— 前缀字节**有意**变化一次（删通道的代价）。
        // TOOLS / PROVIDER / ISSUES / COMPACTION / WINDOW / FINGERPRINT 六组逐字未动（schema 与诊断无变化）。
        // 2026-11-15（U91）：新增内置工具 `device_shell`（ADB 插件的设备 shell），工具清单进入
        // 缓存稳定前缀，故 SYSTEM / TOOLS / SEGMENTS / FINGERPRINT / 整体五组 digest 一次性变化是
        // **有意的**。判据：PROVIDER / ISSUES / COMPACTION / WINDOW 四组**未变**——说明变动的只有
        // 工具清单带来的那一段，别的东西没被顺手改坏。
        // 2026-11-15（U91 续）：再重算同样五组 —— 新增 `device_screen`（唯一一个能回图的设备工具，
        // 其余设备能力都走 `device_shell`），工具清单多一段。四组仍然逐字未动，同一条判据成立。
        // 2026-11-16（U91 续二）：只改 `device_shell` 的文案，重算 SYSTEM / TOOLS / SEGMENTS / 整体
        // 四组。判据这一次是反向的：**FINGERPRINT 逐字未动**——工具集合一个没增没减，变的只是同一
        // 个工具的描述与守则；PROVIDER / ISSUES / COMPACTION / WINDOW 同样未动。
        // 2026-11-16 U92：容器 rootfs 换 Ubuntu，模型可见工具名 alpine_shell → container_shell，
        // 工具描述、system prompt 与守则同步 Alpine → Ubuntu。SYSTEM / TOOLS / SEGMENTS 与整体
        // digest 因此**有意变更**；provider / issues / compaction / window / fingerprint 未变，
        // 证明改动只落在工具与提示文本。
        // 2026-11-19 提示词审计：删掉 proot 时代的 root / umask / chattr 叙述（chroot 档已删除），
        // 并把「同一事实写三遍」的工具守则合并（container_shell / background_task / subagent /
        // device_shell / edit）。SYSTEM / TOOLS / SEGMENTS / 整体四组 digest **有意**变化；
        // PROVIDER / ISSUES / COMPACTION / WINDOW / FINGERPRINT 未变 —— 改动只落在提示词文本。
        // 2026-11-21 图形桌面能力段：能力自述补一条**常量**事实（`DISPLAY=:1` / `scrot` /
        // `read_image`），桌面启停状态不进正文，前缀只在这一版变一次。SYSTEM / SEGMENTS / 整体
        // 三组 digest **有意**变化；PROVIDER / TOOLS / ISSUES / COMPACTION / WINDOW / FINGERPRINT
        // 六组逐字未动 —— 判据与上次相同：变的只有提示文本，没有顺手改坏别的分组。
        // 2026-11-21 手机操控规则 + 临时调试显示：`<rules>` 新增手机边界；能力段明确 `:1` 归
        // 「图形桌面」面板，另允许 agent 用 background_task 起临时 `Xvfb :2` 做无头调试。
        // SYSTEM / SEGMENTS / 整体三组 digest **有意**变化；TOOLS 逐字未动。
        // 2026-11-21 Agent Dev Browser P1：新增 8 个 `browser_*` 工具（open / snapshot / click /
        // type / press / scroll / console / network），工具清单进入缓存稳定前缀。TOOLS / SEGMENTS /
        // FINGERPRINT / 整体四组 digest **有意**变化；SYSTEM（工具清单不在系统提示正文里）/
        // PROVIDER / ISSUES / COMPACTION / WINDOW 逐字未动 —— 证明变动只落在工具集合。
        // 2026-11-21 浏览器首轮缺陷修复：再加 browser_screenshot / browser_eval 两个工具。
        // 同样只动 TOOLS / SEGMENTS / FINGERPRINT / 整体；SYSTEM 等五组未变。
        // 2026-11-22 browser 子智能体：10 个 `browser_*` 工具从主会话工具面移出（只保留
        // `subagent` 入口，由 `browser` 子智能体携带 browser_* + read / read_image）。
        // TOOLS / SEGMENTS / FINGERPRINT / 整体四组 digest **有意**变化；SYSTEM / PROVIDER /
        // ISSUES / COMPACTION / WINDOW 六组逐字未动 —— 证明变动只落在工具集合，
        // 不是装配或提示文本被顺手改坏。
        // 2026-11-22 容器路径统一：`ContainerFileBackend.resolve` 把 `/projects/...` 映射回
        // 工作区父目录，`read_image` 文案同步说「容器会话也认」。只改工具文本，
        // TOOLS / SEGMENTS / 整体三组 **有意**变化；FINGERPRINT 与其余五组未变。
        // 2026-11-23 删除「Linux 端口」面板：能力段去掉「the "Linux 端口" panel only lists
        // listeners」一句。SYSTEM / SEGMENTS / 整体三组 digest **有意**变化；
        // TOOLS / PROVIDER / ISSUES / COMPACTION / WINDOW / FINGERPRINT 逐字未动。
        const val GOLDEN_DIGEST = "9f88f2d815313a273ec172e07950c3e4dfb4f68593f22dab977523811a50c4c6"
        const val PROVIDER_DIGEST = "968eb5b0fd75d86dbd89f2203e9fd682792532779dfed7c3d782bd8ebf199671"
        const val SYSTEM_DIGEST = "8bbf1f5018e867ff79f0e32f484a495d733f85efc6ac369961faef69d32340a3"
        const val TOOLS_DIGEST = "ec8d6b16d563c495dd9e44082bd08ecb54c3f22994ee331ede5bdf446cde3d84"
        const val ISSUES_DIGEST = "0299cbb3d7059627dd90883b9d12deb4775a00d21fb6984159fe7e578da2231b"
        const val SEGMENTS_DIGEST = "12a33df6a94b8bf3b6a082640c167b2a84b14e3268f2e9c33bc094a4d8550b19"
        const val COMPACTION_DIGEST = "1d8432601c998bfaca7ccccb4d433e95667c8d73f48f2ac3034bca9d93993c43"
        const val WINDOW_DIGEST = "34245a672b82ac881c29de7f1927e16571343dfc8ef458570ab127b214b107ea"
        const val FINGERPRINT_DIGEST = "a30123ae81e7f8b382a6979f300cc24fe388aaab04f62b8553e3a84e1a9e5756"
    }
}
