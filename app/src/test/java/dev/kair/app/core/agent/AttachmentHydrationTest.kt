package dev.kair.app.core.agent

import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Part
import dev.kair.app.core.model.Role
import dev.kair.app.core.storage.AttachmentStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * 请求装配期的图片**水合**（[TurnExecutor.hydrateAttachments]）。
 *
 * 为什么要这条：会话日志里的图片是**引用式**（`Part.Image.ref` 有值、`base64` 为空），本体存在
 * 内容寻址的 [AttachmentStore] 里；只有在装配请求副本时才按引用读回 base64（见
 * `docs/架构与内核边界.md`：水合只作用于请求副本、不回写历史）。这条链以前**零单测覆盖** ——
 * 把 `hydrateAttachments` 改成空操作，全量用例一条都不红（批次 4b 评审时门卫实测），
 * 意味着「发出去的图悄悄变成空引用」不会有任何机械信号。本用例就是那个信号。
 *
 * 仓库里已有的 ref-only 图片走的是**压缩与存储**路径，不经过请求装配，所以盖不到这里。
 */
class AttachmentHydrationTest {

    /** 合法 1×1 PNG，可被 `Base64.getDecoder()` 解码（水合链要求本体是真字节）。 */
    private val pngBase64 =
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="

    @Test
    fun `request assembly hydrates a ref-only image back to base64`() = runBlocking {
        val workspace = Files.createTempDirectory("kair-hydrate").toFile()
        val store = AttachmentStore.forWorkspace(workspace)
        // 把本体落进内容寻址存储，拿到真实引用 —— 与「发送图片时被转成 ref-only」的落盘形态一致。
        val ref = store.put(pngBase64, "image/png")
        assertNotNull("附件落地应返回内容寻址引用", ref)
        // 水合出的应当就是存储里的这份字节（内容寻址 → 逐字节稳定，不破坏缓存前缀）。
        val expected = store.read(ref!!)
        assertNotNull("存储里应能按引用读回本体", expected)

        val transport = ScriptedTransport(listOf(ScriptedTurn.text("ok")))
        val h = harness(transport, workspace = workspace, attachments = store)
        try {
            // 历史里放一条**引用式**图片（base64 已剥离），正是日志持久化的形态。
            h.session.loadHistory(
                listOf(
                    ChatMessage(
                        role = Role.USER,
                        parts = listOf(Part.Text("看图"), Part.Image(ref = ref, base64 = null)),
                    ),
                ),
            )
            h.runTurn("这张图是什么？")

            assertEquals("应恰好发出一次请求", 1, h.requests.size)
            val images = h.requests.single().messages.flatMap { it.images }
            assertTrue("装配出的请求里应含图片部件", images.isNotEmpty())
            val sent = images.first { it.ref == ref }
            assertNotNull(
                "引用式图片必须在请求装配时水合回 base64（TurnExecutor.hydrateAttachments）；" +
                    "为空说明水合没生效，发出去的图会是空引用",
                sent.base64,
            )
            assertTrue("水合出的 base64 不得为空白", sent.base64!!.isNotBlank())
            assertEquals("水合出的字节应与存储里的本体一致", expected, sent.base64)
        } finally {
            h.close()
        }
    }
}
