package dev.kair.app.core.agent

import dev.kair.app.core.ai.ConnectionPhases
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 一次装配里没进正文的段落。
 *
 * 两种成因必须分开看：
 * - [Kind.FAILED]：贡献点抛了异常（含异常类型）—— 第三方插件 / 读盘的技能文件；
 * - [Kind.ABSENT]：没抛，但没产出正文（返回 null 或全空白）。
 *
 * [Kind.ABSENT] **只表示「注册了、这次没产出」，不代表缺陷**：贡献点的契约允许它
 * 按本次上下文合法地返回空（例如插件在该项目下没有可注入的内容）。
 * 也正因如此，条目本身就是给「第三方不可控代码」的可见性，**内置段落不在此列** ——
 * 内置段落的空白有契约明文（用户没填「附加系统指令」就整段不出现），若也记成缺席，
 * 默认配置下每次装配都会误报一条。内置段落抛异常不会走到这里：那属于内核 bug，
 * 必须让当回合响亮地失败（见 `SystemPromptBuilder.section`）。
 */
data class SectionIssue(
    val id: String,
    val kind: Kind,
    /** [Kind.FAILED] 时是异常类型名；[Kind.ABSENT] 为 null。 */
    val detail: String? = null,
) {
    /** [FAILED] 抛了异常；[ABSENT] 注册了但这次没产出（合法状态，不代表缺陷）。 */
    enum class Kind { FAILED, ABSENT }
}

/**
 * 插件投递（`AgentBridge.send`）的失败分类计数。
 *
 * 归属**应用级**而非会话级：投递是「插件 → 某个会话」的跨会话行为，失败与用户正在看
 * 哪个会话无关。进程内累计、应用重启归零；定长计数器，不需要清理。
 */
data class DeliveryFailures(
    /** 目标会话已不存在：重试不会成功。 */
    val permanent: Int = 0,
    /** 限流 / 桥暂不可用 / 发送抛异常：下个周期值得重试。 */
    val temporary: Int = 0,
)

enum class DeliveryFailureKind { PERMANENT, TEMPORARY }

/**
 * 装配诊断面：把「本该有、实际没有」的东西记下来，供面板**只读**展示。
 *
 * 与 `CacheInspector` 的分工：那个记「请求装配成了什么字节」，这里记「哪里失败了」。
 * 两者都**不参与请求装配**，读它们不会改变任何一位请求字节。
 *
 * 归属与生命周期（`AGENTS.md` §10.5）：
 * - [sectionIssues] 按**会话**索引；会话关闭时由宿主调 [remove] 清理，
 *   与 `CacheInspector.remove` 同一处、同一时机。
 * - 每次装配**覆盖**该会话的条目，不追加 —— 容量受「会话数 × 段落数」约束，不随回合增长。
 * - 并发：写入来自请求装配线程，`StateFlow.update` 原子；读方（Compose）只收集状态。
 *
 * 为什么不是全局单例：诊断必须能随会话一起消失。做成 `object` 会让已删除会话的条目
 * 永久留在进程里，也没有「谁负责清理」的答案。
 */
class Diagnostics {

    private val _sectionIssues = MutableStateFlow<Map<String, List<SectionIssue>>>(emptyMap())

    /** 各会话最近一次装配里缺席 / 异常的段落。 */
    val sectionIssues: StateFlow<Map<String, List<SectionIssue>>> = _sectionIssues.asStateFlow()

    /** 记录一次装配的诊断。内容与上次相同时不触发状态更新（避免每回合都重组面板）。 */
    fun recordAssembly(sessionId: String, issues: List<SectionIssue>) {
        _sectionIssues.update { current ->
            if (current[sessionId] == issues) current else current + (sessionId to issues)
        }
    }

    /** 会话关闭 / 淘汰时清掉它的诊断条目。 */
    fun remove(sessionId: String) {
        _sectionIssues.update { it - sessionId }
        _compactionFailures.update { it - sessionId }
        _emptyResponses.update { it - sessionId }
        _stopTimeouts.update { it - sessionId }
        // 前置阶段表是共享客户端写的应用级状态（U57），生命周期跟着会话走，一并清。
        ConnectionPhases.clear(sessionId)
    }

    private val _compactionFailures = MutableStateFlow<Map<String, String>>(emptyMap())

