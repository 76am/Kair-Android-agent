package dev.kair.app.core.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 空回复重试的时间窗：云端模型「久一点才回、回了个空」不该被再发一次请求 ——
 * 那既救不回来，又让用户以为回合被打断（真机两次都是子智能体 + 云端模型）。
 */
class EmptyResponseRetryTest {

    @Test
    fun `fast empty response is retried`() {
        assertTrue(shouldRetryEmpty(1_500))
    }

    @Test
    fun `slow empty response is not retried`() {
        assertFalse(shouldRetryEmpty(30_000))
    }

    @Test
    fun `window boundary is inclusive`() {
        assertTrue(shouldRetryEmpty(EmptyRetryFastWindowMs))
        assertFalse(shouldRetryEmpty(EmptyRetryFastWindowMs + 1))
    }
}
