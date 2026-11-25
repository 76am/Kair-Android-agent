package dev.kair.app.ui.plugins.builtin

import android.content.Context
import android.os.Build
import android.sun.security.x509.CertAndKeyGen
import android.sun.security.x509.X500Name
import dev.kair.app.core.device.AdbOutcome
import dev.kair.app.core.device.PhantomKiller
import dev.kair.app.core.device.ScreenOutcome
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Locale

/**
 * U90/U91 · 内置 adb 客户端（libadb-android，LADB 同款纯 JVM）。对外三件事：
 * 配对授权、连接跑命令、**设备 shell**（回环自连，供 agent 工具用）。
 *
 * 密钥落盘在应用私有目录（`files/phantom-adb/`），配对信任建立后下次只需连接。
 * 授权是否成功不靠解析 shell 输出，而由调用方查 `PhantomKillerStore.isGranted` 确认。
 */
class AdbLink(private val context: Context) {
    /** 单次配对/连接/授权的硬上界，避免等待用户的路径无限挂起（AGENTS.md §7）。 */
    private val timeoutMs = 30_000L

    /** 密钥与证书是否已落盘（不代表配对成功——它们在构造管理器时就生成）。 */
    fun hasKeys(): Boolean = File(adbDir(context), KEY_FILE).isFile &&
        File(adbDir(context), CERT_FILE).isFile

    /**
     * 是否配对成功过，据此决定面板走「直接连接」还是「配对」，也是 `device_shell` 的注册闸。
     *
     * 不能只看密钥文件在不在：密钥是 [KairAdbManager] 构造时（即点连接之前）就生成落盘的，
     * 从未配对成功的机器上也有。所以以 [PAIRED_MARK] 为准 —— 只有 `pair()` 真的返回成功才写。
     */
    fun hasPairedKeys(): Boolean = isPaired(context)

    /** 上次连成功的地址（`IP:端口`），用于预填；没有则 null。 */
    fun lastConnectAddress(): String? = File(adbDir(context), LAST_HOST_FILE)
        .takeIf { it.isFile }
        ?.runCatching { readText() }
        ?.getOrNull()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    /**
     * 清掉本机全部配对材料（密钥 / 证书 / 配对标记 / 上次地址），下次回到配对流程。
     *
     * 系统侧「已配对设备」里的 Kair 条目不会因此消失——那是 Settings 的数据，只能在系统界面删。
     * 两边都清掉才算真正重来，UI 文案里要说清。
     */
    fun clearPaired() {
        // 密钥一清，常驻会话手里的授权凭据就不再可信：先断，下次调用会重新走配对前的判定。
        AdbSession.disconnectNow()
        val dir = adbDir(context)
        listOf(KEY_FILE, CERT_FILE, PAIRED_MARK, LAST_HOST_FILE).forEach {
            runCatching { File(dir, it).delete() }
        }
    }

    /**
     * 预热常驻连接：把 adb 会话先建起来，之后每次工具调用都复用它。
     *
     * 插件激活时调一次（`ctx.scope`，卸载即取消）。失败**静默**：无线调试可能压根没开，
     * 那不是错误，工具调用时会自己返回可操作的提示。用户要的是「已授权就一直保持连接」，
     * 而不是每次调用握手一次、系统那个「USB 调试已连接」跟着一明一灭。
     */
    suspend fun prewarm() {
        if (!hasPairedKeys()) return
        val target = AdbSession.liveTarget() ?: resolveTarget() ?: return
        runCatching { AdbSession.withConnection(context, target) { } }
    }

