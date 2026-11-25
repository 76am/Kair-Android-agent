package dev.kair.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * 色板「简约白与蓝」（2026-11-17 U102，接替 U96「图纸与焊点」）。
 *
 * 主张：**颜色即角色**，只留两支强调色，互不借用 ——
 * - 朱红（solder）只归「你」：输入、审批、危险命令；
 * - 蓝（signal）归「系统与此刻」：agent 在跑、终端、成功、当前回合、当前默认。
 *
 * U106（2026-11-18）：黄铜（brass）退场。它原本只标「此刻 / 需要你看一眼」，
 * 却和蓝色的「agent 在跑」在同一屏里抢注意力，设置页尤其花；现在全部并入蓝。
 * `tertiary` 保留为 primary 的别名，只为 M3 槽位完整，UI 不再直接使用。
 *
 * 浅色全面转简约：主体白（ground #FFFFFF）、字体黑（#16181A）、层级靠**可辨的面 + 描边**
 * 共同承担（U105 重配：纯白上只靠 1.2:1 的 hairline 站不住）；dock 控件行的
 * surfaceVariant 灰面原样保留（负责人拍板：排版与灰面不动）。深色地面不动，只把青碧
 * 换成亮蓝。对比度仍按 WCAG 实算口径挑选。
 */
private object Ink {

    /** 手机底 / 消息流。偏靛的墨，不是纯黑 —— 纯黑上做任何抬升都像补丁。 */
    val ground = Color(0xFF0C141C)

    /** 坞、面板覆盖层、卡片。 */
    val slab = Color(0xFF16202B)

    /** 输入框、面板格子、需要再抬一层的面。 */
    val slabHigh = Color(0xFF1E2A36)

    /** 最抬一层：对话框、弹出菜单。 */
    val slabHighest = Color(0xFF26333F)

    /** 比 ground 略沉：分段列表的偶数行、代码块的底。 */
    val slabLow = Color(0xFF101A24)

    /** 最深：终端底、图片容器。 */
    val pit = Color(0xFF070D13)

    /** 正文。米白偏暖，与靛墨是补色关系，久看不刺眼（在 ground 上 14.51:1）。 */
    val paper = Color(0xFFE9E3D7)

    /** 次要文字 / 工具输出（在 ground 上 7.22:1，在 slabHigh 上 5.68:1）。 */
    val dim = Color(0xFF97A3AF)

    /** 装饰线：分界、坞边框。**不当控件边界用** —— 在 ground 上只有 1.4:1。 */
    val line = Color(0xFF24313F)

    /**
     * 控件边界（输入框描边、编辑器边框）。比 [line] 亮一档是刻意的：
     * 描边承担「这里是个可点的框」，WCAG 1.4.11 要求 3:1，在 ground 上 3.53:1、
     * 在 slab 上 3.13:1。设计稿里的 `--line` 只画分界，不画控件边。
     */
    val edge = Color(0xFF5C6E80)

    /** 朱红：你 / 审批 / 危险（文本用，在 ground 上 5.08:1）。 */
    val solder = Color(0xFFE05C33)

    /** 蓝：agent 在跑 / 终端 / 成功（U102，对齐主线 IndigoLight；在 ground 上 ≈7:1）。 */
    val signal = Color(0xFFB4B0FF)

    /** 审批闸门的底与字：朱红压深的一层，与 ground 只差一点色温（11.18:1）。 */
    val gate = Color(0xFF2A1712)
    val gateText = Color(0xFFF0C9B8)

    /** 蓝的容器阶：色调底 + 亮字（对齐主线 primaryContainer 族）。 */
    val signalDeep = Color(0xFF332D7A)
    val onSignalDeep = Color(0xFFE4E1FF)

    /** 实心蓝上的字：亮蓝底要墨（白在 #B4B0FF 上只有 ≈2:1）。 */
    val onSignal = Color(0xFF1B1550)
}

/**
 * 浅色色板「简约白与蓝」：主体白、字体黑，层级靠**可辨的面 + 描边**，不是深色反转。
 *
 * U105（2026-11-18）按界面审查 P0-2 重配抬升面：纯白地面上 `#FDFDFD` 只有 1.02:1、
 * `#F5F6F7` 只有 1.08:1，输入板 / agent 块 / 工具卡在浅色里等于消失；`#E9EAEC` 的
 * hairline 也只有 1.20:1，图标型悬浮键的边界不满足 WCAG 1.4.11 的 3:1。
 * 现在面本身拉开到 1.06 / 1.10 / 1.15，描边到 1.37；控件边界仍单独用 [edge]（3.78:1）。
 */
