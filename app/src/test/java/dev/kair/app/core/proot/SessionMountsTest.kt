package dev.kair.app.core.proot

import dev.kair.app.core.tools.LocalCommandRunner
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 共享终端的「挂载清单 → 命令文本」这一层（C1）。
 *
 * proot 的 `-b` 是进程内模拟绑定；维护脚本（`RootCommandRunner.buildScript`）仍消费同一份
 * 清单（U78 起不再有会话通道，见 ADR 0007）。这里钉的是**生成的文本**：工作区父目录挂到
 * `/projects`、不再出现旧的单工作区固定点 `/workspace`。
 * 目录命名（项目名 → 子目录名）的用例在 `core/storage/ProjectWorkspaceNamingTest`。
 */
class SessionMountsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun runner(): RootCommandRunner = RootCommandRunner(
        manager = RootfsManager(tmp.newFolder("rootfs"), "x86_64"),
        fallback = LocalCommandRunner("/bin/sh"),
    )

    @Test
    fun `maintenance mount script mounts the projects root and no workspace`() {
        val base = tmp.newFolder("workspaces")
        val mounts = projectsMount(base)
        val script = runner().buildScript(mounts, entry = RootCommandRunner.SESSION_ENTRY)

        val line = "try_mount projects ${RootCommandRunner.shellQuote(base.absolutePath)}"
        assertTrue("缺少父目录挂载：$line", script.contains(line))
        // 挂载段必须在 chroot 之前（挂完才进容器），否则容器里看不到项目
        assertTrue(script.indexOf("try_mount projects") < script.indexOf("chroot \"\$R\""))
        // 不再挂单个工作区固定点
        assertFalse(script.contains("try_mount workspace"))
    }

    @Test
    fun `entry script starts in the projects directory`() {
        val script = ContainerSession.entryScript()

        assertTrue(script.contains("cd $PROJECTS_ROOT 2>/dev/null || cd /"))
        // 起始目录不再是某一个项目的工作区
        assertFalse(script.contains("/workspace"))
    }
}
