package dev.kair.app.ui.plugins.builtin

import dev.kair.app.core.plugin.InMemoryPluginSettingsStore
import dev.kair.app.core.tools.DirEntry
import dev.kair.app.core.tools.FileBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * U38 · 回收站阈值判据与清理结果。
 *
 * `selectTrashPurge` 是纯函数（不碰磁盘、不读时钟），所以三条上限的组合能在 JVM 里逐条钉；
 * `purgeTrash` 用假后端，专门测「删不动时不许谎报已清空」这条安全底线。
 */
class TrashMaintenanceTest {

    private val day = 24L * 60 * 60 * 1000

    private fun item(name: String, ageDays: Long, sizeBytes: Long = 0) = TrashItem(
        path = "/trash/$name",
        isDirectory = true,
        lastModifiedMs = NOW - ageDays * day,
        sizeBytes = sizeBytes,
    )

    @Test
    fun `age limit takes everything strictly older than the boundary`() {
        val items = (1L..5L).map { item("d$it", ageDays = it) }
        val picked = selectTrashPurge(items, TrashLimits(maxAgeDays = 3, maxItems = 0, maxSizeBytes = 0), NOW)

        // 边界那一格：age 恰好 3 天的不算超期（`<` 不是 `<=`），断言把两侧都钉住。
        assertEquals(listOf("/trash/d5", "/trash/d4"), picked.map { it.path })
        assertFalse("恰好等于上限的不该被当过期", picked.any { it.path == "/trash/d3" })
    }

    @Test
    fun `count limit alone triggers from the oldest without any age rule`() {
        val items = (1L..6L).map { item("d$it", ageDays = 1) }
        val picked = selectTrashPurge(items, TrashLimits(maxAgeDays = 0, maxItems = 4, maxSizeBytes = 0), NOW)
        // 只开条数：保留最新 4 条，多出的 2 条按最旧优先摘掉；年龄 1 天不该参与。
        assertEquals(listOf("/trash/d1", "/trash/d2"), picked.map { it.path })
    }

    @Test
    fun `size limit alone triggers from the oldest without any count rule`() {
        val items = listOf(item("a", 1, 10), item("b", 2, 10), item("c", 3, 10))
        val picked = selectTrashPurge(items, TrashLimits(maxAgeDays = 0, maxItems = 0, maxSizeBytes = 15), NOW)
        // 总量 30 > 15，从最旧开始摘：c 是 3 天前（最旧）→ b 是 2 天前，摘到这两条就降到 10 ≤ 15。
        // 「最旧」看 lastModified 而不是名字顺序 —— 名字 a/b/c 恰好与年龄反向，就是为了盯这一点。
        assertEquals(listOf("/trash/c", "/trash/b"), picked.map { it.path })
        assertEquals("摘两条就已达标，不该多删", 2, picked.size)
    }

    @Test
    fun `zero on a rule means that rule deletes nothing`() {
        val items = (1L..100L).map { item("d$it", ageDays = 400) }
        // 三条全 0 = 一条都不启用：400 天前的 100 条也原样留着。
        assertEquals(0, selectTrashPurge(items, TrashLimits(0, 0, 0), NOW).size)
        assertEquals("只开条数时年龄不参与", 90, selectTrashPurge(items, TrashLimits(0, 10, 0), NOW).size)
    }

    @Test
    fun `age count and size compose with expired removed before the other two`() {
        val items = listOf(item("old1", 40, 5), item("old2", 40, 5), item("mid", 2, 5), item("new", 1, 5))
        val picked = selectTrashPurge(items, TrashLimits(maxAgeDays = 7, maxItems = 1, maxSizeBytes = 5), NOW)
        // 先摘两条过期 → 剩 mid/new → 条数留最新一条 → 体积 5 已达标。
        assertEquals(listOf("/trash/old1", "/trash/old2", "/trash/mid"), picked.map { it.path })
    }

