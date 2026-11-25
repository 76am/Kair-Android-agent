package dev.kair.app.core.ai

import dev.kair.app.core.model.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 「点了停止却一直转圈」的机械判据。
 *
 * 现场：请求已发出、模型还没吐出第一个字节时点停止 → 界面永久转圈。触发条件与网络有关
 * （切网络 / 信号差），因为那时 socket 处于「连上了但对端不再发数据」的半死状态。
 *
 * 旧的取消链路是死锁：
 * 1. `job.cancel()` 只是发取消信号，**不会**中断阻塞的 `source.read()`；
 * 2. 唯一能中断它的是 `call.cancel()`，而它注册在 `job.invokeOnCompletion` 上
 *    —— 那个回调只在 job **完成时**触发；
 * 3. job 完成要等读循环退出，读循环退出要等 socket 关，socket 关要等 `call.cancel()`；
 * 4. 唯一的兜底（SSE 看门狗）是父协程的子协程，父协程一取消它就先死；
 * 5. `readTimeout(0)` 让 socket 层也没有超时。
 * → 环闭合，永远出不来。
 *
 * 这里用本地 `ServerSocket` 只接连接、一个字节都不发，稳定复现「连上了但没有数据」。
 * 判据：**取消后 5 秒内必须结束**（旧实现永远不结束，`withTimeout` 抛异常即红）。
 */
class SseCancelHangTest {

    @Test
    fun `cancel while waiting for the first byte ends the stream promptly`() = runBlocking {
        val server = ServerSocket(0)
        val accepted = CountDownLatch(1)
        val release = CountDownLatch(1)
        // 只 accept、不发任何字节；测试结束时 release，避免线程空转。
        thread(isDaemon = true, name = "silent-sse-server") {
            try {
                val socket: Socket = server.accept()
                accepted.countDown()
                release.await(30, TimeUnit.SECONDS)
                socket.close()
            } catch (_: Exception) {
                // 收尾时 server 已关，accept/close 抛异常属正常
            }
        }
        val config = ProviderConfig(
            providerId = "test",
            transportId = TransportIds.OPENAI_CHAT,
            baseUrl = "http://127.0.0.1:${server.localPort}",
            apiKey = "test-key",
            headers = emptyMap<String, String>(),
        )
        val request = ChatRequest(
            model = "test-model",
            systemPrompt = "",
            messages = emptyList<ChatMessage>(),
            tools = emptyList(),
        )
        val job = launch(Dispatchers.IO) {
            OpenAiProvider().stream(request, config).collect { }
        }
        try {
            assertTrue("没有与本地服务建立连接", accepted.await(5, TimeUnit.SECONDS))
            // 关键：取消后必须能在 5s 内 join 返回。旧实现会卡在 source.read() 上，
            // 这里会抛 TimeoutCancellationException → 用例红。
            withTimeout(CANCEL_BUDGET_MS) { job.cancelAndJoin() }
        } finally {
            release.countDown()
            server.close()
        }
    }

    private companion object {
        /** 取消到结束的预算：远大于一次 socket 关闭的耗时，又远小于人工能容忍的转圈。 */
        const val CANCEL_BUDGET_MS = 5_000L
    }
}
