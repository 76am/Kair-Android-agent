package dev.kair.app.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Field
import java.lang.reflect.ParameterizedType

/**
 * 「按会话登记的资源必须有归宿」的机械判据（ADR 0005 的跟踪项 U10）。
 *
 * ## 为什么需要它
 *
 * 这类缺陷在本仓已经出过**三例**，三次都靠人肉抓：
 *
 * | 例 | 症状 | 谁抓到的 |
 * |---|---|---|
 * | `CacheInspector` 的 `ALLOW_SESSION` | 按会话记放行，会话结束不清 | 门卫代改 |
 * | `RequestCapture` | `deleteSession` 清了、`deleteProject` 那条路径漏了 | 代理评审 |
 * | `pluginSendLog` | 按 sessionId 建槽，从创建工作起就没人清 | 副程评批次 5 |
 *
 * `SessionRegistry.Cleanup` 让「清什么、按什么顺序清」只有一个出处，
 * 但它管不到**宿主侧新长出来的登记项** —— 你在这里加一个 `Map<String, X>` 字段，
 * 枚举和清单都不会有人提醒你。
 *
 * 这条守卫补的就是这一步：宿主类（`AppContainer` 与它拆出来的协作对象）里每个
 * 「以会话 id 为键」的容器字段**都必须在 [classification] 里有归宿**，要么指向某项
 * `Cleanup`，要么写明为什么不用清。
 *
 * ## 它覆盖什么、不覆盖什么（别高估它）
 *
 * 只认两种形状的字段：
 * - `Map<String, …>`（含 `ConcurrentHashMap`）
 * - `StateFlow<Set<String>>`（按会话 id 的集合）
 *
 * **不认**：`Map<String, X>` 但键不是会话 id 的（比如按项目 id）——
 * 那种要人工判断，本守卫不区分，所以宁可让它误报一次、由人决定登记还是改类型。
 * **也不认** `Set<String>`（`Set<String>` 本身不在上面两种形状里）：
 * [AttachmentGc] 的 `gcScheduled` 是按**项目 id** 记「已排队回收」，由 GC job 自己
 * `remove`，与会话生命周期无关；它不需要 `Cleanup` 归宿，守卫扫不到它也不等于漏。
 */
class SessionResourceCoverageTest {

    /**
     * 字段名 → 归宿。
     *
     * 值写 `Cleanup.XXX` 表示「会话关闭时由那一项清理」；
     * 写自由文本表示「不需要随会话清理」，并说明理由（将来的人要能看懂）。
     */
    private val classification: Map<String, String> = mapOf(
        "runningJobs" to "Cleanup.TrackJob（关闭与淘汰都清）",
        "pluginSendLog" to "Cleanup.SendWindow（**只随关闭清**，淘汰不清 —— " +
            "淘汰后会话 id 还在用，清掉槽等于把限流额度重置）",
        "_runningSessions" to "Cleanup.RunningMark（关闭与淘汰都清）",
        "runningSessions" to "`_runningSessions` 的只读视图（asStateFlow），同一份数据，不需要单独清",
    )

    /**
     * 扫描目标：宿主类组成的**一组**，不是一个类。
     *
     * U31 第一刀把插件运行时桥搬出 `AppContainer`（`pluginSendLog` 跟着去了
     * [PluginRuntimeBridge]），登记项不删、只扩扫描面 —— 归宿登记仍然是同一份。
     * 以后再把按会话分槽的字段搬进新的协作对象，必须把它加到这里。
     */
    private val hostClasses: List<Class<*>> = listOf(
        AppContainer::class.java,
        PluginRuntimeBridge::class.java,
        // 终审补：B 段那个协作对象同样是宿主侧，今天它没有按会话分槽的字段，
        // 但「以后加一个 Map<sessionId, X>」的风险与桥一模一样 —— 漏扫就是空转。
        PluginLifecycleOps::class.java,
        // U31 第二刀 / 3a：GC 与工作区文件访问同样是宿主侧协作对象。今天二者都没有
        // 按会话分槽的字段（`gcScheduled` 按项目 id，见类注释），照样登记，防下次空转。
        AttachmentGc::class.java,
        WorkspaceFileAccess::class.java,
        // U31 ③-a：解析族（模型 / 鉴权 / 工具过滤 / 窗口 / 压缩 / 采样）搬进 SessionAssembly。
        // 今天它不持按会话分槽的字段（诊断按 sessionId 记在容器持有的 Diagnostics 里），照样登记，
        // 防下次「在新对象里加一个 Map<sessionId, X>」空转。
        SessionAssembly::class.java,
        // U31 ③-b：编排段（open / close / delete / new / 子智能体 / 会话日志）搬进 SessionLifecycle，
        // 它持有 activeSession 单槽与 sessions 注册表，今天没有 Map<sessionId, X>；照样登记，
        // 防下次往里加按会话分槽的容器时空转。
        SessionLifecycle::class.java,
    )

    @Test
    fun `every session-keyed container in the host classes is classified`() {
        val found = hostClasses
            .flatMap { it.declaredFields.toList() }
            .filter { isSessionKeyed(it) }
            .map { it.name }
            .toSet()

        val unclassified = found - classification.keys
        assertTrue(
            "宿主类里有按会话分槽的容器没登记归宿：${unclassified.sorted()}\n" +
                "两种处置，二选一：\n" +
                "  ① 该会话关闭时它应当被清 → 加进 SessionRegistry.Cleanup，" +
                "并在 SessionResources 里实现释放（注意分清 close 清 / evict 不清）；\n" +
                "  ② 它不该随会话清 → 在 classification 里写明理由。\n" +
                "别为了让这条变绿就直接往 classification 里塞字段名而不写理由 —— " +
                "那样它就从「判据」退化成「登记簿」了。",
            unclassified.isEmpty(),
        )

        val stale = classification.keys - found
        assertTrue(
            "登记表里有已经不存在的字段：${stale.sorted()}（改名或删除后请同步，否则守卫在空转）",
            stale.isEmpty(),
        )
    }

    /** 以会话 id 为键的两种容器形状。 */
    private fun isSessionKeyed(f: Field): Boolean {
        val generic = f.genericType as? ParameterizedType ?: return false
        val firstArg = generic.actualTypeArguments.firstOrNull()
        if (Map::class.java.isAssignableFrom(f.type)) return firstArg == String::class.java
        // StateFlow<Set<String>> / MutableStateFlow<Set<String>>
        val inner = firstArg as? ParameterizedType ?: return false
        return inner.rawType == Set::class.java &&
            inner.actualTypeArguments.firstOrNull() == String::class.java
    }
}
