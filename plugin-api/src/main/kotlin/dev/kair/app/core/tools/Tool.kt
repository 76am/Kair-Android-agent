package dev.kair.app.core.tools

import dev.kair.app.core.ai.LlmToolSpec
import dev.kair.app.core.model.Part
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * Result of a tool execution: text content plus an error flag.
 *
 * [images] 供「把图片交给模型看」的工具（`read_image`）使用：文本内容照常回灌到
 * tool 消息，图片另作为一条 user 消息追加（vision 模型才能真正「看见」）。
 */
data class ToolResult(
    val content: String,
    val isError: Boolean = false,
    val images: List<Part.Image> = emptyList(),
)

/** Execution context handed to every tool. */
class ToolContext(
    val workspace: File,
    val sessionId: String,
    /** 命令执行后端，由内核按已装插件解析后注入；`bash` 工具通过它跑命令。 */
    val commandRunner: CommandRunner = LocalCommandRunner(),
    /**
     * 文件访问后端：read/write/edit/ls/grep/find 都经它做 I/O。默认本地视图；
     * 容器在场时由宿主注入 [ContainerFileBackend]（写操作可升级到容器内以 root 执行）。
     */
    val files: FileBackend = LocalFileBackend(workspace.path),
    /** Cooperative cancellation check; tools should honor it where practical. */
    val isAborted: () -> Boolean = { false },
) {
    /**
     * 「下一次请求之前压缩上下文」的请求开关，由**宿主注入**；默认 no-op。
     *
     * 为什么是类体属性而不是主构造器参数：主构造器的描述符属于插件 ABI，
     * 改签名会让已编译的插件在运行期直接 `NoSuchMethodError`。类体里新增带默认值的
     * 成员对旧插件是透明的（它们从不读它），因此 **不需要提升 `KairApi.VERSION`**。
     * 见 `docs/插件接口契约.md` 的 `ToolContext` 段落。
     *
     * 语义：只置位「本会话待压缩」，**不在工具执行中同步改写历史**（那会拆散当前
     * `tool_call` 与 `tool_result` 的配对）；真正的压缩发生在下一次请求装配之前。
     */
    var requestCompact: () -> Unit = {}
}

/**
 * 工具类别：审批插件据此把「用户关掉某类审批」映射到具体工具。
 *
 * - [READ] 只读 / 无副作用，不触发审批（除非用户打开「所有工具都需审批」）；
 * - [WRITE] 改动文件或持久配置，受「文件写入类工具需审批」开关控制；
 * - [DELETE] 删除类操作，受「删除类操作需审批」开关控制；
 * - [EXECUTE] 执行命令 / 委派执行，受各通道开关或「执行类工具需审批」控制；
 * - [UNSPECIFIED] 未声明。**动态插件工具未声明时默认需要审批**（宿主在装载时补成
 *   `requiresApproval = true`）；内置工具按工具名沿用原有开关。
 */
enum class ToolCategory {
    UNSPECIFIED,
    READ,
    WRITE,
    DELETE,
    EXECUTE,
    ;

    companion object {
        /** 把清单 / 外部声明里的字符串转成类别；未知值按未声明处理。 */
        fun from(value: String?): ToolCategory = when (value?.trim()?.lowercase()) {
            "read" -> READ
            "write" -> WRITE
            "delete" -> DELETE
            "execute" -> EXECUTE
            else -> UNSPECIFIED
        }
    }
}

/**
 * A tool the agent can call. Mirrors pi's AgentTool surface (name, description,
 * JSON-Schema parameters, execute).
 */
interface Tool {
    val name: String
    val description: String
    val parameters: JsonObject

    /** One-line snippet shown in the system prompt tool list. */
    val promptSnippet: String get() = ""

    /** Guideline bullets contributed to the system prompt rules. */
    val promptGuidelines: List<String> get() = emptyList()

    /**
     * 工具类别：审批面板按类别开关决定是否询问。默认 [ToolCategory.UNSPECIFIED]，
     * 由审批插件按工具名沿用原有规则（动态插件在装载时会把未声明者补成需审批）。
     */
    val category: ToolCategory get() = ToolCategory.UNSPECIFIED

