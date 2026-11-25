package dev.kair.app.core.subagent

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserRunGateTest {

    @Test
    fun `enter and exit notify the host once`() {
        var started = 0
        var finished = 0
        val gate = BrowserRunGate(onStarted = { started++ }, onFinished = { finished++ })

        gate.enter()
        gate.exit()

        assertEquals(1, started)
        assertEquals(1, finished)
    }

    @Test
    fun `exit without enter is a no-op`() {
        var finished = 0
        val gate = BrowserRunGate(onFinished = { finished++ })

        gate.exit()

        assertEquals(0, finished)
    }

    @Test
    fun `host callback failure stays out of the delegation`() {
        // 宿主关页失败（窗口已销毁等）不能把委派结果带崩。
        val gate = BrowserRunGate(
            onStarted = { error("start failed") },
            onFinished = { error("finish failed") },
        )

        gate.enter()
        gate.exit()
    }
}
