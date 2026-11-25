package dev.kair.app.ui.plugins.builtin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 删除类命令识别（审批插件默认拦截的判定依据）。 */
class DangerCommandTest {

    private companion object {
        /** 解析相对操作数用的工作区基准目录。 */
        const val WS = "/workspace/demo"
    }

    @Test
    fun `flags rm in its common forms`() {
        assertEquals("删除命令", DangerCommand.match("rm -rf build"))
        assertEquals("删除命令", DangerCommand.match("rm file.txt"))
        assertEquals("删除命令", DangerCommand.match("cd /tmp && rm -rf *"))
        assertEquals("删除命令", DangerCommand.match("find . -name '*.log' | xargs rm"))
        assertEquals("删除命令", DangerCommand.match("/bin/rm -rf /tmp/x"))
        assertEquals("删除命令", DangerCommand.match("busybox rm -rf /tmp/x"))
    }

    @Test
    fun `flags rm hidden behind escapes or quotes`() {
        // shell 等价写法：正则的前导分隔符匹配不到 `\` / 引号，必须归一化后再判定。
        assertEquals("删除命令", DangerCommand.match("\\rm -rf build"))
        assertEquals("删除命令", DangerCommand.match("'rm' -rf build"))
        assertEquals("删除命令", DangerCommand.match("\"rm\" -rf build"))
        assertEquals("删除命令", DangerCommand.match("r''m -rf build"))
        assertEquals("删除命令", DangerCommand.match("cd /tmp && \\rm -rf *"))
    }

    @Test
    fun `flags the other delete binaries`() {
        assertEquals("删除命令", DangerCommand.match("rmdir olddir"))
        assertEquals("删除命令", DangerCommand.match("unlink a.bin"))
        assertEquals("删除命令", DangerCommand.match("shred -u secret.txt"))
    }

    @Test
    fun `flags app-level deletion through device_shell`() {
        // U91：`device_shell` 能直接跑 pm，不拦就等于给「卸载应用」开了后门。
        assertEquals("删除命令", DangerCommand.match("pm uninstall com.example.app"))
        assertEquals("删除命令", DangerCommand.match("pm uninstall --user 0 com.example.app"))
        assertEquals("删除命令", DangerCommand.match("pm clear com.example.app"))
        assertEquals("删除命令", DangerCommand.match("cmd package uninstall com.example.app"))
        assertEquals("删除命令", DangerCommand.match("adb=1; pm uninstall com.example.app"))
        // 走 commandReason 时包里没有可解析的路径目标，落进「保守拦」分支而不是被临时目录豁免掉。
        assertEquals("删除命令", DangerCommand.commandReason("pm uninstall com.example.app", WS))
    }

    @Test
    fun `does not flag reading package state or stopping an app`() {
        assertNull(DangerCommand.match("pm list packages -3"))
        assertNull(DangerCommand.match("pm list packages -3 | grep example"))
        assertNull(DangerCommand.match("dumpsys package com.example.app"))
        // 刻意不拦：force-stop 只是停进程，重启即恢复，而模型操作 UI 时常要靠它重启应用。
        assertNull(DangerCommand.match("am force-stop com.example.app"))
    }

    @Test
    fun `flags find -delete`() {
        assertEquals("find -delete", DangerCommand.match("find . -name '*.tmp' -delete"))
    }

    @Test
    fun `flags a delete hidden inside sh -c`() {
        assertEquals("删除命令", DangerCommand.match("sh -c \"cd /tmp && rm -rf x\""))
    }

    @Test
    fun `ignores words that merely contain rm`() {
        assertNull(DangerCommand.match("ls -la"))
        assertNull(DangerCommand.match("grep -n firmware README.md"))
        assertNull(DangerCommand.match("apk add charm"))
        assertNull(DangerCommand.match("git status"))
    }

    @Test
    fun `a command that only runs a script is not flagged by text alone`() {
        // 命令文本里没有删除命令：命中与否要靠调用方再去读脚本内容（见 ApprovalPlugin）。
        assertNull(DangerCommand.match("sh clean.sh"))
    }

