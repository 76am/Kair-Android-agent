package dev.kair.app.core.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 镜像预设的纯逻辑判据：五个 id 可解析、未知 / 空 id 回退默认、
 * arm64 走 `ubuntu-ports`、x86_64 走 `ubuntu`，apt 用 http、镜像 / pip 用 https。
 * 站点可用性归真机 / B 层，不在这里猜网络。
 */
class ContainerMirrorTest {

    @Test
    fun fivePresetsResolveById() {
        assertEquals(5, ContainerMirror.entries.size)
        listOf("aliyun", "tuna", "ustc", "huawei", "official").forEach { id ->
            assertEquals(id, ContainerMirror.fromId(id).id)
        }
    }

    @Test
    fun unknownOrBlankIdFallsBackToDefault() {
        assertEquals(ContainerMirror.ALIYUN, ContainerMirror.DEFAULT)
        assertEquals(ContainerMirror.ALIYUN, ContainerMirror.fromId(null))
        assertEquals(ContainerMirror.ALIYUN, ContainerMirror.fromId(""))
        assertEquals(ContainerMirror.ALIYUN, ContainerMirror.fromId("mirror.example.cn"))
        // 大小写不匹配也按未知处理：id 是代码常量，不做模糊匹配。
        assertEquals(ContainerMirror.ALIYUN, ContainerMirror.fromId("ALIYUN"))
    }

    @Test
    fun aptBasesSplitByArchitecture() {
        ContainerMirror.entries.forEach { mirror ->
            val arm64 = mirror.aptBase("arm64")
            val amd64 = mirror.aptBase("amd64")
            assertTrue("${mirror.id} 的 arm64 apt base 必须是 http：$arm64", arm64.startsWith("http://"))
            assertTrue("${mirror.id} 的 amd64 apt base 必须是 http：$amd64", amd64.startsWith("http://"))
            assertTrue("arm64 必须走 ubuntu-ports：$arm64", arm64.contains("ubuntu-ports"))
            assertFalse("amd64 不能走 ubuntu-ports：$amd64", amd64.contains("ubuntu-ports"))
        }
    }

    @Test
    fun imageAndPipUseHttps() {
        ContainerMirror.entries.forEach { mirror ->
            assertTrue(
                "${mirror.id} 的 imageBase 必须是 https：${mirror.imageBase}",
                mirror.imageBase.startsWith("https://"),
            )
            assertTrue("镜像 base 必须指向 ubuntu-base：${mirror.imageBase}", mirror.imageBase.endsWith("/ubuntu-base"))
            assertTrue("${mirror.id} 的 pipIndex 必须是 https：${mirror.pipIndex}", mirror.pipIndex.startsWith("https://"))
        }
    }
}
