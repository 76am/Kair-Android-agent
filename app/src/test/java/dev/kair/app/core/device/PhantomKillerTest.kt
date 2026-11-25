package dev.kair.app.core.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U90 A 层纯逻辑判据（任务书 §5.1，先红后绿）。
 * 守的是「命令拼对、取值解析保守、版本闸正确」——这些是 adb/Settings 封装之下的确定性内核。
 */
class PhantomKillerTest {
    @Test
    fun `version gate opens at android 12`() {
        assertFalse(PhantomKiller.needed(30))
        assertTrue(PhantomKiller.needed(31))
        assertTrue(PhantomKiller.needed(34))
    }

    @Test
    fun `killer disabled on false and zero`() {
        assertFalse(PhantomKiller.killerEnabled("false"))
        assertFalse(PhantomKiller.killerEnabled("0"))
        assertFalse(PhantomKiller.killerEnabled(" FALSE "))
    }

    @Test
    fun `killer enabled on true one null and unknown`() {
        assertTrue(PhantomKiller.killerEnabled("true"))
        assertTrue(PhantomKiller.killerEnabled("1"))
        assertTrue(PhantomKiller.killerEnabled(null))
        assertTrue(PhantomKiller.killerEnabled(""))
        assertTrue(PhantomKiller.killerEnabled("garbage"))
    }

    @Test
    fun `grant command is exact`() {
        assertEquals(
            "pm grant dev.kair.app android.permission.WRITE_SECURE_SETTINGS",
            PhantomKiller.grantCommand("dev.kair.app"),
        )
    }

    @Test
    fun `put command targets only the one key`() {
        assertEquals(
            "settings put global settings_enable_monitor_phantom_procs 0",
            PhantomKiller.putCommand(false),
        )
        assertEquals(
            "settings put global settings_enable_monitor_phantom_procs 1",
            PhantomKiller.putCommand(true),
        )
        assertEquals("settings get global settings_enable_monitor_phantom_procs", PhantomKiller.getCommand())
    }

    @Test
    fun `parse address accepts valid and rejects invalid`() {
        assertEquals("192.168.1.5" to 37123, PhantomKiller.parseAddress("192.168.1.5:37123"))
        assertEquals("127.0.0.1" to 5555, PhantomKiller.parseAddress(" 127.0.0.1:5555 "))
        assertEquals(null, PhantomKiller.parseAddress("192.168.1.5"))
        assertEquals(null, PhantomKiller.parseAddress("192.168.1.5:"))
        assertEquals(null, PhantomKiller.parseAddress("192.168.1.5:abc"))
        assertEquals(null, PhantomKiller.parseAddress("192.168.1.5:0"))
        assertEquals(null, PhantomKiller.parseAddress("192.168.1.5:70000"))
        assertEquals(null, PhantomKiller.parseAddress(":5555"))
    }
}
