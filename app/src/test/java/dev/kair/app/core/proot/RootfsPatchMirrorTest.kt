package dev.kair.app.core.proot

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * U92 判据：容器内 apt / pip 的镜像配置真的被写进 rootfs（临时目录当 rootfs，不联网）。
 *
 * 重点覆盖：
 * - `/etc/apt/sources.list` 是**覆盖写**且恰好三行（jammy / updates / security，都含 universe）；
 * - arm64 走 `ubuntu-ports`、x86_64 走 `ubuntu`；
 * - `policy-rc.d` 存在且可执行；`apt.conf` 有 Sandbox root 与关翻译；
 * - 已装 rootfs 切源由 `reapplyMirror` 立刻生效，未装时返回 false 且不建目录。
 */
class RootfsPatchMirrorTest {

    private fun tempRootfs(): File = Files.createTempDirectory("kair-rootfs").toFile()

    private fun aptSources(rootfs: File): List<String> =
        File(rootfs, "etc/apt/sources.list").readLines()

    private fun expectedAptSources(mirror: ContainerMirror, arch: String = "amd64"): List<String> {
        val base = mirror.aptBase(arch)
        return listOf(
            "deb $base jammy main restricted universe",
            "deb $base jammy-updates main restricted universe",
            "deb $base jammy-security main restricted universe",
        )
    }

    @Test
    fun patchWritesAptSourcesAndPipConf() {
        val rootfs = tempRootfs()
        try {
            RootfsPatcher.patch(rootfs, ContainerMirror.ALIYUN, "amd64")

            assertEquals(expectedAptSources(ContainerMirror.ALIYUN), aptSources(rootfs))
            val pip = File(rootfs, "etc/pip.conf").readText()
            assertTrue("pip.conf 缺 index-url：$pip", pip.contains("index-url = https://mirrors.aliyun.com/pypi/simple/"))
            assertTrue("选项是单数 disable-：$pip", pip.contains("disable-pip-version-check = true"))
            assertFalse("https 且证书正常，不写 trusted-host：$pip", pip.contains("trusted-host"))
            val aptConf = File(rootfs, "etc/apt/apt.conf.d/99kair").readText()
            assertTrue("proot 下 apt 必须不降权到 _apt：$aptConf", aptConf.contains("APT::Sandbox::User \"root\""))
            assertTrue("必须关掉翻译索引：$aptConf", aptConf.contains("Acquire::Languages \"none\""))
            val policy = File(rootfs, "usr/sbin/policy-rc.d")
            assertTrue("policy-rc.d 必须存在", policy.isFile)
            assertTrue("policy-rc.d 必须可执行", policy.canExecute())
            assertTrue(File(rootfs, "root/.cache").isDirectory)
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test
    fun switchingPresetRewritesContent() {
        val rootfs = tempRootfs()
        try {
            RootfsPatcher.patch(rootfs, ContainerMirror.ALIYUN, "amd64")
            RootfsPatcher.patch(rootfs, ContainerMirror.TUNA, "amd64")

            assertEquals(expectedAptSources(ContainerMirror.TUNA), aptSources(rootfs))
            assertTrue(File(rootfs, "etc/pip.conf").readText().contains("https://pypi.tuna.tsinghua.edu.cn/simple"))
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test
    fun arm64UsesUbuntuPorts() {
        val rootfs = tempRootfs()
        try {
            RootfsPatcher.patch(rootfs, ContainerMirror.ALIYUN, "arm64")
            assertEquals(expectedAptSources(ContainerMirror.ALIYUN, "arm64"), aptSources(rootfs))
            assertTrue(aptSources(rootfs).all { it.contains("ubuntu-ports") })
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test
    fun patchClearsAptLists() {
        val rootfs = tempRootfs()
        try {
            val lists = File(rootfs, "var/lib/apt/lists").apply { mkdirs() }
            File(lists, "archive.ubuntu.com_packages").writeText("stale official index")
            File(lists, "partial").apply { mkdirs() }
            File(lists, "partial/pkg").writeText("stale")

            RootfsPatcher.patch(rootfs, ContainerMirror.ALIYUN, "amd64")

            assertTrue("列表目录必须保留", lists.isDirectory)
            assertEquals("换源后旧索引不能残留", 0, lists.listFiles()?.size ?: 0)
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test
    fun reapplyMirrorReturnsFalseWhenNotInstalledAndDoesNotCreateDirs() {
        val prootDir = Files.createTempDirectory("kair-proot").toFile()
        try {
            val manager = RootfsManager(prootDir, "x86_64")
            assertFalse(runBlocking { manager.reapplyMirror(ContainerMirror.HUAWEI) })
            assertFalse("未安装时不得创建 rootfs 目录", File(prootDir, "rootfs").exists())
        } finally {
            prootDir.deleteRecursively()
        }
    }

    @Test
    fun reapplyMirrorUpdatesAnInstalledRootfs() {
        val prootDir = Files.createTempDirectory("kair-proot").toFile()
        try {
            val rootfs = File(prootDir, "rootfs").apply { mkdirs() }
            val manager = RootfsManager(prootDir, "x86_64")

            assertTrue(runBlocking { manager.reapplyMirror(ContainerMirror.HUAWEI) })

            assertEquals(expectedAptSources(ContainerMirror.HUAWEI), aptSources(rootfs))
            assertTrue(
                File(rootfs, "etc/pip.conf").readText()
                    .contains("https://mirrors.huaweicloud.com/repository/pypi/simple"),
            )
        } finally {
            prootDir.deleteRecursively()
        }
    }

    @Test
    fun rootfsUrlPointsAtUbuntuBaseImage() {
        val url = rootfsUrl(ContainerMirror.ALIYUN, "arm64")
        assertEquals(
            "https://mirrors.aliyun.com/ubuntu-cdimage/ubuntu-base/releases/jammy/release/" +
                "ubuntu-base-22.04.5-base-arm64.tar.gz",
            url,
        )
        // imageBase 已经含 /ubuntu-base，文件名里再出现一次不是路径重复；判路径段只出现一次。
        assertEquals(1, Regex("/ubuntu-base/").findAll(url).count())
    }

    /**
     * 回退判据：所选镜像没有 `releases/`（同步延迟 / 站点故障）时必须回退官方源，且**留日志**。
     * 用 OkHttp 拦截器把所有请求打成 404（项目没有 MockWebServer），不需要真网络。
     */
    @Test
    fun `download falls back to official and logs when mirror has no releases`() = runBlocking {
        val prootDir = Files.createTempDirectory("kair-proot").toFile()
        try {
            val warnings = mutableListOf<String>()
            val alwaysNotFound = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(404)
                    .message("Not Found")
                    .body("".toResponseBody(null))
                    .build()
            }.build()
            val manager = RootfsManager(
                prootDir,
                "x86_64",
                client = alwaysNotFound,
                warn = { warnings += it },
            )

            manager.install()

            assertTrue("state=${manager.state.value}", manager.state.value is RootfsManager.State.Error)
            assertTrue(
                "回退官方源必须留日志（不许静默）：$warnings",
                warnings.any { it.contains("falling back to official") && it.contains("cdimage.ubuntu.com") },
            )
            assertFalse("两个源都拿不到时不该留下半装状态", File(prootDir, "rootfs").exists())
        } finally {
            prootDir.deleteRecursively()
        }
    }
}
