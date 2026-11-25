package dev.kair.app.core.proot

import dev.kair.app.core.KotlinSourceScanner
import dev.kair.app.core.tools.BashTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 容器环境表的单档不变量（U78；U36 的双档对齐要求随之收敛）。
 *
 * U78 起只有 proot 一档（ADR 0007）：环境表只有**一张**，`KAIR_CHANNEL` 取值恒
 * `ContainerRunner.MODE_PROOT`（取自既有常量，不另立字面量）。
 *
 * 用例各守一面：表本身（顺序与通道项）、变量名与静态守则文案同源、静态守则已覆盖
 * 「一次性进程树 / background_task」两个事实（动态通道 note 已删除，见 U116），以及
 * 「供值取自 `MODE_PROOT`」—— 最后一句只有扫源码才守得住，见其正文。
 */
class ContainerEnvTest {

    @Test
    fun `the environment table has one channel value at the tail`() {
        val pairs = ContainerEnv.pairs()
        // 通道项固定在末尾：脚本与 argv 的断言按顺序派生，插在中间会让「表尾」语义失真。
        assertEquals("${ContainerEnv.CHANNEL}=${ContainerRunner.MODE_PROOT}", pairs.last())
        // 只有一处通道取值；表里不得再出现第二个 chroot 时代的通道值。
        assertEquals(1, pairs.count { it.startsWith("${ContainerEnv.CHANNEL}=") })
        assertTrue("PATH 必须由容器常量给出", pairs.contains("PATH=${ContainerEnv.PATH}"))
        assertTrue("回收站路径必须进容器环境", pairs.contains("${TrashMounts.ENV}=${TrashMounts.CONTAINER}"))
    }

    @Test
    fun `the bash tool teaches the variable name this table actually exports`() {
        // 守则里写的是变量名。改了常量名不改文案 = 模型照着查一个容器里根本不存在的变量，
        // 而这条错误**不会**让任何行为测试变红（提示词与容器环境分属两处）。
        assertEquals("KAIR_CHANNEL", ContainerEnv.CHANNEL)
        assertTrue(
            "BashTool 守则必须引用 ContainerEnv.CHANNEL 的确切名字",
            BashTool().promptGuidelines.any { it.contains(ContainerEnv.CHANNEL) },
        )
    }

    @Test
    fun `the static bash rules already state the one-shot and background_task facts`() {
        // U116：动态通道 note 已删除，这四条事实必须由静态守则承担 —— 少一句就静默退化。
        val rules = BashTool().promptGuidelines.joinToString("\n")
        assertTrue(rules.contains(ContainerEnv.CHANNEL))
        // 第三条可达路径（变量未定义 = 命令其实落在宿主 toybox，那里没人收 `&` 的后代）与
        // 「常驻的正解」是祈使句。删掉任何一句都不该静默过关。
        assertTrue("守则必须教「变量未设置」这一格", rules.contains("fell back to the device shell"))
        assertTrue("守则必须教 setsid 也逃不出去", rules.contains("`setsid` included"))
        assertTrue("常驻正解必须是祈使句", rules.contains("use background_task with `timeout: 0`"))
    }

    @Test
    fun `the exported channel value comes from the routing mode, not a new literal`() {
        // 「值取自既有 ContainerRunner.MODE_PROOT」这句只有扫源码才守得住：直接写一个字面量
        // "proot" 时，上面的断言全绿（它也等于这个值），但环境表已经和常量分家了
        // （AGENTS.md §10.4 一个概念一个实现点）。字符串字面量会被 stripped() 抹掉，
        // 所以这一条读原文；调用点仍用 stripped，注释里的调用骗不过它。
        val env = repoFile("app/src/main/java/dev/kair/app/core/proot/ContainerEnv.kt")
            .readText(Charsets.UTF_8)
        val proot = strippedRepo("app/src/main/java/dev/kair/app/core/proot/ProotCommandRunner.kt")
        val root = strippedRepo("app/src/main/java/dev/kair/app/core/proot/RootCommandRunner.kt")
        assertTrue(
            "环境表的通道值必须由 ContainerRunner.MODE_PROOT 供值",
            env.contains("\"\$CHANNEL=\${ContainerRunner.MODE_PROOT}\""),
        )
        assertTrue("proot 侧必须调用无参 pairs()", proot.contains("ContainerEnv.pairs()"))
        assertTrue("维护脚本侧也必须复用同一张表", root.contains("ContainerEnv.pairs()"))
    }

    /** 剥注释后的仓库文件文本（与 `ImageScalingGuardTest` 同口径：注释里的调用不该把守卫带绿）。 */
    private fun strippedRepo(path: String): String =
        KotlinSourceScanner.stripped(repoFile(path).readText(Charsets.UTF_8))

    /** 与既有守卫同口径：从测试工作目录往上找仓库根。 */
    private fun repoFile(path: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "docs").isDirectory && File(dir, "app").exists()) return File(dir, path)
            dir = dir.parentFile
        }
        error("找不到仓库根（须含 docs/ 与 app/），无法定位 $path")
    }
}
