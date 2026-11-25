package dev.kair.app.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 提示词预设库：项目绑定、停用回落、删除保护、落盘往返。
 *
 * 绑定关系刻意不写进 `Project`（plugin-api 数据类加字段会改构造签名，旧插件直接
 * `NoSuchMethodError`），所以这里也顺带守住「独立索引文件」这个决定。
 */
class PromptPresetStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = PromptPresetStore(tmp.root)

    @Test
    fun `bound preset decides the project identity`() {
        val store = store()
        val preset = store.create("聊天助手", "You are a chat companion.")
        store.bind("p-chat", preset.id)

        assertEquals(preset.id, store.boundPresetId("p-chat"))
        assertEquals("You are a chat companion.", store.effectivePreamble("p-chat"))
        assertNull("其它项目不受影响", store.effectivePreamble("p-code"))
    }

    @Test
    fun `disabled preset falls back to the builtin identity`() {
        val store = store()
        val preset = store.create("聊天助手", "You are a chat companion.")
        store.bind("p-chat", preset.id)
        store.update(preset.copy(enabled = false))

        assertEquals(preset.id, store.boundPresetId("p-chat"))
        assertNull("停用后不再贡献身份", store.effectivePreamble("p-chat"))
    }

    @Test
    fun `blank preamble falls back to the builtin identity`() {
        val store = store()
        val preset = store.create("空白预设")
        store.bind("p", preset.id)
        assertNull(store.effectivePreamble("p"))
    }

    @Test
    fun `unbind returns the project to builtin identity`() {
        val store = store()
        val preset = store.create("聊天助手", "identity")
        store.bind("p", preset.id)
        store.bind("p", null)
        assertNull(store.boundPresetId("p"))
    }

    @Test
    fun `binding a missing preset is treated as unbind`() {
        val store = store()
        store.bind("p", "does-not-exist")
        assertNull(store.boundPresetId("p"))
    }

    @Test
    fun `delete refuses a preset that a project still uses`() {
        val store = store()
        val preset = store.create("聊天助手", "identity")
        store.bind("p", preset.id)

        assertFalse(store.delete(preset.id))
        assertEquals(1, store.presets().size)
        assertEquals(listOf("p"), store.boundProjectIds(preset.id))
    }

    @Test
    fun `delete removes an unreferenced preset`() {
        val store = store()
        val preset = store.create("聊天助手", "identity")
        assertTrue(store.delete(preset.id))
        assertTrue(store.presets().isEmpty())
    }

    @Test
    fun `presets and bindings survive a new store instance`() {
        val preset = store().create("聊天助手", "identity")
        store().bind("p", preset.id)

        val reopened = store()
        assertEquals(1, reopened.presets().size)
        assertEquals("identity", reopened.effectivePreamble("p"))
    }

    @Test
    fun `corrupt file does not crash and is backed up`() {
        tmp.newFile("prompt-presets.json").writeText("{not json")
        val store = store()
        assertTrue(store.presets().isEmpty())
        assertTrue(
            "损坏文件必须留副本",
            tmp.root.listFiles()?.any { it.name.startsWith("prompt-presets.json.corrupt-") } == true,
        )
    }
}
