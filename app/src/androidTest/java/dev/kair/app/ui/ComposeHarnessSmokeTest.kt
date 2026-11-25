package dev.kair.app.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 底座自检：**证明链路通**，不证明业务正确。
 *
 * 这条用例只做三件事：能起 Activity、能渲染带主题的文字、能把根节点截成 PNG。
 * 三者任一失败 —— 缺 `ui-test-manifest`、BOM 与 ui 版本不一致、设备侧没有可写目录 ——
 * 后面的业务用例都会以难以归因的方式一起挂，所以先单列一条便宜的探针。
 */
@RunWith(AndroidJUnit4::class)
class ComposeHarnessSmokeTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun renders_text_with_kair_theme() {
        rule.setKairContent(widthDp = 320, heightDp = 200) {
            Text("KAIR-UI-HARNESS-OK")
        }
        rule.onNodeWithText("KAIR-UI-HARNESS-OK").assertExists()
    }

    @Test
    fun captures_screenshot_to_pullable_path() {
        rule.setKairContent(widthDp = 320, heightDp = 200) {
            Text("KAIR-UI-HARNESS-OK")
        }
        val path = rule.dumpScreenshot("00-harness-smoke")
        // 判据是「文件真的非空」：captureToImage 在 API 26 上经 PixelCopy 取窗口，
        // 若窗口没画完会得到一张全透明图，尺寸对但内容空 —— 只查存在会漏。
        val bytes = java.io.File(path).length()
        org.junit.Assert.assertTrue("截图 $path 只有 $bytes 字节，窗口没画完", bytes > 1024)
    }
}