    @Test
    fun `purgeTrash reports residue instead of claiming success`() {
        val backend = SilentFailBackend(vanishes = setOf("/trash/a"))
        val result = kotlinx.coroutines.runBlocking {
            purgeTrash(backend, listOf("/trash/a", "/trash/b", "/trash/c"))
        }
        assertEquals(3, result.requested)
        // 假后端对 b/c 静默返回（今天的真形状），但文件还在 → 必须算失败。
        assertEquals("只有真消失的才算删掉", 1, result.deleted)
        assertEquals(2, result.failed)
        assertEquals(listOf("/trash/b", "/trash/c"), result.failedPaths)
        assertFalse("有残留就不许报已清空", result.fullyCleared)
    }

    @Test
    fun `purgeTrash counts a thrown backend as failed and keeps going`() {
        val backend = ThrowingBackend()
        val result = kotlinx.coroutines.runBlocking { purgeTrash(backend, listOf("/trash/x", "/trash/y")) }
        assertEquals(0, result.deleted)
        assertEquals(2, result.failed)
        assertEquals("异常不能冒出去打断整批清理", 2, backend.attempts)
    }

    @Test
    fun `scanTrash lists top level entries and sums directory size`() {
        val root = Files.createTempDirectory("kair-trash-scan").toFile()
        val entry = root.resolve("20261105-120000")
        assertTrue(entry.mkdirs())
        entry.resolve("a.txt").writeText("12345")
        entry.resolve("b.txt").writeText("678")
        root.resolve("loose.txt").writeText("x")

        val withSizes = scanTrash(root, needSizes = true).sortedBy { it.path }
        assertEquals(2, withSizes.size)
        val dir = withSizes.first { it.isDirectory }
        val loose = withSizes.first { !it.isDirectory }
        assertEquals(8L, dir.sizeBytes)
        assertEquals(1L, loose.sizeBytes)

        // 关掉体积时填 0，但条数与年龄照旧 —— 这是「几十万条时先靠条数与年龄收」的前提。
        val noSizes = scanTrash(root, needSizes = false)
        assertEquals(2, noSizes.size)
        assertTrue(noSizes.all { it.sizeBytes == 0L })

        root.deleteRecursively()
    }

    @Test
    fun `limits convert settings units and clamp negatives`() {
        val limits = trashLimits(maxAgeDays = -5, maxItems = 2000, maxSizeMb = 2048)
        assertEquals(0, limits.maxAgeDays)
        assertEquals(2000, limits.maxItems)
        assertEquals(2048L * 1024 * 1024, limits.maxSizeBytes)
        assertEquals("MB 填 0 表示不启用体积判据", 0L, trashLimits(7, 10, 0).maxSizeBytes)
    }

    @Test
    fun `sweep deletes only entries under the trash root`() {
        val root = Files.createTempDirectory("kair-trash-root").toFile()
        val outside = Files.createTempDirectory("kair-trash-outside").toFile()
        val doomed = root.resolve("20261101-old").apply { mkdirs() }
        doomed.resolve("a.txt").writeText("1")
        val insideFresh = root.resolve("20261105-new").apply { mkdirs() }
        val outsideFile = outside.resolve("keep.txt").apply { writeText("keep") }
        // 把旧条目推到 40 天前，新条目留在 NOW：只有旧的该走。
        assertTrue(doomed.setLastModified(NOW - 40 * day))
        assertTrue(insideFresh.setLastModified(NOW))

        val recorder = RecordingBackend(vanishes = setOf(doomed.path))
        val result = kotlinx.coroutines.runBlocking {
            sweepTrash(root, TrashLimits(maxAgeDays = 7, maxItems = 0, maxSizeBytes = 0), recorder, NOW)
        }

        assertEquals(1, result.requested)
        assertEquals(listOf(doomed.path), recorder.deleted)
        assertEquals(1, result.deleted)
        assertFalse("回收站之外的文件不许出现在删除调用里", recorder.deleted.any { it.startsWith(outside.path) })
        assertTrue("工作区侧的文件必须原样还在", outsideFile.exists())
        root.deleteRecursively()
        outside.deleteRecursively()
    }

    @Test
    fun `off rules never consult their own measure`() {
        // 三条全 0：体积未知（这里给的是 0）也不该删任何东西。
        // 「关掉就不做递归求和」是 sweepTrash 传 needSizes 的取舍，由 scanTrash 那条用例从另一头钉。
        val items = listOf(item("a", 1, 0), item("b", 2, 0))
        val picked = selectTrashPurge(items, TrashLimits(0, 0, 0), NOW)
        assertEquals("三条都关时即便体积未知也不删", emptyList<String>(), picked.map { it.path })
    }

