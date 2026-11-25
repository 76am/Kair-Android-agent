package dev.kair.app.core.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 常驻会话的纯逻辑部分：帧协议解析、容器内循环脚本生成。
 * 真实 su / proot / WebView 不在 JVM 单测范围（需 Android 设备 + 原生库）。
 */
class ContainerSessionTest {

    @Test
    fun `frame parses ready and exit markers`() {
        assertEquals(ContainerSession.Frame.Ready, ContainerSession.frame("__KAIR_READY__"))
        assertEquals(ContainerSession.Frame.Exit(0), ContainerSession.frame("__KAIR_RC_0__"))
        assertEquals(ContainerSession.Frame.Exit(130), ContainerSession.frame("__KAIR_RC_130__"))
    }

    @Test
    fun `frame ignores ordinary and malformed output`() {
        assertNull(ContainerSession.frame("hello world"))
        // 空退出码 / 非数字退出码 / 不是行首的伪帧：都不当作帧
        assertNull(ContainerSession.frame("__KAIR_RC___"))
        assertNull(ContainerSession.frame("__KAIR_RC_ab__"))
        assertNull(ContainerSession.frame("prefix __KAIR_RC_0__"))
    }

    @Test
    fun `entry script echoes frames and evals commands in the main shell`() {
        val script = ContainerSession.entryScript()

        // 就绪 / 停止 / 退出码三种帧
        assertTrue(script.contains("printf '%s\\n' '__KAIR_READY__'"))
        assertTrue(script.contains("if [ \"\$__kair_line\" = '__KAIR_STOP__' ]"))
        assertTrue(script.contains("printf '%s%s%s\\n' '${ContainerSession.RC_PREFIX}' \"\$?\" '${ContainerSession.RC_SUFFIX}'"))
        // 逐行读命令，且在**主 shell 里 eval**（cd / 变量跨命令保留，像真电脑）
        assertTrue(script.contains("while IFS= read -r __kair_line"))
        assertTrue(script.contains("eval \"\$__kair_line\" </dev/null"))
        // 起始工作目录 = 全部项目的父目录（共享终端，与「当前项目」无关）
        assertTrue(script.contains("cd /projects 2>/dev/null || cd /"))
        // root 新建文件对 App 可读写（与一次性命令同一 umask 约定）
        assertTrue(script.contains("umask 000"))
        assertTrue(script.indexOf("umask 000") < script.indexOf("eval "))
    }

    @Test
    fun `rc frame printed by the entry script is recognised by the parser`() {
        // 回归：脚本一度只打印 `__KAIR_RC_<code>`（漏了后缀 `__`），而 frame() 要求
        // `__KAIR_RC_<code>__` —— 退出帧永不命中，exec() 会一直挂到 stop() 强行完成
        // pending（表现为「端口要等停止会话后才刷出来」）。
        val script = ContainerSession.entryScript()
        assertTrue(script.contains("printf '%s%s%s\\n' '${ContainerSession.RC_PREFIX}' \"\$?\" '${ContainerSession.RC_SUFFIX}'"))

        // 按脚本的打印格式拼出真实输出行，必须被解析成退出帧
        val rcLine = "${ContainerSession.RC_PREFIX}0${ContainerSession.RC_SUFFIX}"
        assertEquals(ContainerSession.Frame.Exit(0), ContainerSession.frame(rcLine))
    }
}
