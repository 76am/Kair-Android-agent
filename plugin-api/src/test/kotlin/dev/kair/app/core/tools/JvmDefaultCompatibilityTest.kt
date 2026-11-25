package dev.kair.app.core.tools

import dev.kair.app.core.storage.StorageBackend
import dev.kair.plugin.api.PluginContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 回归测试：契约接口里的**可选成员**必须编译成真正的 JVM default 方法。
 *
 * 没有 `-Xjvm-default` 时，Kotlin 只把默认实现放进 `Tool$DefaultImpls`，接口上的方法
 * 仍是 abstract —— 按旧版 API 编译、没有实现该方法的已安装插件，运行期一调用就
 * `AbstractMethodError`（现场：`Tool.getReadOnly()`）。本测试把编译选项钉死。
 */
class JvmDefaultCompatibilityTest {

    @Test
    fun `optional Tool members are real default methods`() {
        val optional = listOf(
            "getPromptSnippet",
            "getPromptGuidelines",
            "getRequiresApproval",
            "getApprovalKey",
            "getApprovableForever",
            "getReadOnly",
            "isParallelSafe",
        )
        for (name in optional) {
            val method = Tool::class.java.getDeclaredMethod(name)
            assertFalse(
                "$name 必须是 default 方法（检查 -Xjvm-default=all-compatibility）",
                Modifier.isAbstract(method.modifiers),
            )
        }
    }

    /**
     * v8 给 [PluginContext] 新增了 5 个成员，全部靠默认实现保持旧插件可用
     * （不订阅就收不到事件、没有作用域就不跑后台任务、拿不到桥就降级）。
     *
     * 这份「默认」同时是**危险源**：编译选项一旦失效，它们退化成 abstract，旧插件
     * 运行期 `AbstractMethodError`，而且**没有任何测试会红**。Worse：`BridgedContext`
     * 漏转发时也同样是静默失效（已在本批修掉）。两条腿都要钉住。
     */
    @Test
    fun `optional PluginContext members are real default methods`() {
        // val 成员在字节码上是 getter（getScope / getAgents / getDataDir）。
        val optional = listOf(
            "onAgentEvent",
            "onDeactivate",
            "getScope",
            "getAgents",
            "getDataDir",
        )
        for (name in optional) {
            val method = try {
                PluginContext::class.java.getDeclaredMethod(name, *paramsOf(name))
            } catch (e: NoSuchMethodException) {
                throw AssertionError(
                    "PluginContext.$name 不存在：v8 新增成员被删或改名，会破坏已发布插件的 ABI",
                    e,
                )
            }
            assertFalse(
                "$name 必须是 default 方法（检查 -Xjvm-default=all-compatibility）",
                Modifier.isAbstract(method.modifiers),
            )
        }
    }

    private fun paramsOf(name: String): Array<Class<*>> = when (name) {
        "onAgentEvent" -> arrayOf(Function1::class.java)
        "onDeactivate" -> arrayOf(Function0::class.java)
        else -> emptyArray()
    }

    @Test
    fun `optional CommandRunner members are real default methods`() {
        val getter = CommandRunner::class.java.getDeclaredMethod("getContainerWorkspacePath")
        assertFalse(
            "CommandRunner.containerWorkspacePath 必须是 default 方法",
            Modifier.isAbstract(getter.modifiers),
        )
        val pathFor = CommandRunner::class.java.getDeclaredMethod("containerWorkspacePathFor", File::class.java)
        assertFalse(
            "CommandRunner.containerWorkspacePathFor 必须是 default 方法（旧插件不实现也要能跑）",
            Modifier.isAbstract(pathFor.modifiers),
        )
    }

    @Test
    fun `optional StorageBackend member is a real default method`() {
        val method = StorageBackend::class.java.getDeclaredMethod("workspaceBase")
        assertFalse(
            "StorageBackend.workspaceBase 必须是 default 方法（旧插件不实现也要能跑）",
            Modifier.isAbstract(method.modifiers),
        )
    }

    // ------------------------------------------------------------------
    // U47（2026-11-11）：上面的用例是**手列成员**的定点核验，覆盖不全的历史已经翻过车
    // （`getCategory` 落在清单外）。下面两条改为**基线快照 + 反射全量比对**：
    // 基线 `abi-baseline.txt` 由生成器口径（[regeneratedBaseline]）产出，双向比对保证
    // 「基线缺条目」与「基线多出条目」都红；逐条核验保证每条基线条目
    // 「真 default 方法 + `DefaultImpls` 外壳存在」。手列用例保留不删（现状回归 + 历史教训锚点）。
    // ------------------------------------------------------------------

