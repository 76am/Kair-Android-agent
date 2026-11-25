package dev.kair.app.core.ai

import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/*
 * 网络前置阶段的取消与上界（U57 主修）。
 *
 * 为什么必须在 `Dns` 这一层做：`call.cancel()` 只让 OkHttp 在**下一个检查点**放弃，
 * 而 DNS 解析是阻塞调用（`InetAddress.getByName`），取消信号进不去 —— 弱网下它可能
 * 十几分钟不返回，`AgentSession.abort()` 等不到收尾结束，`Stopping` 就永久转圈。
 *
 * 两条约束决定了做法：
 * 1. **不许给共享流式客户端加 `callTimeout`**：那个超时覆盖整个调用（含读 body），
 *    长思考会被误杀（本批验收点 V7）。
 * 2. 解析要「可取消」，而不只是「有超时」：用户点停止后应当在百毫秒量级返回，
 *    等满 5 秒才回来仍会被感知成卡住。
 *
 * 实现：解析丢到线程池，调用线程按 [POLL_MS] 轮询 —— 每轮先看当前 OkHttp 调用是否
 * 被取消（`call.cancel()` 是外部线程做的，`Dns` 没有取消钩子，所以靠
 * [CallContextInterceptor] 把调用挂到执行线程上），再看是否超预算。超时抛
 * [SocketTimeoutException]（传输层会把它归为可重试的超时），取消抛
 * `IOException("Canceled")`（与 OkHttp 自己的措辞一致，捕获层据此跳过记录）。
 *
 * 归属与生命周期（`AGENTS.md` §10.5）：解析线程池属于**进程**（daemon，随进程结束）；
 * 每次解析一个任务，结束后线程回池，不按会话累积。
 */
internal class CancellableDns(
    private val delegate: Dns = Dns.SYSTEM,
    private val budgetMs: Long = PreConnectTimeoutMs,
) : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        val task = FutureTask { delegate.lookup(hostname) }
        POOL.execute(task)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs)
        try {
            while (true) {
                if (currentCall.get()?.isCanceled() == true) throw IOException("Canceled")
                val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                if (remainingMs <= 0) {
                    throw SocketTimeoutException("DNS lookup for $hostname timed out after ${budgetMs}ms")
                }
                try {
                    return task.get(minOf(remainingMs, POLL_MS), TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    // 解析还没回来：继续轮询，下一轮再看取消 / 预算。
                } catch (e: ExecutionException) {
                    throw (e.cause as? IOException) ?: IOException(e.message, e.cause)
                }
            }
        } finally {
            // 尽力放手：调用线程已经不等了，就把任务取消掉。
            // **不许把它读成「线程回来了」** —— `cancel(true)` 只发一次中断，而 `InetAddress`
            // （Android 上是 netd 那一跳）的阻塞解析不响应中断：线程真正回池要等解析器自己返回，
            // 弱网下可能是几分钟。这里保证的是**调用方**有上界，不是线程有上界（daemon 池，进程退出即散）。
            if (!task.isDone) task.cancel(true)
        }
    }

    private companion object {
        const val POLL_MS = 100L

        /** daemon 线程：进程退出即结束，不阻止 JVM 收尾（见文件头的归属说明）。 */
        val POOL = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "kair-dns").apply { isDaemon = true }
        }
    }
}

/** 当前线程正在为哪个 OkHttp 调用服务；`Dns.lookup` 拿不到调用，只能靠它。 */
private val currentCall = ThreadLocal<Call?>()

/**
 * 把 [Call] 挂到执行线程上，供 [CancellableDns] 观察取消。
 *
 * 必须是**应用拦截器**且先于其它拦截器：DNS 解析发生在 `ConnectInterceptor` 内部，
 * 与调用同线程，此时本拦截器的 `proceed` 还在栈上。
 */
internal class CallContextInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val previous = currentCall.get()
        currentCall.set(chain.call())
        return try {
            chain.proceed(chain.request())
        } finally {
            currentCall.set(previous)
        }
    }
}

/**
 * 各会话「当前网络请求停在哪个前置阶段」。
 *
 * 只给停止看门狗定位用：硬上界触发时，诊断要说清「停在什么阶段、卡了多久」，
 * 否则用户只知道「停了」而不知道卡在哪（任务文档 §2.2）。
 *
 * 归属与生命周期：应用级（同 `RequestCapture`），每会话只保留最近一条、覆盖式；
 * 清理时机 = 会话关闭，随 `Diagnostics.remove` 一起清。
 */
internal object ConnectionPhases {

    data class Snapshot(val stage: String, val sinceMs: Long)

    private val bySession = ConcurrentHashMap<String, Snapshot>()

    fun enter(sessionId: String?, stage: String) {
        if (sessionId == null) return
        bySession[sessionId] = Snapshot(stage, System.currentTimeMillis())
    }

    fun snapshot(sessionId: String): Snapshot? = bySession[sessionId]

    /**
     * 摘掉这一会话的阶段快照。
     *
     * 两个调用方：调用结束 / 失败时由 [PhaseEventListener] 摘（**没有进行中的网络调用就不该
     * 有阶段**，否则下一次强制收口会念出上一次请求的阶段和时长 —— 那是假线索）；
     * 会话关闭时由 `Diagnostics.remove` 摘（生命周期收口）。
     */
    fun clear(sessionId: String?) {
        if (sessionId != null) bySession.remove(sessionId)
    }
}

/**
 * 把 OkHttp 的阶段回调记进 [ConnectionPhases]。
 *
 * 只记「卡住时最可能停住」的四个前置阶段：解析地址、建连、TLS 握手、等响应头，
 * 外加一个「读到响应头之后」—— 读 SSE 期间点停止是常态，那段不能继续顶着
 * 「等待响应头」报出来。
 * 不是会话发起的请求（模型探测、插件自发请求）没有 [CaptureTag]，直接跳过。
 *
 * **结束与失败必须把快照摘掉**：这张表按会话只留最近一条，卡点却在别处（不响应的工具、
 * 插件自带的传输实现）时照样会触发硬上界。留着上一次请求的阶段，诊断就会说
 * 「网络请求停在『等待响应头』，已 N 秒」—— N 还是上一次算出来的，等于给用户一条假线索。
 */
internal class PhaseEventListener(private val sessionId: String?) : EventListener() {
    override fun dnsStart(call: Call, domainName: String) = ConnectionPhases.enter(sessionId, "DNS 解析")

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) =
        ConnectionPhases.enter(sessionId, "建立连接")

    override fun secureConnectStart(call: Call) = ConnectionPhases.enter(sessionId, "TLS 握手")

    override fun responseHeadersStart(call: Call) = ConnectionPhases.enter(sessionId, "等待响应头")

    override fun responseHeadersEnd(call: Call, response: Response) =
        ConnectionPhases.enter(sessionId, "读取响应")

    override fun callEnd(call: Call) = ConnectionPhases.clear(sessionId)

    override fun callFailed(call: Call, ioe: IOException) = ConnectionPhases.clear(sessionId)
}
