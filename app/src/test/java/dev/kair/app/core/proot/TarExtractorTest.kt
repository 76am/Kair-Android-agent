package dev.kair.app.core.proot

import dev.kair.app.core.assumeLinux
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * tar 解包器测试：条目类型、GNU/PAX 扩展、以及安全边界（tar-slip / 软链穿透）。
 * tar 字节在测试内手工构造，不依赖外部 tar 命令。
 */
class TarExtractorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---- 测试用 tar 构造器 ---------------------------------------------------

    private fun entry(
        name: String,
        type: Char,
        content: ByteArray = ByteArray(0),
        mode: Int = 420, // 0644（tar 头里是八进制，Kotlin 无 0o 字面量）
        linkname: String = "",
        prefix: String = "",
    ): ByteArray {
        val h = ByteArray(512)
        fun str(off: Int, len: Int, s: String) {
            val bytes = s.toByteArray(Charsets.UTF_8).copyOf(len)
            System.arraycopy(bytes, 0, h, off, len)
        }
        fun oct(off: Int, len: Int, v: Long) = str(off, len - 1, v.toString(8).padStart(len - 1, '0'))

        str(0, 100, name)
        oct(100, 8, mode.toLong())
        oct(108, 8, 0) // uid
        oct(116, 8, 0) // gid
        oct(124, 12, content.size.toLong())
        oct(136, 12, 0) // mtime
        for (i in 148 until 156) h[i] = ' '.code.toByte() // chksum 占位
        h[156] = type.code.toByte()
        str(157, 100, linkname)
        str(257, 6, "ustar") // magic + version('0' 在 263)
        h[263] = '0'.code.toByte()
        str(265, 32, "root")
        str(297, 32, "root")
        str(345, 155, prefix)
        oct(148, 8, h.sumOf { it.toInt() and 0xFF }.toLong())

        val data = content.copyOf((content.size + 511) / 512 * 512)
        return h + data
    }

    private fun paxPathEntry(path: String): ByteArray {
        // 记录格式：<len> path=<value>\n；len 含自身、空格与换行
        var len = path.length + 7 // "path=" + " " + "\n" + 至少 1 位数字
        while (len.toString().length + path.length + 7 != len) len++
        val record = "$len path=$path\n".toByteArray(Charsets.UTF_8)
        return entry("PaxHeader", 'x', record)
    }

    private fun vararg(vararg entries: ByteArray): ByteArrayInputStream =
        ByteArrayInputStream(entries.toList().fold(ByteArray(0)) { acc, b -> acc + b } + ByteArray(1024))

    private fun extract(stream: ByteArrayInputStream): Int = TarExtractor.extract(stream, tmp.root)

    // ---- 基本条目 ------------------------------------------------------------

    @Test
    fun `extracts regular file with content and mode`() {
        assumeLinux("依赖 tar 的权限位与符号链接语义")
        val count = extract(
            vararg(
                entry("./etc/", '5'),
                entry("./etc/hello.txt", '0', "hello kair\n".toByteArray()),
                entry("./run.sh", '0', "#!/bin/sh\n".toByteArray(), mode = 493), // 0755
            ),
        )

        assertEquals(2, count)
        assertEquals("hello kair\n", File(tmp.root, "etc/hello.txt").readText())
        assertFalse(File(tmp.root, "etc/hello.txt").canExecute())
        assertTrue(File(tmp.root, "run.sh").canExecute())
    }

    @Test
    fun `extracts symlink and hardlink`() {
        assumeLinux("依赖 tar 的权限位与符号链接语义")
        extract(
            vararg(
                entry("./etc/", '5'),
                entry("./etc/hello.txt", '0', "hello kair\n".toByteArray()),
                entry("./etc/alias", '2', linkname = "../etc/hello.txt"),
                entry("./hard.txt", '1', linkname = "./etc/hello.txt"),
            ),
        )

        assertEquals("../etc/hello.txt", Files.readSymbolicLink(File(tmp.root, "etc/alias").toPath()).toString())
        // 硬链：同 inode，内容一致
        assertEquals("hello kair\n", File(tmp.root, "hard.txt").readText())
    }

    @Test
    fun `hardlink falls back to copy when the filesystem refuses link`() {
        assumeLinux("依赖 tar 的权限位与符号链接语义")
        // 真机（2026-11-16）应用私有目录上的 link() 直接失败，安装卡在 Extracting；
        // 降级必须保住内容与可执行位，否则 rootfs 里的 perl/python 不可用。
        TarExtractor.extract(
            vararg(
                entry("./usr/bin/", '5'),
                entry("./usr/bin/perl", '0', "PERL".toByteArray(), mode = 493), // 0755
                entry("./usr/bin/perl5.34.0", '1', linkname = "usr/bin/perl", mode = 493),
            ),
            tmp.root,
            createLink = { _, _ -> throw IOException("link refused") },
        )

        val linked = File(tmp.root, "usr/bin/perl5.34.0")
        assertEquals("PERL", linked.readText())
        assertTrue(linked.canExecute())
    }

    @Test
    fun `gnu long name is honored`() {
        val longDir = "d".repeat(90)
        val longName = "$longDir/${"f".repeat(90)}.txt" // > 100 字符，触发 GNU L 头
        extract(
            vararg(
                entry("././@LongLink", 'L', (longName + "\u0000").toByteArray()),
                entry("./placeholder", '0', "long!".toByteArray()),
            ),
        )

        assertEquals("long!", File(tmp.root, longName).readText())
    }

    @Test
    fun `pax path record overrides header name`() {
        extract(
            vararg(
                paxPathEntry("./overridden/by/pax.txt"),
                entry("./short.txt", '0', "pax!".toByteArray()),
            ),
        )

        assertFalse(File(tmp.root, "short.txt").exists())
        assertEquals("pax!", File(tmp.root, "overridden/by/pax.txt").readText())
    }

    @Test
    fun `device nodes are skipped without error`() {
        val count = extract(
            vararg(
                entry("./dev/null", '3'),
                entry("./ok.txt", '0', "ok".toByteArray()),
            ),
        )

        assertFalse(File(tmp.root, "dev/null").exists())
        assertEquals(1, count)
    }

    // ---- 安全边界 ------------------------------------------------------------

    @Test
    fun `entry escaping root is rejected`() {
        extract(
            vararg(
                entry("../evil.txt", '0', "boom".toByteArray()),
                entry("nested/../../escape.txt", '0', "boom".toByteArray()),
                entry("ok.txt", '0', "fine".toByteArray()),
            ),
        )

        assertFalse(File(tmp.root.parentFile, "evil.txt").exists())
        assertFalse(File(tmp.root.parentFile, "escape.txt").exists())
        assertEquals("fine", File(tmp.root, "ok.txt").readText())
    }

    @Test
    fun `absolute entry paths are confined to root`() {
        extract(vararg(entry("/etc/abs.txt", '0', "abs".toByteArray())))

        assertEquals("abs", File(tmp.root, "etc/abs.txt").readText())
    }

    @Test
    fun `writing through symlink pointing outside root is rejected`() {
        assumeLinux("依赖 tar 的权限位与符号链接语义")
        extract(
            vararg(
                entry("out", '2', linkname = "../outside"), // 指向 root 外
                entry("out/evil.txt", '0', "boom".toByteArray()),
                entry("ok.txt", '0', "fine".toByteArray()),
            ),
        )

        assertFalse(File(tmp.root.parentFile, "outside").exists())
        assertFalse(File(tmp.root, "out/evil.txt").exists())
        assertEquals("fine", File(tmp.root, "ok.txt").readText())
    }

    @Test
    fun `writing through symlink pointing inside root lands at real location`() {
        assumeLinux("依赖 tar 的权限位与符号链接语义")
        extract(
            vararg(
                entry("real/", '5'),
                entry("in", '2', linkname = "real"), // 指向 root 内
                entry("in/file.txt", '0', "ok".toByteArray()),
            ),
        )

        // 经软链写入 = 落到真实位置，语义等价
        assertEquals("ok", File(tmp.root, "real/file.txt").readText())
    }
}