    /**
     * 双向比对：反射枚举 classpath 上 `dev.kair` 主源集的**全部接口**的 default 成员，
     * 与 [abi-baseline.txt] 逐一比对 —— 两个方向都必须为空才算过：
     * - 反射有、基线无 → 有人加了带默认实现的契约成员没登记（守卫盲区）；
     * - 基线有、反射无 → 契约成员被删 / 改抽象（ABI 破坏，须提升 `KairApi.VERSION`）。
     */
    @Test
    fun `abi baseline matches every default member of every contract interface`() {
        val actual = regeneratedBaseline()
        val expected = abiBaseline()
        val missing = actual.filterNot { it in expected.toSet() }
        val extra = expected.filterNot { it in actual.toSet() }
        assertTrue(
            buildString {
                appendLine("abi-baseline.txt 与反射枚举出的契约默认成员不一致（plugin-api/src/main 零改动是本批边界）：")
                if (missing.isNotEmpty()) {
                    appendLine("基线缺条目 —— 新增了带默认实现的契约成员？按同格式补进基线：")
                    missing.forEach { appendLine("  + $it") }
                }
                if (extra.isNotEmpty()) {
                    appendLine("基线多出条目 —— 契约成员被删 / 改抽象？这是 ABI 破坏，须走破坏性变更流程：")
                    extra.forEach { appendLine("  - $it") }
                }
            },
            missing.isEmpty() && extra.isEmpty(),
        )
    }

    /**
     * 逐条核验基线条目：成员存在、非 abstract（真 default 方法），且
     * `<接口>$DefaultImpls` 外壳上有同名静态方法 —— `all-compatibility` 的两半
     * （default 方法 + DefaultImpls 外壳）缺一半就红，只查「不是 abstract」守不住外壳。
     */
    @Test
    fun `every baseline entry is a default method with a DefaultImpls shell`() {
        val interfaces = contractInterfaces()
        for (entry in abiBaseline()) {
            val ifaceName = entry.substringBefore("#")
            val memberName = entry.substringAfter("#").substringBefore("(")
            val iface = interfaces.singleOrNull { it.simpleName == ifaceName }
            assertNotNull("基线条目 $entry 的接口 $ifaceName 不在契约面里（接口被删 / 改名？）", iface)
            val method = iface!!.declaredMethods.singleOrNull { it.name == memberName }
            assertNotNull("基线条目 $entry：$ifaceName 上找不到成员 $memberName —— 契约被删或改名，ABI 已破坏", method)
            assertFalse(
                "$entry 必须是 default 方法（检查 -Xjvm-default=all-compatibility）",
                Modifier.isAbstract(method!!.modifiers),
            )
            val shell = runCatching { Class.forName("${iface.name}\$DefaultImpls") }.getOrNull()
            assertNotNull("找不到 ${iface.name}\$DefaultImpls 外壳 —— all-compatibility 只剩 default 方法一半，旧编译器生成的插件类会失链", shell)
            assertTrue(
                "${iface.name}\$DefaultImpls 缺少 $memberName 的静态外壳方法",
                shell!!.declaredMethods.any { it.name == memberName && Modifier.isStatic(it.modifiers) },
            )
        }
    }

    /**
     * 基线生成口径（与 `abi-baseline.txt` 的头部注释一致）：
     * `接口简单名#成员名(参数类型简单名):返回类型简单名`，按字典序排序。
     * 只收**实例 default 成员**（abstract / static 不算）。
     */
    private fun regeneratedBaseline(): List<String> =
        contractInterfaces()
            .flatMap { iface ->
                iface.declaredMethods
                    .filter { !Modifier.isAbstract(it.modifiers) && !Modifier.isStatic(it.modifiers) }
                    .map { describe(iface, it) }
            }
            .sorted()

    private fun describe(iface: Class<*>, method: Method): String =
        "${iface.simpleName}#${method.name}" +
            "(${method.parameterTypes.joinToString(",") { it.simpleName }})" +
            ":${method.returnType.simpleName}"

