package dev.kair.app.core.proot

import dev.kair.app.core.assumeLinux
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

/**
 * rootfs 安装状态机：从归档安装（含补丁）、坏归档拒绝、卸载、ABI 支持。
 * 下载不碰真网络：用假 OkHttp client 喂归档 / sha256，覆盖 install() 的镜像接线与取消收尾；
 * 解包核心步骤由 [RootfsManager.installFromArchive] 直接覆盖。
 */
class RootfsManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun manager(abi: String? = "x86_64"): RootfsManager =
        RootfsManager(tmp.newFolder("proot-${System.nanoTime()}"), abi)

    /** 最小可用 rootfs 归档：bin/sh + etc/group，gzip 压缩。 */
    private fun rootfsArchive(vararg extraEntries: Pair<String, String>): ByteArrayInputStream {
        val entries = sequence {
            yield(Pair("./bin/", ""))
            yield(Pair("./bin/sh", "#!/bin/sh\n"))
            yield(Pair("./etc/", ""))
            yield(Pair("./etc/group", "root:x:0:root\n"))
            yieldAll(extraEntries.asIterable())
        }
        val buf = java.io.ByteArrayOutputStream()
        GZIPOutputStream(buf).use { gz ->
            val tar = TarWriter()
            entries.forEach { (path, content) ->
                if (path.endsWith("/")) tar.dir(path) else tar.file(path, content)
            }
            gz.write(tar.bytes())
        }
        return ByteArrayInputStream(buf.toByteArray())
    }

    /** 旧布局的最小归档：`bin/sh` 为指向 `/bin/busybox` 的绝对软链（验证 lstat 判据）。 */
    private fun alpineLikeArchive(): ByteArrayInputStream {
        val tar = TarWriter().apply {
            dir("./bin/")
            symlink("./bin/sh", "/bin/busybox")
            file("./bin/busybox", "ELF-ish bytes")
            dir("./etc/")
            file("./etc/group", "root:x:0:root\n")
        }
        val buf = java.io.ByteArrayOutputStream()
        GZIPOutputStream(buf).use { gz -> gz.write(tar.bytes()) }
        return ByteArrayInputStream(buf.toByteArray())
    }

    @Test
    fun `install from archive reaches Ready and applies patches`() = runBlocking {
        val m = manager()

        m.installFromArchive(rootfsArchive(), gzip = true, version = "3.22.6-test")

        val state = m.state.value
        assertTrue("state=$state", state is RootfsManager.State.Ready)
        assertEquals("3.22.6-test", (state as RootfsManager.State.Ready).version)
        assertTrue(m.isReady)
        assertEquals("3.22.6-test", File(m.prootDirForTest(), "version.txt").readText().trim())
        assertEquals("ubuntu", File(m.prootDirForTest(), "distro.txt").readText().trim())
        // 补丁：DNS / hosts / group / tmp
        assertTrue(File(m.rootfsDir, "etc/resolv.conf").readText().contains("nameserver"))
        assertTrue(File(m.rootfsDir, "etc/hosts").exists())
        assertTrue(File(m.rootfsDir, "etc/group").readText().contains("android_everybody:x:9997"))
        assertTrue(File(m.rootfsDir, "tmp").isDirectory)
    }

    /**
     * 判据（callee）：`installFromArchive` 必须把自己的 `mirror` 参数传给补丁，不退回默认值。
     * 「用户选的值有没有传进来」由下一条走真实 `install()` 的用例覆盖 —— 两条不可互替。
     */
    @Test
    fun `install from archive writes the selected mirror, not the default`() = runBlocking {
        val m = manager()

        m.installFromArchive(rootfsArchive(), gzip = true, mirror = ContainerMirror.HUAWEI)

        assertEquals(huaweiAptSources(), File(m.rootfsDir, "etc/apt/sources.list").readLines())
        assertTrue(
            File(m.rootfsDir, "etc/pip.conf").readText().contains(huaweiPipIndex),
        )
    }

    /**
     * 判据（接线，走真实 `install()`）：设置里选的镜像必须一路传到补丁。
     * 假 client 本地喂归档 + 匹配 sha256（不碰真网络）；选非默认的 HUAWEI ——
     * 若 `install()` 调 `installFromArchive` 时漏传 mirror（退回默认 ALIYUN），这条红。
     */
    @Test
    fun `install uses the mirror selected in settings`() = runBlocking {
        val m = RootfsManager(
            tmp.newFolder("proot-wiring"),
            "x86_64",
            client = servingClient(rootfsArchive().readBytes()),
            mirror = { ContainerMirror.HUAWEI },
        )

        m.install()

        assertTrue("state=${m.state.value}", m.state.value is RootfsManager.State.Ready)
        assertEquals(huaweiAptSources(), File(m.rootfsDir, "etc/apt/sources.list").readLines())
        assertTrue(
            File(m.rootfsDir, "etc/pip.conf").readText().contains(huaweiPipIndex),
        )
    }

    /**
     * 判据（取消收尾）：安装中取消（离开面板 = composition scope 取消）不是失败，
     * 也不能停在 Downloading / Extracting —— 那会被 `install()` 的「已在安装中」guard
     * 永久吞掉，而面板这两个状态没有重试按钮。状态必须复位成磁盘真实状态（此处 = 未安装）。
     */
    @Test
    fun `cancelled install returns to NotInstalled instead of staying busy`() = runBlocking {
        val archive = rootfsArchive().readBytes()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                started.countDown()
                release.await(10, TimeUnit.SECONDS)
                chain.proceed(chain.request())
            }
            .addInterceptor(servingInterceptor(archive))
            .build()
        val m = RootfsManager(tmp.newFolder("proot-cancel"), "x86_64", client = client)

        val job = launch(Dispatchers.Default) { m.install() }
        assertTrue("下载未开始", started.await(10, TimeUnit.SECONDS))
        assertTrue(
            "此时应为 Downloading，实际 ${m.state.value}",
            m.state.value is RootfsManager.State.Downloading,
        )
        job.cancel()
        release.countDown()
        job.join()

        assertTrue(
            "取消后停在 ${m.state.value}：install() 会被 guard 吞掉、面板无重试按钮",
            m.state.value is RootfsManager.State.NotInstalled,
        )
    }

    @Test
    fun `archive with absolute symlink bin-sh (real alpine layout) installs`() = runBlocking {
        assumeLinux("rootfs 权限与属主")
        val m = manager()

        m.installFromArchive(alpineLikeArchive(), gzip = true, version = "3.22.6-test")

        assertTrue("state=${m.state.value}", m.state.value is RootfsManager.State.Ready)
        assertTrue(m.isReady)
        // bin/sh 是指向 /bin/busybox 的绝对软链——设备上解析不到，但链接本身须存在
        assertTrue(java.nio.file.Files.isSymbolicLink(File(m.rootfsDir, "bin/sh").toPath()))
    }

    @Test
    fun `detectState recognizes installed rootfs after process restart`() = runBlocking {
        assumeLinux("rootfs 权限与属主")
        val prootDir = tmp.newFolder("proot-restart")
        RootfsManager(prootDir, "x86_64")
            .installFromArchive(alpineLikeArchive(), gzip = true, version = "v9")

        // 模拟进程重启：新实例从磁盘探测状态
        val restarted = RootfsManager(prootDir, "x86_64")
        assertTrue("state=${restarted.state.value}", restarted.state.value is RootfsManager.State.Ready)
        assertTrue(restarted.isReady)
    }

    @Test
    fun `legacy rootfs without distro marker requires reinstall`() = runBlocking {
        val prootDir = tmp.newFolder("proot-legacy")
        val rootfs = File(prootDir, "rootfs")
        File(rootfs, "bin").mkdirs()
        File(rootfs, "bin/sh").writeText("#!/bin/sh\n")
        File(prootDir, "version.txt").writeText("3.22.6")

        val m = RootfsManager(prootDir, "x86_64")

        val state = m.state.value
        assertTrue("旧 Alpine rootfs 不得冒充 Ready：$state", state is RootfsManager.State.ReinstallRequired)
        assertEquals("alpine", (state as RootfsManager.State.ReinstallRequired).installed)
        assertFalse(m.isReady)
    }

    @Test
    fun `archive without bin-sh is rejected`() = runBlocking {
        val m = manager()
        val bad = TarWriter().apply { file("./not/a/rootfs.txt", "nope") }.let {
            val buf = java.io.ByteArrayOutputStream()
            GZIPOutputStream(buf).use { gz -> gz.write(it.bytes()) }
            ByteArrayInputStream(buf.toByteArray())
        }

        try {
            m.installFromArchive(bad, gzip = true)
            throw AssertionError("expected IOException")
        } catch (e: IOException) {
            assertNotNull(e.message)
        }
        assertFalse(m.isReady)
        // 失败不残留半成品
        assertFalse(File(m.rootfsDir, "not").exists())
    }

    @Test
    fun `uninstall returns to NotInstalled`() = runBlocking {
        val m = manager()
        m.installFromArchive(rootfsArchive(), gzip = true)

        m.uninstall()

        assertTrue(m.state.value is RootfsManager.State.NotInstalled)
        assertFalse(m.rootfsDir.exists())
        assertFalse(File(m.prootDirForTest(), "version.txt").exists())
    }

    @Test
    fun `reinstall replaces previous rootfs`() = runBlocking {
        val m = manager()
        m.installFromArchive(rootfsArchive(), gzip = true, version = "v1")
        m.installFromArchive(rootfsArchive(), gzip = true, version = "v2")

        val state = m.state.value
        assertTrue(state is RootfsManager.State.Ready)
        assertEquals("v2", (state as RootfsManager.State.Ready).version)
    }

    @Test
    fun `unsupported abi reports reason and never ready`() = runBlocking {
        val m = manager(abi = "armeabi-v7a")

        assertNotNull(m.unsupportedReason)
        assertFalse(m.isReady)

        m.install()
        assertTrue(m.state.value is RootfsManager.State.Error)
    }

    // ---- 测试辅助 ------------------------------------------------------------

    private val huaweiPipIndex =
        "index-url = https://mirrors.huaweicloud.com/repository/pypi/simple"

    private fun huaweiAptSources() = listOf(
        "deb http://repo.huaweicloud.com/ubuntu jammy main restricted universe",
        "deb http://repo.huaweicloud.com/ubuntu jammy-updates main restricted universe",
        "deb http://repo.huaweicloud.com/ubuntu jammy-security main restricted universe",
    )

    private fun servingClient(archive: ByteArray): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(servingInterceptor(archive)).build()

    /** 假下载源：归档请求返回 tar.gz 字节，`SHA256SUMS` 请求返回对应摘要（不碰真网络）。 */
    private fun servingInterceptor(archive: ByteArray): Interceptor {
        val sha = MessageDigest.getInstance("SHA-256").digest(archive)
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        return Interceptor { chain ->
            val isSums = chain.request().url.pathSegments.last() == "SHA256SUMS"
            val body = if (isSums) {
                "$sha *${rootfsFileName("amd64")}\n".toResponseBody("text/plain".toMediaType())
            } else {
                archive.toResponseBody("application/gzip".toMediaType())
            }
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body)
                .build()
        }
    }

    private fun RootfsManager.prootDirForTest(): File = rootfsDir.parentFile!!

    /** 与 TarExtractorTest 的构造器同构，只保留 file/dir 两种最小条目。 */
    private class TarWriter {
        private val out = java.io.ByteArrayOutputStream()

        private fun header(name: String, type: Char, size: Int, mode: Int, linkname: String = "") {
            val h = ByteArray(512)
            fun str(off: Int, len: Int, s: String) {
                System.arraycopy(s.toByteArray(Charsets.UTF_8).copyOf(len), 0, h, off, len)
            }
            fun oct(off: Int, len: Int, v: Long) = str(off, len - 1, v.toString(8).padStart(len - 1, '0'))
            str(0, 100, name)
            oct(100, 8, mode.toLong()); oct(108, 8, 0); oct(116, 8, 0)
            oct(124, 12, size.toLong()); oct(136, 12, 0)
            for (i in 148 until 156) h[i] = ' '.code.toByte()
            h[156] = type.code.toByte()
            str(157, 100, linkname)
            str(257, 6, "ustar"); h[263] = '0'.code.toByte()
            str(265, 32, "root"); str(297, 32, "root")
            oct(148, 8, h.sumOf { it.toInt() and 0xFF }.toLong())
            out.write(h)
        }

        fun file(path: String, content: String) {
            header(path, '0', content.length, 420) // 0644
            val data = content.toByteArray().copyOf((content.length + 511) / 512 * 512)
            out.write(data)
        }

        fun dir(path: String) = header(path, '5', 0, 493) // 0755

        fun symlink(path: String, linkname: String) = header(path, '2', 0, 511, linkname) // 0777

        fun bytes(): ByteArray = out.toByteArray() + ByteArray(1024)
    }
}