    /** 执行前是否需要用户审批；默认不需要（审批由内核的 ApprovalGate 闸门统一裁决）。 */
    val requiresApproval: Boolean get() = false

    /** 审批「同类放行」的归类键；默认取工具名。 */
    val approvalKey: String get() = name

    /** 是否允许「永久放行」；危险操作（如安装插件）应为 false，只能逐次 / 逐会话放行。 */
    val approvableForever: Boolean get() = true

    /**
     * 本工具是否**只读**（不产生副作用）。用于「只读子智能体」（`subagent` 的 `search` 类型）
     * 组装工具集时按此过滤 —— 只保留读取类工具（read / ls / grep / find 等）。
     * 默认 false：未声明的一律视为可能有副作用，从而不会被放进只读子智能体。
     */
    val readOnly: Boolean get() = false

    /**
     * 同一回合的多个工具调用并发执行时，本工具是否可以与其他工具**同时**跑。
     *
     * 默认 **false（保守串行）**：未声明的一律共用一把锁按提交顺序执行 —— 写文件 /
     * 跑命令这类有副作用的工具并发时，对同一文件的「读-改-写」会互相覆盖丢失更新。
     * 只读（read / ls / grep / find 等）与自等待类（subagent、ask_user）工具应覆写为
     * true：多个只读或多个委派可以真正并行，收益最大的正是它们。
     *
     * 注意：并发只发生在「同回合多个调用」之间；单调用行为不受影响。
     */
    val isParallelSafe: Boolean get() = false

    suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult
}

class ToolException(message: String) : Exception(message)

/** Registry of available tools; also the source of LLM tool specs. */
class ToolRegistry {
    private val tools = LinkedHashMap<String, Tool>()

    // 写入来自会话协程（插件装载 / 卸载、工具开关 resync）与启动期装配，读取在装配线程；
    // 全部加锁，避免 ConcurrentModificationException 与读到「清空到一半」的中间态。
    @Synchronized
    fun register(tool: Tool): ToolRegistry {
        tools[tool.name] = tool
        return this
    }

    @Synchronized
    fun registerAll(list: Iterable<Tool>): ToolRegistry {
        list.forEach { register(it) }
        return this
    }

    @Synchronized
    fun get(name: String): Tool? = tools[name]

    /** 移除一个工具（动态插件卸载时使用）。返回是否存在过。 */
    @Synchronized
    fun unregister(name: String): Boolean = tools.remove(name) != null

    /** 清空全部工具；宿主 resync 重建终序时使用（清空后立刻重新注册）。 */
    @Synchronized
    fun clear() {
        tools.clear()
    }

    /**
     * 原子替换整个工具集：宿主重建终序时用这个，避免「clear → 逐个 register」之间
     * 被并发读取看到空表 / 半张表（系统提示与请求 schema 会因此不一致）。
     */
    @Synchronized
    fun replaceAll(list: Iterable<Tool>) {
        tools.clear()
        list.forEach { tools[it.name] = it }
    }

    @Synchronized
    fun all(): List<Tool> = tools.values.toList()

    /**
     * 发给模型的工具 schema。[filter] 由宿主按会话解析：例如 `read_image` 只在当前
     * 模型勾选「图像模型」时才可见。默认全量，行为与改动前一致。
     */
    @Synchronized
    fun specs(filter: (Tool) -> Boolean = { true }): List<LlmToolSpec> =
        tools.values.filter(filter).map {
            LlmToolSpec(it.name, it.description, it.parameters)
        }

    /** Execute a tool call, converting thrown errors into error results. */
    suspend fun execute(call: Part.ToolCall, ctx: ToolContext): ToolResult {
        val tool = get(call.name)
            ?: return ToolResult("Unknown tool: ${call.name}", isError = true)
        val args = parseToolArguments(call.arguments)
            ?: return ToolResult(invalidToolArgsMessage(call.name, call.arguments), isError = true)
        return try {
            tool.execute(args, ctx)
        } catch (e: ToolException) {
            ToolResult(e.message ?: "Tool failed", isError = true)
        } catch (e: CancellationException) {
            // 取消不是失败：继续向上传播，否则停止 / 超时会被伪装成一条工具结果。
            throw e
        } catch (e: Exception) {
            ToolResult("${e.javaClass.simpleName}: ${e.message}", isError = true)
        }
    }
}