    /**
     * 契约接口全集 = classpath 上 `dev/kair` **主源集**里的全部接口（plugin-api 就是插件 ABI 面，
     * 接口即契约）。测试类在 test 源集，由 [isMainSourceRoot] 判源集归属，不参与枚举。
     *
     * B 审（U47）改过口径：旧写法用路径子串 `contains("/main/")`，只要仓库祖先目录里
     * 有一级叫 `main`（如 `/home/main/Kair`），**test 源集**路径也会命中，测试类会被误纳入
     * 契约面。现在取「最近的 main / test 祖先目录名」作判据。
     */
    private fun contractInterfaces(): List<Class<*>> {
        val result = LinkedHashSet<Class<*>>()
        val loader = Thread.currentThread().contextClassLoader
        val resources = loader.getResources("dev/kair")
        while (resources.hasMoreElements()) {
            val url = resources.nextElement()
            if (url.protocol != "file") continue
            val root = File(url.toURI())
            if (!isMainSourceRoot(root)) continue
            root.walkTopDown()
                .filter { it.isFile && it.extension == "class" }
                .forEach { file ->
                    // getResources("dev/kair") 的 URL 已经指到 dev/kair 目录里，
                    // 相对路径要补回前缀才是 FQN（例：app/core/model/Part.kt → dev.kair.app.core.model.Part）。
                    val rel = "dev.kair." + file.relativeTo(root).path
                        .replace(File.separatorChar, '/')
                        .removeSuffix(".class")
                        .replace('/', '.')
                    // 内部类（DefaultImpls / 伴生形状）不是独立契约
                    if ('$' in rel) return@forEach
                    val clazz = Class.forName(rel)
                    if (clazz.isInterface) result += clazz
                }
        }
        assertTrue("classpath 上枚举不到契约接口（dev/kair 主源集）—— 守卫空转等于没守卫", result.isNotEmpty())
        return result.sortedBy { it.name }
    }

    /**
     * 源集归属的四条钉子（U47 B 审 P1）：第三条是旧口径的反例 ——
     * 仓库路径含 `main` 时，test 源集必须仍被排除。
     */
    @Test
    fun `source root classification ignores main-looking ancestors`() {
        assertTrue(
            "Gradle main 源集输出必须纳入契约枚举",
            isMainSourceRoot(File("/repo/plugin-api/build/classes/kotlin/main/dev/kair")),
        )
        assertFalse(
            "test 源集输出不得纳入契约枚举",
            isMainSourceRoot(File("/repo/plugin-api/build/classes/kotlin/test/dev/kair")),
        )
        assertFalse(
            "仓库祖先目录叫 main 时 test 源集仍不得纳入（旧口径 contains(\"/main/\") 会误纳入）",
            isMainSourceRoot(File("/home/main/Kair/plugin-api/build/classes/kotlin/test/dev/kair")),
        )
        assertTrue(
            "resources/main 也按 main 源集处理（其中没有 .class，无害）",
            isMainSourceRoot(File("/repo/plugin-api/build/resources/main/dev/kair")),
        )
    }

    /** 读取基线数据文件（跳过 `#` 注释行与空行）。 */
    private fun abiBaseline(): List<String> {
        val stream = javaClass.getResourceAsStream("/abi-baseline.txt")
            ?: error("找不到 abi-baseline.txt（应在 plugin-api/src/test/resources）")
        return stream.readBytes().toString(Charsets.UTF_8)
            .lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
    }
}

/**
 * 判定 `dev/kair` 目录是否位于 **main 源集输出**（U47 B 审 P1）。
 *
 * 不能用路径子串 `contains("/main/")`：只要仓库祖先目录里有一级叫 `main`
 * （如 `/home/main/Kair`），test 源集路径也会命中，测试类会被误纳入契约面。
 * 改为从目录向上取**最近**的 `main` / `test` 祖先：Gradle 输出里源集目录
 * （`.../classes/kotlin/main/dev/kair` 的 `main`）离 `dev/kair` 比仓库路径里的同名目录近得多。
 *
 * 已知边界：classpath 只给 jar（`url.protocol != "file"`）时本函数不参与判定，
 * 契约枚举会空转并由 `contractInterfaces()` 的防空扫断言报红 —— 方向是 fail-safe，
 * 但 IDE / jar 化构建会误红，登记在交接 §6，不在本批扩成 JarEntry 扫描。
 */
internal fun isMainSourceRoot(root: File): Boolean =
    generateSequence(root) { it.parentFile }
        .firstOrNull { it.name == "main" || it.name == "test" }
        ?.name == "main"