    /**
     * 各会话**最近一次**压缩失败的成因（异常类型 + 消息）。
     *
     * 覆盖式：一次成功的摘要会把它清掉（见 [clearCompactionFailure]），因此面板不会
     * 一直挂着一个已经好了的失败 —— 与 [sectionIssues] 同一套语义。
     */
    val compactionFailures: StateFlow<Map<String, String>> = _compactionFailures.asStateFlow()

    fun recordCompactionFailure(sessionId: String, reason: String) {
        _compactionFailures.update { current ->
            if (current[sessionId] == reason) current else current + (sessionId to reason)
        }
    }

    fun clearCompactionFailure(sessionId: String) {
        _compactionFailures.update { if (sessionId in it) it - sessionId else it }
    }

    private val _emptyResponses = MutableStateFlow<Map<String, Int>>(emptyMap())

    /**
     * 各会话被丢弃的**空回合**计数（U48）：模型只回了结束原因、没有正文也没有工具调用。
     * 这类回合不落盘（`TurnExecutor`），历史里无痕 —— 诊断是它唯一的可见痕迹。
     */
    val emptyResponses: StateFlow<Map<String, Int>> = _emptyResponses.asStateFlow()

    /** 记一次空回合（按会话累计，[remove] 时随会话清理）。 */
    fun recordEmptyResponse(sessionId: String) {
        _emptyResponses.update { it + (sessionId to (it[sessionId] ?: 0) + 1) }
    }

    private val _stopTimeouts = MutableStateFlow<Map<String, String>>(emptyMap())

    /**
     * 各会话**最近一次**「停止收尾超过硬上界、被强制回到空闲」的中文说明（U57）。
     *
     * 覆盖式（同 [compactionFailures]）：同一会话再发生一次就替换，不累计 —— 这是止损
     * 记录，不是计数器。**必须让用户看得见**，否则硬上界只是把「永久转圈」换成
     * 「悄悄回空闲」，等于新的静默失败（任务文档 §2.2 / V3）。
     */
    val stopTimeouts: StateFlow<Map<String, String>> = _stopTimeouts.asStateFlow()

    fun recordStopTimeout(sessionId: String, detail: String) {
        _stopTimeouts.update { current ->
            if (current[sessionId] == detail) current else current + (sessionId to detail)
        }
    }

    private val _deliveryFailures = MutableStateFlow(DeliveryFailures())

    /** 插件投递失败计数（应用级，见 [DeliveryFailures]）。 */
    val deliveryFailures: StateFlow<DeliveryFailures> = _deliveryFailures.asStateFlow()

    private val _notices = MutableStateFlow<Map<String, String>>(emptyMap())

    /**
     * 各功能**就地记录**的提示（键由功能自定，例如记忆插件用 `memory:<工作区>`）。
     *
     * 与 [sectionIssues] 的分工：那张表只记「段落缺席 / 抛异常」，本表记「这一回合发生了什么、
     * 用户需要知道」的计数类事实（如「已隐藏 7 条过期记忆」）。
     *
     * 归属与生命周期（`AGENTS.md` §10.5）：键的命名空间归记录它的功能，**清理责任也在功能自己**
     * （插件卸载时调 [clearNotices]）；容量受功能自己的键空间约束（记忆插件是工作区数）。
     * 只在装配线程 / 事件线程写，`StateFlow.update` 原子；不参与任何请求字节。
     */
    val notices: StateFlow<Map<String, String>> = _notices.asStateFlow()

    /** 记一条提示；[text] 为空即清除该键（覆盖式，不累计）。 */
    fun recordNotice(key: String, text: String) {
        _notices.update { current ->
            when {
                text.isBlank() -> if (key in current) current - key else current
                current[key] == text -> current
                else -> current + (key to text)
            }
        }
    }

    /** 清掉某个键前缀的全部提示（功能卸载 / 停用时调）。 */
    fun clearNotices(prefix: String) {
        _notices.update { current ->
            if (current.keys.none { it.startsWith(prefix) }) current
            else current.filterKeys { !it.startsWith(prefix) }
        }
    }

    /**
     * 记一次投递失败。
     *
     * 分类由**宿主**判定（它才知道是「会话不存在」还是「限流 / 抛异常」）：
     * 永久失败要能让用户看见并去改配置，临时失败则说明下个周期会重试。
     */
    fun recordDeliveryFailure(kind: DeliveryFailureKind) {
        _deliveryFailures.update { counts ->
            when (kind) {
                DeliveryFailureKind.PERMANENT -> counts.copy(permanent = counts.permanent + 1)
                DeliveryFailureKind.TEMPORARY -> counts.copy(temporary = counts.temporary + 1)
            }
        }
    }
}
