package dev.kair.app.ui.plugins.builtin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FsPolicyTest {

    private val policy = FsPolicy(
        cap = "/data/kair",
        manageable = listOf("/data/kair/workspaces/p1", "/data/kair/trash"),
        sessionLogs = "/data/kair/sessions/sessions",
    )

    @Test
    fun `session logs are deletable but not renameable`() {
        val log = "/data/kair/sessions/sessions/abc.jsonl"
        assertTrue(policy.isSessionLog(log))
        assertTrue(policy.canDelete(log))
        // 改名 / 移动会让索引指向不存在的文件名，继续禁止。
        assertFalse(policy.canModify(log))
        // 索引文件与会话日志目录本身仍受保护，避免一次手滑清空所有会话。
        assertFalse(policy.canDelete("/data/kair/sessions/sessions.json"))
        assertFalse(policy.canDelete("/data/kair/sessions/sessions"))
        assertFalse(policy.isSessionLog("/data/kair/sessions/sessions/abc.txt"))
    }

    @Test
    fun `workspace entries keep full modify rights`() {
        val file = "/data/kair/workspaces/p1/src/a.kt"
        assertTrue(policy.canModify(file))
        assertTrue(policy.canDelete(file))
        // 工作区根自身仍只读（删了会让整个项目失去工作目录）。
        assertFalse(policy.canDelete("/data/kair/workspaces/p1"))
    }
}
