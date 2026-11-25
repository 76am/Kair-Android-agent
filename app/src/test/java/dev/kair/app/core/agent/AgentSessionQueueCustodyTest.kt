package dev.kair.app.core.agent

import dev.kair.app.core.ai.StreamEvent
import dev.kair.app.core.model.Part
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * U21 判据本体：**abort 精确落进「已取出、未提交」的窗口时，消息要么在历史、要么还在队列，
 * 不许静默消失**。
 *
 * 窗口的钉缝：内联图片入库前的缩放（`ImageDownscaler.attach` 是 `append` 里提交前的挂起点）。
 * 假缩放器第一次进入即开门等放行 —— 此刻消息已被排空机制「起用」但还没进历史。
 * 旧写法（`takeNextAfterStop` 取出即离队）在这里队列已空、历史无，abort 一落就两头都没有。
 *
 * 本批语义（在途 = 已起用）：
 * - 在途条目**不在队列** —— 界面撤回判据「在队列 = 还没起用」保持成立
 *   （`ChatScreen` / `Composer` 的撤回入口只对在队列条目开，B 审 U21 遗留 P1 就钉在这里）；
 * - abort 不清在途条目（清的是没说出口的排队输入），取消后**归还队首**、由既有排空机制接上 ——
 *   与「回合已起、用户消息留在历史」的既有停止语义同向。
 */
class AgentSessionQueueCustodyTest {

    /**
     * 首次进入即钉开缝（等放行），后续调用直接放行 —— 归还后的重投不能再被卡住。
     * 用阻塞门闩（[ImageDownscaler.Scaler] 不是 suspend）：取消在 `withContext(IO)` **出块处**
     * 咬住 —— 测试先注册 abort 再放行，取消必然落在「缩放完、提交前」的缝里。
     */
    private class GatingScaler {
        private val first = AtomicBoolean(true)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val scaler = ImageDownscaler.Scaler { _, _, _ ->
            if (first.compareAndSet(true, false)) {
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
            null
        }
    }

    private class Scenario(gating: GatingScaler) {
        val transport = ScriptedTransport(
            listOf(
                ScriptedTurn.hanging(StreamEvent.TextDelta("half")),
                ScriptedTurn.text("ok"),
            ),
            id = "u21-custody-transport",
            imagePixelCap = 1L,
        )
        val harness = harness(
            transport = transport,
            sessionId = "u21-custody-session",
            // 带内联图会真落附件 —— 用临时目录，不污染仓库树。
            workspace = Files.createTempDirectory("kair-u21-custody").toFile(),
            imageScaler = gating.scaler,
        )
    }

    /** 走到「abort 精确落进窗口」的前一刻：第一回合挂住 → 停止 → 抢发带图消息 → 提交卡在入库前。 */
    private suspend fun reachWindow(gating: GatingScaler): Scenario {
        val scenario = Scenario(gating)
        val session = scenario.harness.session
        session.send("first")
        await("第一回合的请求已发出") { scenario.transport.requests.isNotEmpty() }
        session.abort()
        // 收尾期间抢发：Stopping 一律入队（FOLLOW_UP），收尾结束后由排空机制起用。
        session.send("second", listOf(Part.Image(base64 = "AA==", mediaType = "image/png")))
        await("排空已起用这条消息、提交卡在入库前（窗口钉开）") { gating.entered.count == 0L }
        return scenario
    }