    @Test
    fun `clearing all deletes every top level entry and nothing deeper`() {
        val root = Files.createTempDirectory("kair-trash-empty").toFile()
        val dir = root.resolve("20261105-a").apply { mkdirs() }
        val inner = dir.resolve("b.txt").apply { writeText("x") }
        val loose = root.resolve("c.txt").apply { writeText("y") }

        val recorder = RecordingBackend(vanishes = setOf(dir.path, loose.path))
        val result = kotlinx.coroutines.runBlocking { emptyTrash(root, recorder) }

        assertEquals(2, result.requested)
        assertEquals("只清顶层条目：目录整体交给后端递归删", setOf(dir.path, loose.path), recorder.deleted.toSet())
        assertTrue("一键清空要如实报已清空", result.fullyCleared)
        root.deleteRecursively()
    }

    @Test
    fun `partial residue is reported as partial not as cleared`() {
        val root = Files.createTempDirectory("kair-trash-partial").toFile()
        val dir = root.resolve("20261105-a").apply { mkdirs() }
        val stuck = root.resolve("20261105-b").apply { mkdirs() }

        // 只有 a 真的消失：b 静默失败（今天 ContainerFileBackend 对回收站的形状）。
        val recorder = RecordingBackend(vanishes = setOf(dir.path))
        val result = kotlinx.coroutines.runBlocking { emptyTrash(root, recorder) }

        assertEquals(2, result.requested)
        assertEquals(1, result.deleted)
        assertEquals(1, result.failed)
        assertFalse("有残留就不能报已清空", result.fullyCleared)
        root.deleteRecursively()
    }

    @Test
    fun `settings round-trip gives the documented defaults`() {
        val store = dev.kair.app.core.plugin.InMemoryPluginSettingsStore()
        // 门控：开关默认「关」时读上来是三条全 0（由 `the master switch off…` 单独钉），
        // 要看三条阈值自己的默认值就得先把开关打开 —— 这一步同时证明默认值与门控是两套数据。
        store.setBoolean(TRASH_KEY_ENABLED, true)
        // 没写过任何阈值时读到的必须是 §3 那张表的默认：默认值走偏是这类批次最常见的静默回归。
        val defaults = trashLimitsOf(store)
        assertEquals(7, defaults.maxAgeDays)
        assertEquals(2000, defaults.maxItems)
        assertEquals(2048L * 1024 * 1024, defaults.maxSizeBytes)

        // 写进去要能原样读回（Choice 的保留时长落库是字符串，另两条是整数）。
        store.setString(TRASH_KEY_MAX_AGE_DAYS, "15")
        store.setInt(TRASH_KEY_MAX_ITEMS, 500)
        store.setInt(TRASH_KEY_MAX_SIZE_MB, 0)
        val edited = trashLimitsOf(store)
        assertEquals(15, edited.maxAgeDays)
        assertEquals(500, edited.maxItems)
        assertEquals("体积填 0 = 这条不启用", 0L, edited.maxSizeBytes)
    }

    @Test
    fun `the master switch off makes every rule inactive`() {
        // 本批最贵的一种错：默认「关」的开关没被读，用户以为没开、回收站里的东西却在被删。
        // 空存储 = 用户从没碰过开关，所以三条必须全 0；而且带着 400 天前的真条目也不能动一条。
        val limits = trashLimitsOf(dev.kair.app.core.plugin.InMemoryPluginSettingsStore())
        assertTrue("开关关着时三条都必须不启用：$limits", limits.allOff)

        val root = Files.createTempDirectory("kair-trash-off").toFile()
        val ancient = root.resolve("20260101-old").apply { mkdirs() }
        ancient.resolve("f.txt").writeText("x")
        assertTrue(ancient.setLastModified(NOW - 400 * day))

        val recorder = RecordingBackend(vanishes = setOf(ancient.path))
        val result = kotlinx.coroutines.runBlocking { sweepTrash(root, limits, recorder, NOW) }
        assertEquals("关着就一条都不许动", emptyList<String>(), recorder.deleted)
        assertEquals(0, result.requested)
        assertTrue("400 天前的条目必须原样还在", ancient.exists())
        root.deleteRecursively()
    }