    /**
     * **设备 shell**（U91）：自己连自己的 adbd，跑一条命令。
     *
     * 地址是回环 `127.0.0.1:<adbd TLS 端口>`——真机已证 adbd 监听全部接口、回环可连
     * （U91 任务书 §8.1 ②），因此不占用局域网、不需要用户抄端口、不依赖容器。
     *
     * 连接走 [AdbSession] **跨调用复用**（不再一次调用一次握手，见那里的 KDoc）。
     *
     * 失败一律折成 [AdbOutcome.Failed]，不抛异常：工具层要能据 reason 给可操作的提示。
     */
    suspend fun shell(command: String, timeout: Long = timeoutMs): AdbOutcome {
        if (!hasPairedKeys()) return AdbOutcome.Failed(AdbOutcome.Reason.NOT_PAIRED)
        val target = AdbSession.liveTarget() ?: resolveTarget() ?: return AdbOutcome.Failed(AdbOutcome.Reason.NO_PORT)
        val address = "${target.first}:${target.second}"
        return try {
            withTimeout(timeout) {
                withContext(Dispatchers.IO) {
                    AdbSession.withConnection(context, target) { manager ->
                        AdbOutcome.Ok(runShell(manager, command))
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            // 一条命令没回来，这条流就不能再用了：后面排队的调用不该跟着卡死在同一处。
            AdbSession.disconnectNow()
            AdbOutcome.Failed(AdbOutcome.Reason.TIMEOUT, address)
        } catch (_: AdbConnectException) {
            AdbOutcome.Failed(AdbOutcome.Reason.CONNECT_FAILED, address)
        } catch (e: Exception) {
            AdbSession.disconnectNow()
            AdbOutcome.Failed(AdbOutcome.Reason.IO, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * **设备截屏**（U91 `device_screen`）：自己连自己，`exec:screencap -p` 取一张 PNG。
     *
     * **为什么是原始字节而不是 base64**（2026-11-16 真机翻案）：上一版图省事走
     * `screencap -p | base64` + 回程解码，理由是「shell 通道会做行尾转换，base64 对通道行为
     * 不敏感」。真机跑出来是 `screencap output is not base64` —— 那层保险加错了地方：
     * libadb 的 [io.github.muntashirakon.adb.AdbStream] 是把 A_WRTE 的 payload **原样**交出来的
     * 裸字节流（`addPayload` 只入队、`notifyClose` 还会用 `mPendingClose` 保住在途数据），
     * 根本不做 pty 的 `\n` → `\r\n` 转换。CRLF 那个经典坑是 **host adb 的 pty** 才有的，
     * 这里既没有 pty（用 `exec:` 更明确），也不该有文本解码。
     *
     * 所以直接读字节：既绕开 base64 这一层可能出错的编解码，也省掉 33% 的传输膨胀。
     * 失败时把前 8 字节打成 hex 一起报出来 —— 通道要是真坏了（错误文本 / 被 CRLF 改写的
     * PNG 头 / 截断），一眼能分清，不用再猜。
     *
     * 尺寸从 PNG 的 IHDR 读，不另外查 `wm size`：截图像素**就是** `input tap/swipe` 的坐标系
     * （input 用当前显示方向的坐标），旋转时宽高自然跟着换，不需要再判断朝向。
     */
    suspend fun screenshot(timeout: Long = timeoutMs): ScreenOutcome {
        if (!hasPairedKeys()) return ScreenOutcome.Failed(AdbOutcome.Reason.NOT_PAIRED)
        val target = AdbSession.liveTarget() ?: resolveTarget() ?: return ScreenOutcome.Failed(
            AdbOutcome.Reason.NO_PORT,
        )
        val address = "${target.first}:${target.second}"
        return try {
            withTimeout(timeout) {
                withContext(Dispatchers.IO) {
                    AdbSession.withConnection(context, target) { manager ->
                        parsePng(readBytes(manager, SCREENSHOT_SERVICE))
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            AdbSession.disconnectNow()
            ScreenOutcome.Failed(AdbOutcome.Reason.TIMEOUT, address)
        } catch (_: AdbConnectException) {
            ScreenOutcome.Failed(AdbOutcome.Reason.CONNECT_FAILED, address)
        } catch (e: Exception) {
            AdbSession.disconnectNow()
            ScreenOutcome.Failed(AdbOutcome.Reason.IO, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** 配对并授权：pair → connect → shell(pm grant) → 断开。返回 shell 输出供错误展示。 */
    suspend fun pairAndGrant(
        pairHost: String,
        pairPort: Int,
        pairingCode: String,
        connectHost: String,
        connectPort: Int,
    ): String = withTimeout(timeoutMs) {
        withContext(Dispatchers.IO) {
            // 重新配对后旧会话的凭据一定作废，先断掉，别让它继续以旧密钥跑命令。
            AdbSession.disconnectNow()
            val manager = KairAdbManager(context)
            try {
                check(manager.pair(pairHost, pairPort, pairingCode)) { "pair returned false" }
                // 配对真成功才落标记：密钥文件本身在构造时就写了，不能当配对凭据。
                rememberPaired()
                check(manager.connect(connectHost, connectPort)) { "connect returned false" }
                rememberConnectAddress(connectHost, connectPort)
                runShell(manager, PhantomKiller.grantCommand(context.packageName))
            } finally {
                runCatching { manager.disconnect() }
                runCatching { manager.close() }
            }
        }
    }

    /**
     * 原始字节 → PNG + IHDR 宽高。空输出 / 非 PNG 都折成可判定的失败，并把**前 8 字节打成
     * hex** 带进 detail：通道真出问题时（错误文本混入、PNG 头被改写、传输截断），
     * `89 50 4e 47` 在不在、后面接的是什么，一眼就能分清，不用再靠猜和二次真机。
     */
    private fun parsePng(bytes: ByteArray): ScreenOutcome {
        if (bytes.isEmpty()) {
            return ScreenOutcome.Failed(AdbOutcome.Reason.IO, "screencap returned nothing")
        }
        val (w, h) = pngSize(bytes) ?: return ScreenOutcome.Failed(
            AdbOutcome.Reason.IO,
            "screencap output is not a PNG: ${bytes.size} bytes, head=${hexPrefix(bytes)}",
        )
        return ScreenOutcome.Ok(bytes, w, h)
    }

    /** 前 [count] 字节的 hex（`89 50 4e 47 …`），给失败信息用。 */
    private fun hexPrefix(bytes: ByteArray, count: Int = 8): String =
        bytes.take(count).joinToString(" ") { "%02x".format(Locale.ROOT, it.toInt() and 0xFF) }

    /** 读 PNG 的 IHDR：签名 8 字节 + 块长 4 + `IHDR` 4，随后宽、高各 4 字节大端。 */
    private fun pngSize(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < PNG_HEADER_BYTES) return null
        if (!bytes.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)) return null
        val w = ByteBuffer.wrap(bytes, 16, 4).int
        val h = ByteBuffer.wrap(bytes, 20, 4).int
        return if (w > 0 && h > 0) w to h else null
    }

    /** 已配对后重连跑一条命令；返回 shell 输出。配对端口是临时的，重连只需连接地址。 */
    suspend fun connectAndRun(host: String, port: Int, command: String): String = withTimeout(timeoutMs) {
        withContext(Dispatchers.IO) {
            // 用户点「重新连接」就是要刷新连接：先丢掉常驻会话，避免它继续用在旧端口上。
            AdbSession.disconnectNow()
            val manager = KairAdbManager(context)
            try {
                check(manager.connect(host, port)) { "connect returned false" }
                rememberPaired()
                rememberConnectAddress(host, port)
                runShell(manager, command)
            } finally {
                runCatching { manager.disconnect() }
                runCatching { manager.close() }
            }
        }
    }

    /**
     * 端口三级回退的第 1、3 级（第 2 级 mDNS 实测不可用，见 [deviceTlsPort]）。
     *
     * 优先回环自连；读不到属性时退回「上次连通的地址」——那可能是局域网 IP，手机换网就失效，
     * 但比直接失败强。两级都没有就返回 null，让调用方报 [AdbOutcome.Reason.NO_PORT]。
     */
    private fun resolveTarget(): Pair<String, Int>? {
        deviceTlsPort()?.let { return LOOPBACK to it }
        val last = lastConnectAddress() ?: return null
        val host = last.substringBeforeLast(':', "")
        val port = last.substringAfterLast(':', "").toIntOrNull()
        return if (host.isNotEmpty() && port != null) host to port else null
    }

    /** 记住这次连通的地址，下次预填（端口只在重启无线调试时变，值得留）。回环地址不记。 */
    private fun rememberConnectAddress(host: String, port: Int) {
        if (host == LOOPBACK) return
        runCatching { File(adbDir(context), LAST_HOST_FILE).writeText("$host:$port") }
    }

    /** 连接被接受即证明配对有效（老版本没写标记，靠这一步补上），下次直接进已配对态。 */
    private fun rememberPaired() {
        runCatching { File(adbDir(context), PAIRED_MARK).writeText("1") }
    }

    /**
     * 读尽一条 adb service 的**原始字节**（`shell:cmd` / `exec:cmd`），关流后返回。
     *
     * 文本命令走 [runShell]，截图这类二进制走这里 —— 同一条读路径，免得两份循环逻辑各自漂。
     * 两个坑都是真机实测出来的：
     *
     * 1. `pm grant` 成功时不产生任何输出，adbd 跑完直接把流关掉；libadb 把远端的 CLSE 抛成
     *    `IOException("Stream closed.")` 而不是 EOF，于是「命令已经执行成功」被当成失败报给用户
     *    （U90 真机：面板报 Stream closed，退出再进开关已经出现）。这类关流不视为错误——授权到底
     *    有没有生效一律由调用方查 isGranted 复核，不靠 shell 输出判断。
     * 2. **关流会吃掉已收到的输出**：用 `readBytes()` 时，只要远端在读完之前发了 CLSE，整个
     *    `readBytes()` 就抛异常、把已经读到的字节一起丢掉（U91 诊断：`getprop` / `pm list` 全部
     *    返回空，`id` / `echo` 正常）。故改成增量读 + 关流当正常收尾，收到多少返回多少。
     *    libadb 侧确认过不会丢在途数据：`addPayload` 只入队，`notifyClose` 见队列非空只置
     *    `mPendingClose`，`read` 仍会把队列读空才抛。
     */
    private fun readBytes(manager: AbsAdbConnectionManager, service: String): ByteArray {
        val stream = manager.openStream(service)
        return try {
            val input = stream.openInputStream()
            val out = ByteArrayOutputStream()
            val chunk = ByteArray(READ_CHUNK)
            try {
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    out.write(chunk, 0, n)
                }
            } catch (e: IOException) {
                if (e.message?.contains("stream closed", ignoreCase = true) != true) throw e
            }
            out.toByteArray()
        } finally {
            runCatching { stream.close() }
        }
    }

    /** 文本命令：读字节后按 UTF-8 还原（adb 的 shell 输出就是文本）。 */
    private fun runShell(manager: AbsAdbConnectionManager, command: String): String =
        readBytes(manager, "shell:$command").toString(Charsets.UTF_8)

    companion object {
        private const val LOOPBACK = "127.0.0.1"

        /**
         * 截屏服务串：`exec:` 不分配 pty，`screencap -p` 把 PNG 直接写进这条流
         * （为什么不用 `shell:... | base64`，见 [screenshot] 的 KDoc）。
         */
        private const val SCREENSHOT_SERVICE = "exec:screencap -p"

        /** 读流的分块大小。截图有 500 KB 量级，4096 太碎。 */
        private const val READ_CHUNK = 8192

        /** PNG 文件签名（`\x89PNG\r\n\x1a\n`）。 */
        private val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(),
            0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte(),
        )

        /** 读到 IHDR 宽高所需的最小长度：签名 8 + 块长 4 + `IHDR` 4 + 宽 4 + 高 4。 */
        private const val PNG_HEADER_BYTES = 24

        /** 是否已配对（宿主据此决定注不注入 `device_shell`）。 */
        fun isPaired(context: Context): Boolean = File(adbDir(context), PAIRED_MARK).isFile &&
            File(adbDir(context), KEY_FILE).isFile

        /**
         * adbd 的 TLS 监听端口。
         *
         * app 域实测可读（U91 任务书 §8.1 ①：`untrusted_app_27` 下返回 `44467`），所以端口发现
         * 只需要这一级；`NsdManager` 在本机 8 秒内 `hits=0`（§8.1 ③），mDNS 不做。
         * 读不到返回 null，由调用方决定降级或报错——**不许**拿旧端口静默硬连。
         */
        fun deviceTlsPort(): Int? = runCatching {
            val proc = ProcessBuilder("/system/bin/getprop", "service.adb.tls.port")
                .redirectErrorStream(true)
                .start()
            val text = proc.inputStream.bufferedReader().use { it.readText() }.trim()
            proc.waitFor()
            text.toIntOrNull()
        }.getOrNull()
    }
}

/** 具体 adb 管理器：提供持久化的 RSA 密钥 / 自签证书 / 设备名。 */
private class KairAdbManager(context: Context) : AbsAdbConnectionManager() {
    private val keys: AdbKeys = loadOrGenerateKeys(context)

    init {
        // mApi 默认 1，会原样进 A_CNXN 握手（README「Set the API version whose adbd is running」）。
        // 必须声明本机 adbd 的版本，否则对端按最老的协议面谈。
        setApi(Build.VERSION.SDK_INT)
    }

    override fun getPrivateKey(): PrivateKey = keys.privateKey

    override fun getCertificate(): Certificate = keys.certificate

    override fun getDeviceName(): String = "Kair"
}

/** 建连失败（与「命令执行中途 IO 出错」区分开），调用方据此报 CONNECT_FAILED。 */
private class AdbConnectException(address: String) : Exception("connect failed: $address")

/**
 * 常驻 adb 会话（2026-11-16 真机反馈：「USB 调试已连接」只在 agent 调用时闪一下）。
 *
 * 原来每次 `shell()` / `screenshot()` 都新建 manager → `connect` → 用完 `disconnect`，
 * 一条命令一次 TLS 握手 + AUTH（真机 150–300 ms）。GUI 自动化是「截图 → 点 → 再截图」的
 * 连续动作，10 次操作光握手就 1.5–3 s，系统侧那个「USB 调试已连接」也跟着一明一灭。
 *
 * 这里让一条会话跨调用复用。两条规矩：
 *
 * 1. **串行**（[Mutex]）：一条连接上的流管理不是为并发设计的，同时开两条 `shell:` 容易互相踩。
 *    代价是截图这种 500 KB 的调用会挡住排队的另一个 —— 但那本来也不该并发。
 * 2. **执行期不重试**：block 里可能已经发出 `input tap`，重连重跑等于点两下。建连失败报
 *    CONNECT_FAILED；执行中途出错只重置会话（[disconnectNow]），让下一次调用自己重建。
 *
 * 注意 `connect()` 在 libadb 里「已连接就返回 false」——**false 不等于失败**，所以这里先判
 * `isConnected()` 再连，不能沿用「!connect() 即失败」的写法。
 */
private object AdbSession {
    private val mutex = Mutex()
    private var manager: KairAdbManager? = null
    private var endpoint: Pair<String, Int>? = null

    /**
     * 会话还活着就直接复用它的地址，省掉一次 `getprop` 子进程（预热的意义就在这里：
     * 每次调用都重新探测端口，等于把省下来的握手时间又花回去了）。
     * 端口真变了（无线调试重启）时 `isConnected()` 会先失败，下一句自然会走重新探测。
     */
    fun liveTarget(): Pair<String, Int>? = if (manager?.isConnected() == true) endpoint else null

    suspend fun <T> withConnection(
        context: Context,
        target: Pair<String, Int>,
        block: (AbsAdbConnectionManager) -> T,
    ): T = mutex.withLock {
        var current = manager
        // 端口变了（无线调试重启）或连接已死：整条重建，别在半死状态上发命令。
        if (current != null && (endpoint != target || !current.isConnected())) {
            closeQuietly(current)
            current = null
            manager = null
            endpoint = null
        }
        val live = current ?: run {
            val fresh = KairAdbManager(context)
            if (!fresh.connect(target.first, target.second)) {
                closeQuietly(fresh)
                throw AdbConnectException("${target.first}:${target.second}")
            }
            manager = fresh
            endpoint = target
            fresh
        }
        block(live)
    }

    /** 丢掉当前会话：超时、执行期 IO 错、密钥被清。下一次调用会自己重建。 */
    fun disconnectNow() {
        val current = manager ?: return
        manager = null
        endpoint = null
        closeQuietly(current)
    }

    private fun closeQuietly(target: AbsAdbConnectionManager) {
        runCatching { target.disconnect() }
        runCatching { target.close() }
    }
}

/** 密钥 + 证书持有者（libadb 的 KeyPair 是包级私有，不可外用，故自带）。 */
private data class AdbKeys(val privateKey: PrivateKey, val certificate: Certificate)

/** 密钥与上次连通地址的落盘目录（应用私有）。不用 SharedPreferences：避开 PhantomKillerStore 的单键守卫。 */
private const val ADB_DIR = "phantom-adb"
private const val KEY_FILE = "private.key"
private const val CERT_FILE = "cert.der"
private const val LAST_HOST_FILE = "last-connect-host"
private const val PAIRED_MARK = "paired.ok"

private fun adbDir(context: Context): File = File(context.filesDir, ADB_DIR).apply { mkdirs() }

/** 读已落盘的密钥/证书；没有则用 CertAndKeyGen 生成 RSA2048 + 自签证书并落盘。 */
private fun loadOrGenerateKeys(context: Context): AdbKeys {
    val dir = adbDir(context)
    val keyFile = File(dir, KEY_FILE)
    val certFile = File(dir, CERT_FILE)
    if (keyFile.exists() && certFile.exists()) {
        runCatching {
            val priv = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
            val cert = CertificateFactory.getInstance("X.509").generateCertificate(certFile.inputStream())
            return AdbKeys(priv, cert)
        }
    }
    val gen = CertAndKeyGen("RSA", "SHA256withRSA")
    gen.generate(2048)
    val cert = gen.getSelfCertificate(X500Name("CN=Kair"), 3650L * 24 * 3600)
    val priv = gen.privateKey
    keyFile.writeBytes(priv.encoded)
    certFile.writeBytes(cert.encoded)
    return AdbKeys(priv, cert)
}
