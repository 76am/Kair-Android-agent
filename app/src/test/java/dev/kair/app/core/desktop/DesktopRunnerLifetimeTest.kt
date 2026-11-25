package dev.kair.app.core.desktop

import dev.kair.app.core.proot.ContainerSessionHost
import dev.kair.app.ui.plugins.builtin.DesktopPlugin
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U86 Blocker 3 判据（审阅 §2.3）：桌面 runner 必须归 **AppContainer / 容器会话层**，
 * 不能挂在插件实例上 —— `builtinPlugins()` 每次调用都 new 一批 Plugin，
 * 禁用→启用后旧实例连同 `process` 句柄一起丢，旧桌面成孤儿、「停止桌面」失效。
 *
 * 结构判据（反射），不启动真进程：JVM 上就能钉住「谁持有句柄」。
 */
class DesktopRunnerLifetimeTest {

    @Test
    fun `desktop plugin must not own the runner handle`() {
        val owned = DesktopPlugin::class.java.declaredFields
            .filter { !it.isSynthetic }
            .map { it.type.name }
        assertFalse(
            "DesktopPlugin 不得持有 DesktopRunner 字段：插件实例会被 builtinPlugins() 重建，" +
                "句柄随之丢失 → 孤儿进程 + 唯一停口失效。runner 归 AppContainer 级单例。",
            owned.any { it.endsWith("DesktopRunner") },
        )
    }

    @Test
    fun `container session host owns the desktop runner`() {
        val field = ContainerSessionHost::class.java.declaredFields.firstOrNull { it.name == "desktop" }
        assertTrue(
            "ContainerSessionHost 必须持有 desktop（与 session / pty 并列的稳定载体），" +
                "实际字段：${ContainerSessionHost::class.java.declaredFields.map { it.name }}",
            field != null,
        )
        assertTrue(
            "desktop 字段必须是 DesktopRunner（而不是把它又放回某个会重建的实例里）",
            field!!.type.name.endsWith("DesktopRunner"),
        )
    }

    @Test
    fun `audio sink belongs to the session host not the desktop runner`() {
        // 审阅回执二 §3：音频是容器级能力，`.asoundrc` 整个容器都用。绑在 DesktopRunner 上
        // 会回到「不开桌面 / 停桌面 / 覆盖安装 → 静默失声」的老问题。
        val desktopFields = DesktopRunner::class.java.declaredFields
            .filter { !it.isSynthetic }
            .map { it.type.name }
        assertFalse(
            "DesktopRunner 不得持有 SessionAudioSink：接收端随会话起停，不随桌面",
            desktopFields.any { it.endsWith("SessionAudioSink") },
        )
        val audio = ContainerSessionHost::class.java.declaredFields.firstOrNull { it.name == "audioSink" }
        assertTrue(
            "ContainerSessionHost 必须持有 audioSink，实际字段：${ContainerSessionHost::class.java.declaredFields.map { it.name }}",
            audio != null && audio.type.name.endsWith("SessionAudioSink"),
        )
    }
}