    @Test
    fun abortLandsInCommitWindowAndMessageIsEitherInHistoryOrBackInQueue() = runBlocking {
        val gating = GatingScaler()
        val scenario = reachWindow(gating)
        val session = scenario.harness.session
        try {
            // 窗口正中：既不在历史（没提交）也不在队列（已起用、受托中）——
            // 这格是「在途」状态，下两行同时钉住窗口本身。
            assertTrue("窗口正中消息不得已在历史里", session.messages.value.none { it.text.contains("second") })
            assertTrue(
                "在途条目不得留在队列里（撤回判据「在队列=没起用」要保持成立）",
                session.queue.value.none { it.message.text.contains("second") },
            )

            // abort 精确落进窗口。
            session.abort()
            gating.release.countDown()

            // U21 判据：消息要么在历史、要么还在队列 —— 不许静默消失。
            await("U21 判据：消息要么在历史、要么还在队列（不许静默消失）") {
                session.messages.value.any { it.text.contains("second") } ||
                    session.queue.value.any { it.message.text.contains("second") }
            }
            // settle（B 审 P2-1）：OR 谓词可能命中「已归还未进历史」的中间态，count 断言
            // 读 0 会红成可误读的「恰好一次」——先等终态再断言，丢失仍必超时红。
            await("消息最终应进历史恰好一次、队列清空") {
                session.messages.value.count { it.text.contains("second") } == 1 &&
                    session.queue.value.none { it.message.text.contains("second") }
            }
            // 恰好一次：归还不许造成重投双份。
            assertEquals(
                "消息恰好进历史一次",
                1,
                session.messages.value.count { it.text.contains("second") },
            )
            // 本批语义：在途 = 已起用，取消后归还队首、排空机制接上 → 最终进历史。
            assertTrue(
                "在途消息被 abort 打断后应归还并接上（与「回合起了、用户消息留历史」同向）",
                session.messages.value.any { it.text.contains("second") },
            )
            assertTrue("队列不许留尾巴", session.queue.value.isEmpty())
        } finally {
            scenario.harness.close()
        }
    }

    /**
     * 同一判据换入口：**send 路径**（Idle 直发、消息由 `runUser` 的参数捎带）也要受托。
     * 旧写法把消息关在 `launchRun` 的 lambda 里 —— abort 落进「已取消、还没起跑」或
     * 「起跑了、提交前」的窗口时，消息既不在历史也不在队列，**静默消失**
     * （收尾卡死批的取证见证：探测器红快照同一窗口的另一张脸）。
     */
    @Test
    fun abortInStartupWindowLandsSendPathMessageInHistoryOrQueue() = runBlocking {
        val gating = GatingScaler()
        val scenario = Scenario(gating)
        val session = scenario.harness.session
        try {
            session.send("go", listOf(Part.Image(base64 = "AA==", mediaType = "image/png")))
            await("send 路径的提交卡在入库前（窗口钉开）") { gating.entered.count == 0L }
            session.abort()
            gating.release.countDown()

            await("send 路径消息同样不许静默消失：要么在历史、要么还在队列") {
                session.messages.value.any { it.text.contains("go") } ||
                    session.queue.value.any { it.message.text.contains("go") }
            }
            // settle（B 审 P2-1）：同上，防 OR 谓词命中中间态把「恰好一次」红成误读。
            await("消息最终应进历史恰好一次、队列清空") {
                session.messages.value.count { it.text.contains("go") } == 1 &&
                    session.queue.value.none { it.message.text.contains("go") }
            }
            assertEquals(
                "消息恰好进历史一次",
                1,
                session.messages.value.count { it.text.contains("go") },
            )
        } finally {
            scenario.harness.close()
        }
    }

    /**
     * B 审 U21 遗留 P1 的排除钉子：撤回 / 改道以「消息仍在队列」为判据
     * （`ChatScreen` / `Composer` 的撤回入口）。在途条目必须**不在**队列里 ——
     * 否则「在队列」不再等价「没被起用」，窗口内的撤回会静默失效。
     */
    @Test
    fun inFlightMessageIsNotOfferedForRecall() = runBlocking {
        val gating = GatingScaler()
        val scenario = reachWindow(gating)
        val session = scenario.harness.session
        try {
            assertTrue(
                "在途条目不得出现在 queue.messages（撤回判据的「在队列」必须只含没起用的）",
                session.queue.value.none { it.message.text.contains("second") },
            )
        } finally {
            session.abort()
            gating.release.countDown()
            scenario.harness.close()
        }
    }
}