private object Pulp {

    /** 主体白：页面底、消息流（U102）。 */
    val ground = Color(0xFFFFFFFF)
    val slab = Color(0xFFF7F8FA)
    val slabHigh = Color(0xFFF2F4F6)
    val slabHighest = Color(0xFFFFFFFF)
    val slabLow = Color(0xFFECEFF2)
    val pit = Color(0xFFFFFFFF)

    /** 正文黑（在 ground 上 ≈16:1）。 */
    val inkText = Color(0xFF16181A)

    /** 次要文字（≈7:1）。 */
    val dim = Color(0xFF5A5F64)

    /** hairline 装饰线：分界、卡片描边（对白 1.37:1，只画分界、不画控件边）。 */
    val line = Color(0xFFD8DDE1)

    /** 控件边界：在 ground 上 ≈3.5:1 —— 与深色同一个权衡，见 [Ink.edge]。 */
    val edge = Color(0xFF7A858A)

    /** 朱红压深（白字 6.47:1）。浅色不能直接用 #E05C33，白底上糊。 */
    val solder = Color(0xFFA83818)

    /** 蓝（U102 对齐主线 Indigo #4F46E5）：白字 ≈6.3:1，在 ground 上 ≈6:1。 */
    val signal = Color(0xFF4F46E5)

    /** 审批闸门 / 用户气泡：纸上的朱红晕开一层（8.37:1）。 */
    val gate = Color(0xFFFBEDE8)
    val gateText = Color(0xFF7A2C12)

    val signalDeep = Color(0xFFE4E1FF)
    val onSignalDeep = Color(0xFF1A1550)

    val onSignal = Color(0xFFFFFFFF)
}

/**
 * 深色：墨与纸。
 *
 * `surfaceTint` 显式关掉（M3 默认按 primary 给每层做色调叠加，会把主色渗进所有面）；
 * 这里的层次靠色面本身（ground → slab → slabHigh）拉开，不靠混色。
 */
private val DarkColors = darkColorScheme(
    primary = Ink.signal,
    onPrimary = Ink.onSignal,
    primaryContainer = Ink.signalDeep,
    onPrimaryContainer = Ink.onSignalDeep,
    inversePrimary = Pulp.signal,
    // secondary 在代码里是「次要标签」色（Composer 的快捷指令提示等），不参与强调。
    secondary = Ink.dim,
    onSecondary = Ink.ground,
    secondaryContainer = Ink.signalDeep,
    onSecondaryContainer = Ink.onSignalDeep,
    // tertiary 是 primary 的别名：M3 槽位必须填，但 UI 不再使用第三支强调色。
    tertiary = Ink.signal,
    onTertiary = Ink.onSignal,
    tertiaryContainer = Ink.signalDeep,
    onTertiaryContainer = Ink.onSignalDeep,
    // error = 朱红：失败、危险命令、删除。实心按钮上是亮底 + 墨字（白字只有 3.2:1，不合格）。
    error = Ink.solder,
    onError = Ink.ground,
    errorContainer = Ink.gate,
    onErrorContainer = Ink.gateText,
    background = Ink.ground,
    onBackground = Ink.paper,
    surface = Ink.ground,
    onSurface = Ink.paper,
    surfaceVariant = Color(0xFF1B2733),
    onSurfaceVariant = Ink.dim,
    surfaceTint = Color.Transparent,
    inverseSurface = Ink.paper,
    inverseOnSurface = Ink.ground,
    surfaceDim = Color(0xFF0A1119),
    surfaceBright = Color(0xFF2E3C4A),
    surfaceContainerLowest = Ink.pit,
    surfaceContainerLow = Ink.slabLow,
    surfaceContainer = Ink.slab,
    surfaceContainerHigh = Ink.slabHigh,
    surfaceContainerHighest = Ink.slabHighest,
    outline = Ink.edge,
    outlineVariant = Ink.line,
    scrim = Color.Black,
)

