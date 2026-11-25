package dev.kair.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.kair.app.ui.theme.KairTheme
import java.io.File
import java.io.FileOutputStream

/**
 * Compose UI 测试底座（U94）。
 *
 * 存在的理由：本仓库此前只有 [dev.kair.app.core.proot.PtySmokeTest] 一个 instrumented
 * 探针，没有任何能在真机上渲染 composable 并断言的能力 —— 于是每次改界面都只能
 * 「构建绿 → 请人点一遍」。这里把三件必须具备的事固定下来：
 *
 * 1. **真实主题**：所有用例都包在 [KairTheme] 里。脱离主题测出来的间距 / 颜色不作数，
 *    因为 M3 的组件尺寸来自整套 colorScheme 与 typography。
 * 2. **可控视口与字号**：`widthDp` / `heightDp` / `fontScale` 三个参数。窄屏（320dp）与
 *    无障碍放大（1.3×）在真机上没法随手切，但在这里就是三个实参。
 * 3. **截图落盘**：[dumpScreenshot] 把当前根节点画成 PNG 写进 App 自己的外部私有目录
 *    （`/sdcard/Android/data/<pkg>/files/kair-uitest/`，API 26 起免存储权限），
 *    宿主机 `adb pull` 出来即可目视核对 —— 这是「眼睛」的部分，断言只能证明节点存在，
 *    证明不了它被切掉了半边。
 *
 * 刻意**不**做全局单例或 Rules 链：每条用例自己 `createComposeRule()`，
 * 失败时堆栈直接落在用例里，不用猜是哪层包装吃掉了异常。
 */
internal fun ComposeContentTestRule.setKairContent(
    dark: Boolean = true,
    widthDp: Int? = null,
    heightDp: Int? = null,
    fontScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    setContent {
        val base = LocalDensity.current
        // 只换 fontScale，保留设备真实密度：字体缩放是系统设置，密度不是。
        CompositionLocalProvider(
            LocalDensity provides Density(density = base.density, fontScale = fontScale),
        ) {
            KairTheme(darkTheme = dark) {
                val box = if (widthDp != null && heightDp != null) {
                    Modifier.size(widthDp.dp, heightDp.dp)
                } else {
                    Modifier.fillMaxSize()
                }
                // 铺一层底色再画内容：`captureToImage` 会把没有绘制的像素输出成**透明**，
                // 拉回来看是灰白格子 —— 判断不了「深色主题下这块对比够不够」（U97 修）。
                Box(box.background(MaterialTheme.colorScheme.background)) { content() }
            }
        }
    }
}

/** 截图写到测试目标 App 的外部私有目录，返回宿主机可 `adb pull` 的绝对路径。 */
internal fun ComposeContentTestRule.dumpScreenshot(name: String): String {
    waitForIdle()
    val bitmap = onRoot().captureToImage().asAndroidBitmap()
    val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    val dir = File(ctx.getExternalFilesDir(null), "kair-uitest")
    dir.mkdirs()
    val file = File(dir, "$name.png")
    FileOutputStream(file).use { out ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    }
    return file.absolutePath
}
