package dev.kair.app.core.settings

import dev.kair.app.app.fakes.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `maxSteps` 的静钳（U20）：0 / 负数会让 `TurnRunner` 在并入点之前 `break@inner` ——
 * 通知永不并入、收尾 sweep 空跑 `runLoop`、回合永不结束（活锁）。
 * 口径由负责人拍板：**静钳到 1**，不弹错。setter 钳新输入，getter 钳历史落盘的脏值
 * （老设备不必重进设置页）。行为侧判据（「回合仍能结束、通知仍能并入」）在
 * `AgentSessionLoopTest.maxStepsFloorOneStillTerminatesAndMergesSteerNote`。
 */
class SettingsStoreMaxStepsTest {

    @Test
    fun `default is 200`() {
        assertEquals(200, SettingsStore(FakeSharedPreferences()).maxSteps)
    }

    @Test
    fun `zero is silently clamped to one`() {
        val store = SettingsStore(FakeSharedPreferences())
        store.maxSteps = 0
        assertEquals(1, store.maxSteps)
    }

    @Test
    fun `negative is silently clamped to one`() {
        val store = SettingsStore(FakeSharedPreferences())
        store.maxSteps = -3
        assertEquals(1, store.maxSteps)
    }

    @Test
    fun `legal values pass through untouched`() {
        val store = SettingsStore(FakeSharedPreferences())
        store.maxSteps = 7
        assertEquals(7, store.maxSteps)
    }

    @Test
    fun `legacy stored zero is repaired on read`() {
        // 钳制之前落盘的 0：getter 也必须钳 —— 否则老设备升级后回合照样活锁。
        val prefs = FakeSharedPreferences()
        prefs.edit().putInt("max_steps", 0).apply()
        assertEquals(1, SettingsStore(prefs).maxSteps)
    }
}
