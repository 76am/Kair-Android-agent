package dev.kair.app.app

import dev.kair.app.core.agent.BuiltinPromptSections
import dev.kair.app.core.agent.SystemPromptBuilder
import dev.kair.app.core.agent.SystemPromptContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P3-3B step5：系统提示 `cwd` 段的**双路径 + 字节对照**。
 *
 * 为什么要单独一条：`cwd` 进缓存前缀，改一个字节就会作废会话此前的前缀缓存；
 * 而它有两条路径 —— 容器生效时是容器内的 `/projects/<目录名>`（宿主上不存在这个路径），
 * 不生效时是宿主真实工作区。**只验一条等于没验**，这正是 A 段被要求「不代做」的原因。
 *
 * 判据分三层：
 * 1. 解析层：[promptCwd] 的取值（容器优先，否则宿主路径）；
 * 2. 段落层：装配出的正文里 `<cwd>` / `<workspace>` 段**逐字节**符合契约；
 * 3. 确定性：同一逻辑状态连装两次逐字节相同（缓存安全）。
 *
 * 容器路径的「不经容器就跑不通」由真机 T1–T4 覆盖（`docs/tasks/K1-…`），这里只钉装配字节。
 */
class PromptWiringTest {

    private val host = File("/data/user/0/dev.kair.app/files/kair/projects/workspaces/p-1")

    @Test
    fun `cwd is the container path when a container runner is present`() {
        val cwd = promptCwd("/projects/p-1", host)
        assertEquals("容器生效时 cwd 必须是容器内工作区真实路径", "/projects/p-1", cwd)

        val text = assembly(cwd)
        // 容器生效时同时给出宿主真实路径（顺序固定：先 workspace 再 cwd），
        // 否则模型会把容器路径当成 App 能直接读的路径。两段之间是映射规则
        // （X2：警告贴着数据源），所以不再逐字节断言「两段紧邻」，改为三段式。
        assertTrue(
            "workspace 段字节不符合契约：$text",
            text.contains("<workspace>\n${host.path}\n</workspace>"),
        )
        assertTrue(
            "workspace 与 cwd 之间必须夹映射规则（container_shell 永远用 <cwd> 前缀）：$text",
            text.contains("Never pass the host path to container_shell"),
        )
        assertTrue(
            "cwd 段字节不符合契约：$text",
            text.contains("<cwd>\n/projects/p-1\n</cwd>"),
        )
        // 顺序断言用「带内容的段开头」做锚点：映射句文本里也含 <workspace>/<cwd> 字面量，
        // 裸 indexOf 会撞上它（本用例首跑即红）。
        val wsAt = text.indexOf("<workspace>\n${host.path}")
        val mapAt = text.indexOf("Never pass")
        val cwdAt = text.indexOf("<cwd>\n/projects/p-1")
        assertTrue("顺序固定：先 workspace（含映射）再 cwd", wsAt < mapAt && mapAt < cwdAt)
        assertFalse("容器生效时 cwd 不得退回宿主路径", text.contains("<cwd>\n${host.path}\n</cwd>"))
    }

    @Test
    fun `cwd is the host path without a container runner`() {
        val cwd = promptCwd(null, host)
        assertEquals("没有容器时 cwd 必须是宿主真实路径", host.path, cwd)

        val text = assembly(cwd)
        assertTrue("宿主路径的段落字节不符合契约：$text", text.contains("<cwd>\n${host.path}\n</cwd>"))
        assertFalse("两值相同时不该多出 <workspace> 段", text.contains("<workspace>"))
    }

    @Test
    fun `same logical state yields the same bytes`() {
        val first = assembly(promptCwd("/projects/p-1", host))
        val second = assembly(promptCwd("/projects/p-1", host))
        assertEquals("同一逻辑状态必须逐字节相同（缓存前缀契约）", first, second)
    }

    /** 走真实装配路径（内置段落全集），只固定 cwd 这一个变量。 */
    private fun assembly(cwd: String): String {
        SystemPromptBuilder.invalidate()
        return SystemPromptBuilder.build(
            SystemPromptContext(cwd = cwd, tools = emptyList(), addendum = "", workspace = host.path),
            BuiltinPromptSections.contributors,
        )
    }
}