val JSON = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

/**
 * 工具参数的**唯一判据**：必须是严格 JSON 对象（空白串按 `{}`，保持既有语义）。
 *
 * 为什么不能用 [JSON]：它配了 `isLenient = true`，会把 `<longcat_arg_value>` 这类
 * **未加引号的占位符**当成字符串接受；而 OpenAI 兼容端点按严格 JSON 解析 `arguments`，
 * 解析失败会把整轮请求吃掉（表现为 200 + 空流，之后每回合都失败）。宽松解析成功
 * 不等于端点接受。
 *
 * 三处共用：工具执行（`ToolRegistry.execute`）、历史修复（`HistorySanitizer`）、
 * 钩子链（`AgentSession.parseArgs`）—— 一个概念只有一个实现点。
 */
fun parseToolArguments(raw: String): JsonObject? {
    if (raw.isBlank()) return JsonObject(emptyMap())
    // 注意：kotlinx.serialization 的 `parseToJsonElement` 在 1.4.1 实测**即使
    // `isLenient = false` 也接受未加引号的值**（`{"t": <x>}` 会被读成字符串）。
    // 端点按严格 JSON 解析会整轮拒收，所以这里先自己走一遍严格语法，再交给解析器建对象。
    if (!isStrictJsonObject(raw)) return null
    return runCatching { JSON.parseToJsonElement(raw).jsonObject }.getOrNull()
}

/** 严格 JSON 语法校验（顶层必须是对象）；只回答「端点会不会接受」，不建对象。 */
private fun isStrictJsonObject(text: String): Boolean {
    val cursor = StrictJsonCursor(text)
    return cursor.objectAtTop() && cursor.atEnd()
}

/**
 * 最小严格 JSON 语法游标（RFC 8259 的子集，足够判工具参数）：
 * 拒绝未加引号的键 / 值、单引号、尾逗号、`NaN` / `Infinity`、前导 `+` / `.`、控制字符、
 * 非法转义；接受 `\uXXXX`、指数、空白。
 */
private class StrictJsonCursor(private val s: String) {
    private var i = 0

    fun atEnd(): Boolean {
        skipWs()
        return i == s.length
    }

    fun objectAtTop(): Boolean = parseObject(0)

    private fun parseObject(depth: Int): Boolean {
        if (!take('{')) return false
        skipWs()
        if (take('}')) return true
        while (true) {
            if (!parseString()) return false
            skipWs()
            if (!take(':')) return false
            if (!parseValue(depth)) return false
            skipWs()
            when {
                take('}') -> return true
                take(',') -> skipWs()
                else -> return false
            }
        }
    }

    private fun parseArray(depth: Int): Boolean {
        if (!take('[')) return false
        skipWs()
        if (take(']')) return true
        while (true) {
            if (!parseValue(depth)) return false
            skipWs()
            when {
                take(']') -> return true
                take(',') -> skipWs()
                else -> return false
            }
        }
    }

    private fun parseValue(depth: Int): Boolean {
        // 深度门：参数来自模型（不可信输入），无上限递归会抛 StackOverflowError，而它是 Error
        // 不是 Exception，三个调用点都不接。落在 HistorySanitizer 上最狠：脏历史已持久化，
        // 之后每次请求装配都炸 → 会话永久打不开。旧的 kotlinx 树读取器是显式栈迭代，不会溢栈。
        if (depth >= MAX_DEPTH) return false
        skipWs()
        if (i >= s.length) return false
        return when (s[i]) {
            '"' -> parseString()
            '{' -> parseObject(depth + 1)
            '[' -> parseArray(depth + 1)
            't' -> literal("true")
            'f' -> literal("false")
            'n' -> literal("null")
            else -> parseNumber()
        }
    }

