package dev.kair.app.core.skills

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 一个技能（skill）：常驻的**名称 + 描述**进入系统提示索引，**正文**按需由
 * `skill` 工具读回上下文。
 *
 * 这是「渐进式披露」：模型只在判断任务相关时才付出读正文的上下文成本，
 * 因此正文既不占缓存稳定前缀，也不打扰无关回合。
 */
data class Skill(
    val name: String,
    val description: String,
    val body: String,
    /** 技能目录的绝对路径；宿主内置技能没有磁盘来源，为 null。 */
    val dir: String? = null,
)

/**
 * 解析 `SKILL.md`：可选的 YAML 风格 frontmatter（`---` 包裹的 `key: value`）+ 正文。
 *
 * - 有 frontmatter：`name` / `description` 取自其字段，缺 `name` 时回退到目录名。
 * - 无 frontmatter：整篇即正文，名称回退到目录名，描述为空。
 * - 正文为空时返回 null（视为无效技能）。
 */
object SkillParser {

    fun parse(text: String, fallbackName: String): Skill? {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val lines = normalized.split("\n")

        var index = 0
        while (index < lines.size && lines[index].isBlank()) index++

        if (index >= lines.size || lines[index].trim() != "---") {
            val body = normalized.trim()
            if (body.isEmpty()) return null
            return Skill(name = fallbackName, description = "", body = body)
        }

        index++
        val meta = mutableMapOf<String, String>()
        while (index < lines.size && lines[index].trim() != "---") {
            val line = lines[index]
            val colon = line.indexOf(':')
            if (colon > 0) {
                val key = line.substring(0, colon).trim().lowercase()
                val value = line.substring(colon + 1).trim().trim('"', '\'')
                meta[key] = value
            }
            index++
        }
        if (index < lines.size) index++ // 跳过结束的 ---

        val body = lines.drop(index).joinToString("\n").trim()
        if (body.isEmpty()) return null

        return Skill(
            name = meta["name"]?.takeIf { it.isNotBlank() } ?: fallbackName,
            description = meta["description"].orEmpty(),
            body = body,
        )
    }
}

/**
 * 技能注册表：扫描若干根目录下的 `<name>/SKILL.md`，外加宿主注入的内置技能。
 *
 * 目录约定与 Claude Skills 一致（一技能一目录 + `SKILL.md`）。多个根目录
 * 按加入顺序查找；同名技能先加入者胜出，最终结果按名称排序以保证**确定性**
 * （技能索引进入缓存稳定前缀，顺序不得随文件系统枚举顺序抖动）。
 */
class SkillRegistry {
    // 请求装配在 Dispatchers.Default 上读、插件装卸在别处写：普通 ArrayList 并发迭代会抛
    // ConcurrentModificationException，冒到系统提示装配后该回合直接失败。写少读多，用 COW。
    private val roots = CopyOnWriteArrayList<File>()
    private val builtins = CopyOnWriteArrayList<Skill>()

    fun addRoot(dir: File) {
        if (roots.none { it.absolutePath == dir.absolutePath }) roots.add(dir)
    }

    fun removeRoot(dir: File) {
        roots.removeAll { it.absolutePath == dir.absolutePath }
    }

    /** 注册宿主内置技能（不对应磁盘文件）；重复注册按名称去重。 */
    fun addBuiltin(skill: Skill) {
        if (builtins.none { it.name == skill.name }) builtins.add(skill)
    }

    fun list(): List<Skill> {
        val failures = LinkedHashMap<String, String>()
        val skills = (builtins + roots.flatMap { loadRoot(it, failures) })
            .distinctBy { it.name }
            .sortedBy { it.name }
        // 诊断是只读旁路：整表替换，坏文件修好后条目随之消失。
        loadFailures = failures
        return skills
    }

    fun find(name: String): Skill? = list().firstOrNull { it.name.equals(name, ignoreCase = true) }

    private fun loadRoot(dir: File, failures: MutableMap<String, String>): List<Skill> {
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles().orEmpty()
            .filter { it.isDirectory }
            .sortedBy { it.name }
            .mapNotNull { child ->
                val doc = File(child, SKILL_FILE)
                if (!doc.isFile) return@mapNotNull null
                // 单个技能文件不可读 / 读一半被删（EACCES、竞争删除）时跳过该文件，
                // 不能让整份技能索引构造失败。
                val parsed = runCatching { SkillParser.parse(doc.readText(), child.name) }
                val skill = parsed.getOrNull()
                if (skill == null) {
                    // 跳过不是静默：原因按技能目录名留下，供诊断面板展示。
                    failures[child.name] = parsed.exceptionOrNull()
                        ?.let { "${it.javaClass.simpleName}: ${it.message ?: "读取失败"}" }
                        ?: "$SKILL_FILE 内容为空"
                }
                skill?.copy(dir = child.absolutePath)
            }
    }

    /**
     * 最近一次扫描里被跳过的技能文件：技能目录名 → 原因。
     *
     * 只读诊断面：不参与技能索引，也不进系统提示。每次 [list] 整表替换，
     * 坏文件修好后条目随之消失（不留过期记录）。
     */
    @Volatile
    var loadFailures: Map<String, String> = emptyMap()
        private set

    companion object {
        const val SKILL_FILE = "SKILL.md"
    }
}
