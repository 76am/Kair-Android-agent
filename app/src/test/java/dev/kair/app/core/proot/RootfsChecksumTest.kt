package dev.kair.app.core.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * F1 的纯逻辑判据：rootfs 下载地址拼接 / sha256 解析与计算。
 *
 * 原「裸 base URL 归一化」三条用例（`mirrorDefaultsToOfficialHttps` /
 * `customHttpsMirrorIsKeptWithoutTrailingSlash` / `plainHttpFallsBackToOfficial`）随
 * 「自由填 URL → 预设 id」一并作废 —— 语义已不存在，镜像预设的判据见 `ContainerMirrorTest`。
 *
 * 网络边界（真下载、`.sha256` 拿不到）不在这里测 —— 那要 MockWebServer 或真网络，
 * 归 B 层 + 真机（trae / Kair）。
 */
class RootfsChecksumTest {

    @Test
    fun urlKeepsVersionAndArchBakedIn() {
        assertEquals(
            "https://mirrors.aliyun.com/ubuntu-cdimage/ubuntu-base/releases/jammy/release/" +
                "ubuntu-base-22.04.5-base-arm64.tar.gz",
            rootfsUrl(ContainerMirror.ALIYUN, "arm64"),
        )
        assertEquals(
            "https://cdimage.ubuntu.com/ubuntu-base/releases/jammy/release/" +
                "ubuntu-base-22.04.5-base-amd64.tar.gz",
            rootfsUrl(ContainerMirror.OFFICIAL, "amd64"),
        )
    }

    @Test
    fun sha256SumsParsingPicksTheMatchingFileName() {
        val hash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        val other = "0".repeat(64)
        val text = """
            $other *ubuntu-base-22.04.5-base-amd64.tar.gz
            $hash *ubuntu-base-22.04.5-base-arm64.tar.gz
        """.trimIndent()
        assertEquals(hash, parseSha256Sums(text, "ubuntu-base-22.04.5-base-arm64.tar.gz"))
        assertEquals(other, parseSha256Sums(text, "ubuntu-base-22.04.5-base-amd64.tar.gz"))
        assertNull(parseSha256Sums(text, "ubuntu-base-24.04.3-base-arm64.tar.gz"))
    }

    @Test
    fun sha256FileParsingAcceptsSha256sumFormat() {
        val hash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertEquals(hash, parseSha256("$hash  alpine-minirootfs-3.22.6-aarch64.tar.gz\n"))
        assertEquals(hash, parseSha256(hash.uppercase()))
        assertEquals(hash, parseSha256("$hash\n"))
        assertNull(parseSha256(""))
        assertNull(parseSha256("not a checksum"))
        // 只有 63 位十六进制：不是合法 sha256，不能凑数通过。
        assertNull(parseSha256(hash.dropLast(1)))
    }

    @Test
    fun sha256HexMatchesKnownDigest() {
        val file = Files.createTempFile("kair-sha", ".txt").toFile()
        try {
            file.writeText("abc")
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256Hex(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun mismatchExceptionIsAnIoFailure() {
        val e = ChecksumMismatchException("aa", "bb")
        assertTrue(e.message!!.contains("aa") && e.message!!.contains("bb"))
    }

    @Test
    fun missingChecksumFileWarnsButDoesNotBlock() {
        val archive: File = Files.createTempFile("kair-download", ".tar.gz").toFile()
        try {
            archive.writeText("abc")
            val warnings = mutableListOf<String>()
            val verified = verifyArchive(
                archive,
                "https://mirror.test/alpine.tar.gz.sha256",
                fetch = { null },
                warn = { warnings += it },
            )
            assertFalse("拿不到 sha：跳过校验，不阻断安装", verified)
            assertTrue("文件不能被删", archive.exists())
            assertEquals(1, warnings.size)
            assertTrue("至少要留一条日志：${warnings.single()}", warnings.single().contains("unavailable"))
        } finally {
            archive.delete()
        }
    }

    @Test
    fun checksumFetchFailureWarnsButDoesNotBlock() {
        val archive: File = Files.createTempFile("kair-download", ".tar.gz").toFile()
        try {
            archive.writeText("abc")
            val warnings = mutableListOf<String>()
            val verified = verifyArchive(
                archive,
                "https://mirror.test/alpine.tar.gz.sha256",
                fetch = { throw IOException("connection reset") },
                warn = { warnings += it },
            )
            assertFalse(verified)
            assertTrue(archive.exists())
            assertTrue("至少要留一条日志：${warnings.single()}", warnings.single().contains("failed"))
        } finally {
            archive.delete()
        }
    }

    @Test
    fun matchingChecksumIsVerified() {
        val archive: File = Files.createTempFile("kair-download", ".tar.gz").toFile()
        try {
            archive.writeText("abc")
            val warnings = mutableListOf<String>()
            val verified = verifyArchive(
                archive,
                "https://mirror.test/alpine.tar.gz.sha256",
                fetch = { "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad" },
                warn = { warnings += it },
            )
            assertTrue("拿到 sha 必须真的比对", verified)
            assertTrue(warnings.isEmpty())
            assertTrue(archive.exists())
        } finally {
            archive.delete()
        }
    }

    @Test
    fun mismatchingChecksumDeletesTheArchive() {
        val archive: File = Files.createTempFile("kair-download", ".tar.gz").toFile()
        try {
            archive.writeText("abc")
            verifyArchive(
                archive,
                "https://mirror.test/alpine.tar.gz.sha256",
                fetch = { "0".repeat(64) },
                warn = {},
            )
            throw AssertionError("sha256 不一致时应抛 ChecksumMismatchException")
        } catch (e: ChecksumMismatchException) {
            assertTrue("校验失败必须删除半截文件", !archive.exists())
        } finally {
            archive.delete()
        }
    }

    @Test
    fun checksumMismatchDeletesTheArchive() {
        val archive: File = Files.createTempFile("kair-download", ".tar.gz").toFile()
        try {
            archive.writeText("abc")
            // 一致：不抛、不删。
            verifySha256(archive, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
            assertTrue(archive.exists())

            // 不一致：删半截文件 + 抛类型化异常（安装中止，不拿坏包解压）。
            verifySha256(archive, "0".repeat(64))
            throw AssertionError("sha256 不一致时应抛 ChecksumMismatchException")
        } catch (e: ChecksumMismatchException) {
            assertTrue("校验失败必须删除半截文件", !archive.exists())
        } finally {
            archive.delete()
        }
    }
}
