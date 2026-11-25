package dev.kair.app.core.proot

import dev.kair.app.core.tools.CommandOutput
import dev.kair.app.core.tools.CommandRunner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 通道路由：U78 起只有 proot 一档（chroot 已删除，见 ADR 0007）。
 *
 * A-4 判据：`pick()` 为 proot，且本次路由**不探测 root** —— 状态机层面（`rootState` /
 * `probeResult` 保持未检测），不靠调用计数。
 */
class ContainerRunnerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeRunner(override val id: String) : CommandRunner {
        var calls = 0
        override val displayName = id
        override val description = id
        override val containerWorkspacePath: String? = "/projects"

        override suspend fun run(command: String, workspace: File, timeoutSeconds: Long?): CommandOutput {
            calls++
            return CommandOutput(id, 0)
        }
    }

    @Test
    fun `the router always picks proot and delegates every call`() = runBlocking {
        val proot = FakeRunner("proot-alpine")
        val router = ContainerRunner(proot)

        assertEquals("proot-alpine", router.id)
        assertEquals("proot-alpine", router.displayName)
        assertEquals("proot-alpine", router.description)
        assertEquals("/projects", router.containerWorkspacePath)

        val out = router.run("echo", File("/tmp"), null)

        assertEquals("proot-alpine", out.output)
        assertEquals(1, proot.calls)
    }

    @Test
    fun `routing never probes root`() = runBlocking {
        // 旧实现会在 chroot 档调 `ensureRootChecked()`（可能弹授权框）。现在路由不再持有
        // root 执行器；这条用例把「本轮不探测」钉在状态机上：探测过就一定有结论。
        val root = RootCommandRunner(
            manager = RootfsManager(File("/nonexistent"), "x86_64"),
            fallback = FakeRunner("local-fallback"),
            suLocator = { "/fake/su" },
            rootProbe = { RootProbeResult.GRANTED },
        )
        val router = ContainerRunner(FakeRunner("proot-alpine"))

        router.run("echo", File("/tmp"), null)

        assertNull("路由不得触发 root 探测", root.rootState.value)
        assertNull("路由不得留下探测结论", root.probeResult.value)
    }
}