    @Test
    fun `extracts script operands passed to an interpreter`() {
        assertEquals(listOf("clean.sh"), DangerCommand.scriptPaths("sh clean.sh"))
        assertEquals(listOf("tools/clean.py"), DangerCommand.scriptPaths("python3 tools/clean.py"))
        assertEquals(listOf("clean.sh"), DangerCommand.scriptPaths("./clean.sh"))
        assertEquals(listOf("lib.sh"), DangerCommand.scriptPaths("source lib.sh"))
    }

    @Test
    fun `treats interpreter options as options, not script files`() {
        assertEquals(emptyList<String>(), DangerCommand.scriptPaths("sh -c \"echo hi\""))
        assertEquals(emptyList<String>(), DangerCommand.scriptPaths("ls -la"))
    }

    // --- commandReason：命令行判定（带「明显临时目标豁免」） ---

    @Test
    fun `exempts obviously transient delete targets`() {
        // 工作区内的构建产物
        assertNull(DangerCommand.commandReason("rm -rf build/classes", WS))
        // 先 cd 进 /tmp 再删相对路径：cwd 跟踪让 apicheck 归为临时
        assertNull(
            DangerCommand.commandReason(
                "cd /tmp && rm -rf apicheck && mkdir apicheck && cd apicheck",
                WS,
            ),
        )
        // 没有 cwd 基准时，仍凭路径段豁免
        assertNull(DangerCommand.commandReason("rm -rf node_modules", null))
        // find 的搜索根落在 /tmp
        assertNull(DangerCommand.commandReason("find /tmp -name '*.tmp' -delete", WS))
    }

    @Test
    fun `still flags real delete targets through commandReason`() {
        assertEquals("删除命令", DangerCommand.commandReason("rm -rf src", WS))
        assertEquals("删除命令", DangerCommand.commandReason("rm -rf /", WS))
        assertEquals("删除命令", DangerCommand.commandReason("rm -rf ~/data", WS))
        assertEquals("删除命令", DangerCommand.commandReason("rm -rf \$HOME/secret", WS))
        // 混入一个非临时目标 → 整体仍拦
        assertEquals("删除命令", DangerCommand.commandReason("rm -rf build/classes src", WS))
        // find 的搜索根落在工作区（非临时）
        assertEquals("find -delete", DangerCommand.commandReason("find . -name '*.log' -delete", WS))
    }

    @Test
    fun `flags a delete launched through a variable in command position`() {
        // 变量拼接绕过：`cmd=rm` 里的 rm 前面是 `=`，SEGMENT 不命中；`$cmd` 又不是字面量。
        // 命令位出现变量 / 命令替换 = 命令未知，按保守口径拦。文本里并没有删除词元，
        // 所以理由词是「不确定的命令位」，不是「删除命令」（弹窗拼成「命令里有删除命令」就是失实）。
        assertEquals("不确定的命令位", DangerCommand.commandReason("cmd=rm; \$cmd -rf src", WS))
        assertEquals("不确定的命令位", DangerCommand.commandReason("cmd=rm; \${cmd} -rf src", WS))
        assertEquals("不确定的命令位", DangerCommand.commandReason("`echo rm` -rf src", WS))
        // 环境变量前缀 + 变量命令位
        assertEquals("不确定的命令位", DangerCommand.commandReason("FLAG=1 \$cmd -rf src", WS))
        // 子壳 / 取反里的变量命令位同样是「命令未知」
        assertEquals("不确定的命令位", DangerCommand.commandReason("( \$cmd -rf src )", WS))
        assertEquals("不确定的命令位", DangerCommand.commandReason("! \$cmd -rf src", WS))
    }