    private fun parseString(): Boolean {
        if (!take('"')) return false
        while (i < s.length) {
            val c = s[i++]
            when {
                c == '"' -> return true
                c == '\\' -> {
                    if (i >= s.length) return false
                    when (s[i++]) {
                        '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                        'u' -> repeat(4) {
                            if (i >= s.length || !isHex(s[i])) return false
                            i++
                        }

                        else -> return false
                    }
                }

                c < ' ' -> return false
            }
        }
        return false
    }

    private fun parseNumber(): Boolean {
        take('-')
        if (i >= s.length) return false
        when {
            s[i] == '0' -> i++
            s[i] in '1'..'9' -> {
                i++
                while (i < s.length && s[i] in '0'..'9') i++
            }

            else -> return false
        }
        if (i < s.length && s[i] == '.') {
            i++
            if (i >= s.length || s[i] !in '0'..'9') return false
            while (i < s.length && s[i] in '0'..'9') i++
        }
        if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
            i++
            if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
            if (i >= s.length || s[i] !in '0'..'9') return false
            while (i < s.length && s[i] in '0'..'9') i++
        }
        return true
    }

    private fun literal(word: String): Boolean {
        if (!s.startsWith(word, i)) return false
        i += word.length
        return true
    }

    private fun take(c: Char): Boolean {
        if (i < s.length && s[i] == c) {
            i++
            return true
        }
        return false
    }

    private fun skipWs() {
        while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
    }

    private fun isHex(c: Char): Boolean =
        c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    private companion object {
        /** 容器嵌套上限：远超任何真实工具参数，只用来挡病态输入（见 [parseValue] 的深度门）。 */
        const val MAX_DEPTH = 128
    }
}

/** 面向模型（英文）：非法参数**不执行**工具，让模型按错误结果纠正后重发。 */
private fun invalidToolArgsMessage(toolName: String, raw: String): String {
    val snippet = raw.replace('\n', ' ').take(200).let { if (raw.length > 200) "$it…" else it }
    return "Invalid JSON arguments for $toolName; the tool was not executed. " +
        "Re-issue the call with a single valid JSON object. Raw arguments: $snippet"
}

// ---- JSON schema helpers (TypeBox equivalents) ----

fun strProp(description: String): JsonObject = kotlinx.serialization.json.buildJsonObject {
    put("type", kotlinx.serialization.json.JsonPrimitive("string"))
    put("description", kotlinx.serialization.json.JsonPrimitive(description))
}

fun numProp(description: String): JsonObject = kotlinx.serialization.json.buildJsonObject {
    put("type", kotlinx.serialization.json.JsonPrimitive("number"))
    put("description", kotlinx.serialization.json.JsonPrimitive(description))
}

fun boolProp(description: String): JsonObject = kotlinx.serialization.json.buildJsonObject {
    put("type", kotlinx.serialization.json.JsonPrimitive("boolean"))
    put("description", kotlinx.serialization.json.JsonPrimitive(description))
}

fun arrayProp(items: JsonObject, description: String): JsonObject = kotlinx.serialization.json.buildJsonObject {
    put("type", kotlinx.serialization.json.JsonPrimitive("array"))
    put("description", kotlinx.serialization.json.JsonPrimitive(description))
    put("items", items)
}

fun props(vararg pairs: Pair<String, JsonObject>): JsonObject = kotlinx.serialization.json.buildJsonObject {
    put("properties", kotlinx.serialization.json.buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } })
}

fun require(vararg names: String): JsonObject = kotlinx.serialization.json.buildJsonObject {
    put("required", kotlinx.serialization.json.JsonArray(names.map { kotlinx.serialization.json.JsonPrimitive(it) }))
}

fun schemaOf(propsObj: JsonObject, vararg required: String): JsonObject = kotlinx.serialization.json.buildJsonObject {
    put("type", kotlinx.serialization.json.JsonPrimitive("object"))
    propsObj["properties"]?.let { put("properties", it) }
    put("required", kotlinx.serialization.json.JsonArray(required.map { kotlinx.serialization.json.JsonPrimitive(it) }))
}

// ---- arg accessors ----

fun JsonObject.str(name: String): String? = (this[name] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content
fun JsonObject.int(name: String): Int? = str(name)?.toIntOrNull()
fun JsonObject.bool(name: String): Boolean? = (this[name] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull()
fun JsonObject.reqStr(name: String): String = str(name) ?: throw ToolException("Missing required parameter: $name")