/** 浅色：硫酸纸与墨。槽位与深色一一对应，值全部重配过（不是反转）。 */
private val LightColors = lightColorScheme(
    primary = Pulp.signal,
    onPrimary = Pulp.onSignal,
    primaryContainer = Pulp.signalDeep,
    onPrimaryContainer = Pulp.onSignalDeep,
    inversePrimary = Ink.signal,
    secondary = Pulp.dim,
    onSecondary = Color.White,
    secondaryContainer = Pulp.signalDeep,
    onSecondaryContainer = Pulp.onSignalDeep,
    // 与深色同理：tertiary 只是 primary 的别名。
    tertiary = Pulp.signal,
    onTertiary = Pulp.onSignal,
    tertiaryContainer = Pulp.signalDeep,
    onTertiaryContainer = Pulp.onSignalDeep,
    error = Pulp.solder,
    onError = Color.White,
    errorContainer = Pulp.gate,
    onErrorContainer = Pulp.gateText,
    background = Pulp.ground,
    onBackground = Pulp.inkText,
    surface = Pulp.ground,
    onSurface = Pulp.inkText,
    surfaceVariant = Color(0xFFDDE3E4),
    onSurfaceVariant = Pulp.dim,
    surfaceTint = Color.Transparent,
    inverseSurface = Pulp.inkText,
    inverseOnSurface = Pulp.ground,
    surfaceDim = Color(0xFFF0F1F2),
    surfaceBright = Pulp.slabHigh,
    surfaceContainerLowest = Pulp.pit,
    surfaceContainerLow = Pulp.slabLow,
    surfaceContainer = Pulp.slab,
    surfaceContainerHigh = Pulp.slabHigh,
    surfaceContainerHighest = Pulp.slabHighest,
    outline = Pulp.edge,
    outlineVariant = Pulp.line,
    scrim = Color.Black,
)

/**
 * 全局圆角只有这一处定义。输入框（extraSmall）、菜单 / 小标签（small）、卡片（medium）、
 * 气泡与面板（large）、对话框（extraLarge）都从 MaterialTheme.shapes 取，别再在组件里写死 dp。
 *
 * 比 M3 默认值收紧一档（默认 4/8/12/16/28 之上还有一套 pill 化的组件）：
 * 「图纸与焊点」的边角是工件边角，不是气泡，20 以上的圆角会把密度感抹掉。
 */
private val KairShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/**
 * 用户消息气泡：四面 18dp，**右下角收到 6dp** —— 那一点收口指向「这句话是从右边说的」，
 * 不用箭头、不用尖角，也不会像对话软件那样把圆角做成一颗药丸。
 *
 * 单独定义而不进 [KairShapes]：`Shapes` 是 M3 的五个语义档，接不了「某一个角不一样」。
 */
val UserBubbleShape = RoundedCornerShape(
    topStart = 18.dp,
    topEnd = 18.dp,
    bottomStart = 18.dp,
    bottomEnd = 6.dp,
)

/**
 * 当前生效的深浅（跟随 [KairTheme] 的入参，**不是** `isSystemInDarkTheme()`）。
 *
 * 下面几个自定义槽位不在 [ColorScheme] 里，只能按「当前是哪套」取值；
 * 用系统值判断会在 `KairTheme(darkTheme = ...)` 被显式指定时错配
 * （U94 的 UI 测试底座就靠显式指定跑深浅两套截图）。
 */
private val LocalKairDark = staticCompositionLocalOf { false }

/**
 * Material3 的语义色槽里没有 success，这里补一对明暗自适应的蓝：工具**成功输出**
 * 用浅蓝底、diff 新增行用蓝字（见 `ChatScreen` 工具卡片），与错误的朱红形成对照。
 */
val ColorScheme.successContainer: Color
    @Composable get() = if (LocalKairDark.current) Ink.signalDeep else Pulp.signalDeep

/** 浅蓝底上的正文 / 蓝字（两套都在 9:1 以上）。 */
val ColorScheme.onSuccessContainer: Color
    @Composable get() = if (LocalKairDark.current) Ink.onSignalDeep else Pulp.onSignalDeep

/**
 * 悬浮在内容之上的控件（发送 / 停止、回到底部 ↓、首页新建 +）的容器色。
 *
 * U102 拍板：**白底黑字**（负责人「跟主线一样」）。浅色取 `surfaceContainerHighest`
 * （纯白）+ 墨字；深色维持纸底墨字（`onSurface` / `surface` 自适应）。
 * 白圆在白板上靠 hairline 描边与 FAB 阴影站住，不靠染色 —— 见各调用点的 border。
 */