    @Test
    fun `the age choice can be turned off on its own`() {
        // 三条独立触发：选「不限制」（value 0）时年龄这条不参与，另两条照旧。
        val store = dev.kair.app.core.plugin.InMemoryPluginSettingsStore()
        store.setBoolean(TRASH_KEY_ENABLED, true)
        store.setString(TRASH_KEY_MAX_AGE_DAYS, "0")
        store.setInt(TRASH_KEY_MAX_ITEMS, 2)
        val limits = trashLimitsOf(store)
        val items = (1L..3L).map { item("d$it", ageDays = 400) }
        val picked = selectTrashPurge(items, limits, NOW)
        assertEquals("三条同龄时按路径定序，只多出一条 → 删最旧那条", listOf("/trash/d1"), picked.map { it.path })
    }

    private companion object {
        const val NOW = 1_760_000_000_000L
    }
}

/** 记下每次删除调用的路径；[vanishes] 里的路径在复查时「已消失」，其余照旧存在。 */
private class RecordingBackend(private val vanishes: Set<String>) : FileBackend {
    val deleted = ArrayList<String>()

    override fun resolve(path: String): String = path
    override suspend fun exists(path: String) = path !in vanishes
    override suspend fun isDirectory(path: String) = true
    override suspend fun readText(path: String) = ""
    override suspend fun writeText(path: String, content: String) {}
    override suspend fun delete(path: String) {
        deleted.add(path)
    }

    override suspend fun list(path: String): List<DirEntry> = emptyList()
    override suspend fun mkdir(path: String) {}
    override suspend fun copy(from: String, to: String) {}
    override suspend fun move(from: String, to: String) {}
    override suspend fun walk(root: String, skipDirs: Set<String>) = emptyList<String>()
    override fun relative(root: String, path: String) = path
}

/**
 * 删不动时**静默返回**的后端：`FileBackend.delete` 本来就没有返回值，删没删掉调用方无从得知
 * —— 这正是「一键清空」敢对着残留报「已清空」的原因（回收站条目具体怎么空转见任务文档 §6 第 1 条，
 * 那条是代码级推断、未上真机）。[vanishes] 里的路径「删得掉」。
 */
private class SilentFailBackend(private val vanishes: Set<String>) : FileBackend {
    override fun resolve(path: String): String = path
    override suspend fun exists(path: String) = path !in vanishes
    override suspend fun isDirectory(path: String) = true
    override suspend fun readText(path: String) = ""
    override suspend fun writeText(path: String, content: String) {}

    /** 关键就是这一具「什么都不做」：只有 vanishes 里的路径会在复查时消失，靠的是 exists 的口径。 */
    override suspend fun delete(path: String) {}

    override suspend fun list(path: String): List<DirEntry> = emptyList()
    override suspend fun mkdir(path: String) {}
    override suspend fun copy(from: String, to: String) {}
    override suspend fun move(from: String, to: String) {}
    override suspend fun walk(root: String, skipDirs: Set<String>) = emptyList<String>()
    override fun relative(root: String, path: String) = path
}

/** 每次删除都抛异常的后端，同时数一下有没有因为一条失败就中断整批。 */
private class ThrowingBackend : FileBackend {
    var attempts = 0
        private set

    override fun resolve(path: String): String = path
    override suspend fun exists(path: String) = true
    override suspend fun isDirectory(path: String) = true
    override suspend fun readText(path: String) = ""
    override suspend fun writeText(path: String, content: String) {}

    override suspend fun delete(path: String) {
        attempts++
        throw java.io.IOException("device busy: $path")
    }

    override suspend fun list(path: String): List<DirEntry> = emptyList()
    override suspend fun mkdir(path: String) {}
    override suspend fun copy(from: String, to: String) {}
    override suspend fun move(from: String, to: String) {}
    override suspend fun walk(root: String, skipDirs: Set<String>) = emptyList<String>()
    override fun relative(root: String, path: String) = path
}