    @Test
    fun `flags a variable command position behind a wrapper command`() {
        // §B5.6 第 8 条（临时探针实测复现，不是推测）：包装命令把变量推进了**参数位**，
        // 而命令位判定只看第一个命令词（sudo / env / timeout / chroot…）—— 于是
        // `sudo $cmd -rf src` 一路判成「已知命令 + 没有删除词元」，整条不弹审批。
        // 口径：**先穿透包装命令，再判命令位**。真命令未知 = 同一个「目标不确定」。
        assertEquals("不确定的命令位", DangerCommand.commandReason("sudo \$cmd -rf src", WS))
        assertEquals("不确定的命令位", DangerCommand.commandReason("env \$cmd -rf src", WS))
        assertEquals("不确定的命令位", DangerCommand.commandReason("timeout 30 \$cmd -rf src", WS))
        assertEquals("不确定的命令位", DangerCommand.commandReason("sudo \${cmd} -rf src", WS))
        assertEquals("不确定的命令位", DangerCommand.commandReason("chroot /jail \$cmd -rf src", WS))
        // 连环包装逐层穿透，每层自己的选项（-E / -n）与数值（5）都不算命令词
        assertEquals("不确定的命令位", DangerCommand.commandReason("sudo -E nice -n 5 \$cmd -rf src", WS))
        // 误报侧对照：包装命令 + **已知**命令词不该多弹一次（负责人选的正是「精准穿透」）
        assertNull(DangerCommand.commandReason("sudo vim notes.md", WS))
        assertNull(DangerCommand.commandReason("env PATH=/usr/bin make", WS))
        assertNull(DangerCommand.commandReason("timeout 300 ./build.sh", WS))
        // 穿透也不能把 chroot 的 jail 当命令词，否则临时目录豁免会跟着失真
        assertNull(DangerCommand.commandReason("chroot /jail rm -rf /tmp/x", WS))
    }

    @Test
    fun `a home-dir executable in command position is a known command and stays quiet`() {
        // `~/bin/tool` 的命令是已知的，只是路径在家目录下 —— 命令位判定不含 `~`。
        // （B 审探针实测：借用目标侧 UNCERTAIN_CHARS 会把每条 `~/` 命令都弹一次「删除命令」。）
        assertNull(DangerCommand.commandReason("~/bin/tool --version", WS))
        assertNull(DangerCommand.commandReason("~/scripts/clean.sh", WS))
        // 变量命令位仍按「未知」拦（$HOME 展开前不知道是什么）
        assertEquals("不确定的命令位", DangerCommand.commandReason("\$HOME/.local/bin/foo run", WS))
        // 目标侧口径不变：删家目录里的东西照旧拦
        assertEquals("删除命令", DangerCommand.commandReason("rm -rf ~/data", WS))
    }

    @Test
    fun `does not let a traversal path borrow the transient exemption`() {
        // `build` 是豁免词，但折叠 `..` 之后真实目标是工作区 src —— 豁免前必须先规范化。
        assertEquals("删除命令", DangerCommand.commandReason("rm -rf build/../../src", WS))
        // cwd 未知时同样不能凭裸 `build` 段豁免
        assertEquals("删除命令", DangerCommand.commandReason("rm -rf build/../../src", null))
        // 对照：真正的构建产物仍豁免，别为了堵绕过把整段豁免删掉
        assertNull(DangerCommand.commandReason("rm -rf build/classes", WS))
        assertNull(DangerCommand.commandReason("rm -rf build/classes", null))
    }

    @Test
    fun `falls back to conservative flag when targets cannot be resolved`() {
        // rm 无操作数（目标来自 stdin）
        assertEquals("删除命令", DangerCommand.commandReason("find . -name '*.log' | xargs rm", WS))
        // rm 藏在 sh -c 的引号串里：解析不出删除调用 → 保守拦
        assertEquals("删除命令", DangerCommand.commandReason("sh -c \"cd /tmp && rm -rf x\"", WS))
        // 只是「提到 rm」也保守拦（沿用 match 的保守语义）
        assertEquals("删除命令", DangerCommand.commandReason("grep -n rm README.md", WS))
        // 非删除命令
        assertNull(DangerCommand.commandReason("ls -la", WS))
    }

    // --- heredoc 正文：不是命令行，别拿它当命令判 ---

    @Test
    fun `a heredoc body is not command text`() {
        // 真机复现的**误报**（负责人实跑出弹窗）：`git commit -q -F - <<'EOF'` 的正文里
        // 一行「- `65c187f` 外部素材…」被 splitStatements 当成一条语句，命令位是反引号
        // 包住的 sha → 判成「不确定的命令位」，于是每次带 heredoc 的提交都弹一次审批。
        // 正文是**数据**（提交说明），不是要执行的命令，不参与判定。
        val commit = "git add -A && git commit -q -F - <<'EOF'\n" +
            "docs(看板): 记本批合并点\n" +
            "\n" +
            "- `65c187f` 外部素材那一节的结论\n" +
            "- 挂账两条：\$HOME 与 \${WS} 都没动\n" +
            "- 顺手记一句 rm -rf 只在文档里出现，不是命令\n" +
            "EOF\n" +
            "timeout 90 git push"
        assertNull(DangerCommand.commandReason(commit, WS))
    }