val ColorScheme.floatingContainer: Color
    @Composable get() = if (LocalKairDark.current) onSurface else surfaceContainerHighest

/** 悬浮面上的图标色：白底黑字（浅）/ 纸底墨字（深）。 */
val ColorScheme.onFloatingContainer: Color
    @Composable get() = if (LocalKairDark.current) surface else onSurface

/**
 * 上下文圆环的**可点圆底**（U115）：浅色用与旁边 chip 同一块 `surfaceVariant` 灰面，
 * 深色用比输入板亮一档的 `surfaceContainerHighest` —— 两套都是「底从板里跳出来」，
 * 方向相反而已。只给可点的环垫；只报读数的环不垫底（「有底 = 能点」）。
 */
val ColorScheme.gaugeDisc: Color
    @Composable get() = if (LocalKairDark.current) surfaceContainerHighest else surfaceVariant

/** 圆环轨道：要在圆底上仍看得见的一档（浅色白、深色沉），与 [gaugeDisc] 成对使用。 */
val ColorScheme.gaugeTrack: Color
    @Composable get() = if (LocalKairDark.current) surfaceContainerLow else surface

/**
 * 字阶（U97）：设计稿「字体与字阶」那一节落到 M3 的九个角色上。
 *
 * 四条原始规格 —— 标题 17/700/-0.02em、正文 14/400/1.65、数据 11.5/500（等宽）、
 * 标签 10/500/.16em（等宽）—— 不能只当四个字号用：M3 的 `bodyMedium` 到处都是，
 * 它才是「这个 App 看起来是什么样」的真正载体。
 *
 * 两条不准改的取舍：
 * 1. **行高一律相对值**（1.6–1.68），不写死 sp。用户把字号放大到 1.3× 时，
 *    行高跟着放大，否则中文字会被自己的行高切边；
 * 2. **中文不内置字体**。Noto Sans SC 全量十几 MB，包体换不来观感差异（系统字体
 *    在 MIUI / AOSP 上都是思源黑体一系）。等宽只用于数据与标签，`FontFamily.Monospace`
 *    落到设备自带的等宽字体上，数字立刻有「仪表读数」的样子。
 */
private val KairTypography = Typography(
    // 顶栏标题、屏内大标题
    titleLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 17.sp,
        lineHeight = 24.sp,
        letterSpacing = (-0.02).em,
    ),
    // 卡片 / 面板标题
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.01).em,
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.em,
    ),
    // 正文：14 / 1.65 —— 设计稿的正文规格，中文在这个行高下才不挤。
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.01.em,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 23.sp,
        letterSpacing = 0.01.em,
    ),
    // 次要说明、诊断文本
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.5.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.01.em,
    ),
    // 按钮与坞格主标签
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.5.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.02.em,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.5.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.04.em,
    ),
    // 最小一档：时间戳、计数、状态。字距拉到 0.08em 才有「刻字」的味道
    // （设计稿写 .16em，那是给纯大写英文的；中文跟着拉会散架，折中一半）。
    // U105 决定保留 10.5sp：它是**装饰性元数据**档、随系统字号缩放，不属于正文；
    // 读不出来的精确数值（如上下文百分比）不要塞进这一档，另找位置或放进读屏描述。
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 10.5.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.08.em,
    ),
)

/** 数据档（[KairTypography] 的第四条规格）：等宽，用于工具名、耗时、token 数、路径。 */
val MonoData: TextStyle
    @Composable get() = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace)

/**
 * 转录里的「工具层」字号（U98）：等宽 11.5 / 行高 19。
 *
 * 与 [MonoData] 同字号，但行高按设计稿 `.tool` 的 1.7 倍单独给 —— 工具输出是多行代码 / 日志，
 * 用标签档的行高会挤成一片；而正文那档（23）又会让十行日志占掉半屏。
 */
val MonoTrace: TextStyle
    @Composable get() = MonoData.copy(lineHeight = 19.sp)

/** 工具层的注（行数 / 耗时 / 状态）：比 [MonoTrace] 小一档，靠字号拉开层级，不靠透明度。 */
val MonoTraceMeta: TextStyle
    @Composable get() = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace)

@Composable
fun KairTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }
    CompositionLocalProvider(LocalKairDark provides darkTheme) {
        MaterialTheme(colorScheme = colors, shapes = KairShapes, typography = KairTypography, content = content)
    }
}
