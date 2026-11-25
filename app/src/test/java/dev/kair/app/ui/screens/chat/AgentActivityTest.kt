package dev.kair.app.ui.screens.chat

import dev.kair.app.R
import dev.kair.app.core.agent.TurnState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 运行态状态条的文案映射（U58 ②）。
 *
 * 只看状态，不看渲染：`null` 现在只有一种成因 —— 审批 / 提问卡片在场（卡片本身就是
 * 「在等你」，再写「正在运行」会误导）。空闲与非回合的串行动作都给「待机」：状态条
 * 常驻才留得住一行高度（2026-11-20）。
 */
class AgentActivityTest {

    @Test
    fun `空闲给待机`() {
        assertEquals(R.string.chat_state_idle, activityLabelRes(TurnState.Idle, awaitingUser = false))
    }

    @Test
    fun `非回合的串行动作也不算 agent 在跑，但那一行不能空`() {
        assertEquals(R.string.chat_state_idle, activityLabelRes(TurnState.Busy, awaitingUser = false))
    }

    @Test
    fun `运行中显示正在运行`() {
        assertEquals(R.string.chat_running, activityLabelRes(TurnState.Running(), awaitingUser = false))
    }

    @Test
    fun `压缩相位显示正在压缩`() {
        assertEquals(
            R.string.chat_compacting,
            activityLabelRes(TurnState.Running(compacting = true), awaitingUser = false),
        )
    }

    @Test
    fun `停止收尾显示正在停止`() {
        assertEquals(R.string.chat_stopping, activityLabelRes(TurnState.Stopping, awaitingUser = false))
    }

    @Test
    fun `等待用户时一律不显示`() {
        assertNull(activityLabelRes(TurnState.Running(), awaitingUser = true))
        assertNull(activityLabelRes(TurnState.Running(compacting = true), awaitingUser = true))
        assertNull(activityLabelRes(TurnState.Stopping, awaitingUser = true))
    }

    @Test
    fun `本会话均速后缀在工具与停止等中间状态也拼在后面`() {
        // U115 真机反馈：工具执行 / 停止时读数整段消失。后缀只由「有没有读数」决定，
        // 与状态、实时值都无关；没有读数时保持原样，不写 0。
        // U115 返工：后缀是本会话均速（ChatSpeed.sessionSpeedText），不是本轮那个数。
        assertEquals(
            "正在运行… · 本会话均速 27 tok/s",
            withSpeedSuffix("正在运行…", "本会话均速 27 tok/s"),
        )
        assertEquals(
            "正在停止… · 本会话均速 27 tok/s",
            withSpeedSuffix("正在停止…", "本会话均速 27 tok/s"),
        )
        assertEquals(
            "正在生成 · 32 tok/s · 本会话均速 27 tok/s",
            withSpeedSuffix("正在生成 · 32 tok/s", "本会话均速 27 tok/s"),
        )
        assertEquals("待机", withSpeedSuffix("待机", null))
    }
}
