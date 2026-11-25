package dev.kair.app.core.mcp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * stdio 服务器拿不到工作区时必须**当场失败**，不许退回临时目录。
 *
 * 这条分支不是防御性死代码：`stdioWorkspaceReady()` 与真正读工作区是两次独立的读，
 * 中间会话可能刚好被关掉 —— 那就是「进程 cwd 落到临时目录、模型在空目录里干活」的场景。
 * 判据是状态里出现 `Error` 且原因可读，而不是「看起来不会发生」。
 */
class McpManagerWorkspaceTest {

    @Test
    fun `stdio server without a workspace fails instead of using a temp dir`() = runBlocking {
        val cfg = McpServerConfig(
            id = "srv-1",
            name = "srv",
            transport = McpTransportConfig.Stdio(command = "echo"),
        )
        val manager = McpManager(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            configsProvider = { listOf(cfg) },
            saveConfigs = {},
            processHost = McpProcessHost(),
            // 就绪判定说「可以连」，但真到取工作区时已经没有会话了（会话刚被关掉）。
            workspace = { null },
            stdioWorkspaceReady = { true },
            onToolsChanged = {},
        )
        try {
            manager.reconcile()
            val status = withTimeout(5_000) {
                manager.status.first { it[cfg.id] is McpServerStatus.Error }
            }
            val error = status.getValue(cfg.id) as McpServerStatus.Error
            assertTrue("失败原因必须可读（实际：${error.message}）", error.message.contains("请先打开一个会话"))
        } finally {
            manager.closeAll()
        }
    }
}