    @Test
    fun `handles every heredoc delimiter form`() {
        // 四种写法：<<EOF / <<"EOF" / <<'EOF' / <<-EOF（结束行按 tab 缩进）。
        assertNull(DangerCommand.commandReason("git commit -F - <<EOF\n- `deadbeef` 说明\nEOF", WS))
        assertNull(DangerCommand.commandReason("git commit -F - <<\"EOF\"\n- `deadbeef` 说明\nEOF", WS))
        assertNull(DangerCommand.commandReason("git commit -F - <<'EOF'\n- `deadbeef` 说明\nEOF", WS))
        assertNull(
            DangerCommand.commandReason("cat <<-MSG > notes.txt\n\t- `deadbeef` 说明\n\tMSG", WS)
        )
        // 命令替换写法：结束行带着收尾标点（`EOF)"`），仍算结束行。
        assertNull(
            DangerCommand.commandReason(
                "git commit -q -m \"\$(cat <<'EOF'\n- `deadbeef` 说明\nEOF)\"",
                WS,
            ),
        )
    }

    @Test
    fun `a delete on the command line is still flagged when a heredoc follows`() {
        // 剥正文不能把**命令行上**的真实删除一起剥掉。
        val command = "rm -rf src && git commit -q -F - <<'EOF'\n- `deadbeef` 说明\nEOF"
        assertEquals("删除命令", DangerCommand.commandReason(command, WS))
        // 命令位不确定同样不能被 heredoc 洗白。
        assertEquals("不确定的命令位", DangerCommand.commandReason("sudo \$cmd -f - <<'EOF'\n说明\nEOF", WS))
        // 删除命令正好落在「看起来像结束行」的那行上（`EOF && rm -rf src`）：
        // 结束行只认「分隔符 + 收尾标点」，这行不算结束行 → 整段剥不掉 → 照旧拦。
        assertEquals(
            "删除命令",
            DangerCommand.commandReason("git commit -F - <<'EOF'\n说明\nEOF && rm -rf src", WS),
        )
    }

    @Test
    fun `a delete inside a heredoc fed to an interpreter is still flagged`() {
        // **反例**，这条是本轮改动的牙：`sh <<'EOF'` 的正文就是要执行的脚本，
        // 当成数据剥掉等于给「用 heredoc 现写一个删除脚本再执行」开后门。
        assertEquals(
            "删除命令",
            DangerCommand.commandReason("sh <<'EOF'\nrm -rf /sdcard/Download/data\nEOF", WS),
        )
        assertEquals(
            "删除命令",
            DangerCommand.commandReason("bash -x <<'EOF'\ncd /sdcard\nrm -rf photos\nEOF", WS),
        )
        // 穿透包装命令之后仍是解释器 → 正文照旧参与判定。
        assertEquals(
            "删除命令",
            DangerCommand.commandReason("sudo sh <<'EOF'\nrm -rf /sdcard/x\nEOF", WS),
        )
        // 管道末端是解释器：正文会被执行。
        assertEquals(
            "删除命令",
            DangerCommand.commandReason("echo ok | sh <<'EOF'\nrm -rf /sdcard/y\nEOF", WS),
        )
    }

    @Test
    fun `a stray double-less-than does not swallow the rest of the command`() {
        // `<<` 出现在引号里（并非 heredoc）、或 heredoc 缺结束行时，**整段不剥** ——
        // 宁可多弹一次窗，也不要把后面的真实命令吞掉（变异 MV-4：找不到结束行就吞到尾，红在这两条）。
        assertEquals(
            "删除命令",
            DangerCommand.commandReason("grep -n \"a<<b\" notes.txt\nrm -rf src", WS),
        )
        assertEquals(
            "删除命令",
            DangerCommand.commandReason("git commit -F - <<EOF\n提交说明（缺结束行）\nrm -rf src", WS),
        )
    }
}
