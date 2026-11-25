package dev.kair.app.core.tools

import dev.kair.app.core.device.AdbOutcome
import dev.kair.app.core.device.ScreenOutcome
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具提示词预算守卫（U91 提交 6）。
 *
 * 为什么要它：工具的 `name` / `description` / `promptSnippet` / `promptGuidelines` **全部进
 * 系统提示的缓存稳定前缀**（`SystemPrompt.kt` 把摘要列进「可用工具」段、把守则并进规则段），
 * 也就是说它们不是写给人看的文档，而是**每次请求都要付一遍的固定成本**。一个工具的守则多写
 * 200 字符，等于每次请求多付约 50 token，而写的人几乎感觉不到 —— 这种膨胀只有守卫能拦。
 *
 * 判据三条，任一红都要先算账再动手：
 * 1. **登记**：本守卫能实例化的每个工具都必须在 [budget] 有一行。新增工具时你必须来量一次数，
 *    这个动作本身就是「意识到自己在加常驻成本」。
 * 2. **只准减**：实测超出登记值即红。要加守则，先从同一个工具里删掉等量 ——
 *    与 `UiTextGuardTest` 的「只降不增」同一套口径。
 * 3. **总量**：全表合计不超过 [TOTAL_BUDGET]。前两条管单个，这条管堆叠。
 *
 * 范围**只含** `builtinTools()` 与两个设备工具 —— 只有它们能在 JVM 里直接构造。插件贡献的
 * 工具（subagent / mcp / workspace 等）要 Android `Context`，不在本守卫内：别把这里的绿
 * 当成全部工具的账。
 */
class ToolPromptBudgetGuardTest {

    /**
     * 登记表：工具名 → 允许达到的**提示词字符数**（`name + description + promptSnippet +
     * promptGuidelines`，登记时的实测值）。低于它不报错，但请把数字改小 —— 预算只该往一个
     * 方向走，改大数字就是一次显式的「我知道自己在加成本」，评审看得见。
     *
     * 2026-11-15 基线（11 个工具合计 8305）：`container_shell` 2884 是最大头（容器 shell 边界
     * 复杂，7 条守则占 1929），其后 `device_shell` 1448（三条必踩的坑另算 808）。
     * 2026-11-19 提示词审计（实测 7652）：把「同一事实写三遍」的守则合并、删掉 proot 时代的
     * root 叙述后，`container_shell` 2884 → 2398、`edit` 737 → 639、`device_shell` 1448 → 1395。
     * `container_shell` 保留了一句守卫要求的 `KAIR_CHANNEL` 自查说明，因此比纯删减版多 108。
     * 其余项与实测值对齐，数字只往下走。
     */
    private val budget: Map<String, Int> = mapOf(
        "read" to 282,
        "write" to 207,
        "edit" to 639,
        "container_shell" to 2398,
        "android_shell" to 1005,
        "ls" to 209,
        "grep" to 237,
        "find" to 195,
        "read_image" to 370,
        "device_shell" to 1395,
        "device_screen" to 715,
    )

    /** 守卫能实例化的工具。设备工具用假 runner —— 这里只关心提示词，不碰 Android。 */
    private fun sampleTools(): List<Tool> = builtinTools() + listOf(
        DeviceShellTool { AdbOutcome.Ok("") },
        DeviceScreenTool { ScreenOutcome.Failed(AdbOutcome.Reason.IO) },
    )

    private fun promptChars(tool: Tool): Int =
        tool.name.length + tool.description.length +
            tool.promptSnippet.length + tool.promptGuidelines.sumOf { it.length }

    @Test
    fun `every shipped tool is registered`() {
        val shipped = sampleTools().map { it.name }.toSet()
        val missing = shipped - budget.keys
        val stale = budget.keys - shipped
        assertTrue(
            "有工具没登记提示词预算：$missing —— 用 promptChars 量一次，然后写进 budget。",
            missing.isEmpty(),
        )
        assertTrue(
            "budget 里有已经不存在的工具：$stale —— 工具删了就把那一行一起删掉。",
            stale.isEmpty(),
        )
    }

    @Test
    fun `no tool exceeds its registered budget`() {
        val over = sampleTools()
            .filter { promptChars(it) > budget.getValue(it.name) }
            .map { "${it.name}: 实测 ${promptChars(it)} > 登记 ${budget.getValue(it.name)}" }
        assertTrue(
            "工具提示词超出预算（这是每次请求都付的缓存前缀成本）：\n" +
                over.joinToString("\n") +
                "\n要加内容，先从同一个工具里删掉等量的；确实要加就跟 budget 的数字一起改，别只改数字。",
            over.isEmpty(),
        )
    }

    @Test
    fun `total prompt budget`() {
        val total = sampleTools().sumOf { promptChars(it) }
        assertTrue(
            "工具提示词合计 $total 超出总预算 $TOTAL_BUDGET。" +
                "余量只够再放一个最小的工具（ls 209 / find 195）—— 加第二个之前必须先减。",
            total <= TOTAL_BUDGET,
        )
    }

    private companion object {
        /**
         * 总预算。2026-11-19 实测 7652，留 208 —— 刚好一个最小工具的余量，意思是
         * 「还能加最后一个，再想加就得先腾」。这不是目标值，是天花板；相对 2026-11-15 的
         * 8500 已下调 640。
         */
        const val TOTAL_BUDGET = 7860
    }
}
