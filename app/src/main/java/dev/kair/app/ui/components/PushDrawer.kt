package dev.kair.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.kair.app.ui.plugins.builtin.rememberPanelBackToggle
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 抽屉展开后会话卡的位移比例（U115 拍板 65%）：卡片整块右移 65% 屏宽，
 * 抽屉内容列只排进这 65% 的可见区。一个数同时决定位移与内容列宽，避免两边漂移。
 */
internal const val PUSH_DRAWER_FRACTION = 0.65f

/**
 * 起拖死区：平台 `touchSlop`（16dp）。不再往上拍 24dp：裁决只过一次（见 [drawerDragClaim]），
 * 打字时随手横扫那点行程本来就到不了 16dp，多出来的 8dp 只会让真甩动变钝。
 */
internal val PushDrawerDeadZone = 16.dp

/** 收起状态边缘拉开，松手时进度过这个值就吸附到展开。 */
internal const val PUSH_DRAWER_OPEN_THRESHOLD = 0.35f

/**
 * 展开态松手收起的进度阈值：**比展开阈值宽松**。
 *
 * 推屏抽屉展开后页面只剩一条，用户往回拨的意图很明确；要求拖过半屏才收，
 * 真机手感就是「怎么滑都收不回去」（负责人 2026-11-23 反馈）。0.8 意味着
 * 往回拨约 13% 屏宽就收起，同时短于死区的轻点仍然不动它。
 */
internal const val PUSH_DRAWER_CLOSE_THRESHOLD = 0.8f

/**
 * 展开进度（0..1）→ 卡片位移像素：**屏宽 × 65% × 进度**。
 *
 * 65% 必须在这里乘上：调用方传的是 0..1 的进度，不是已经算好的比例。
 * 之前漏乘会让卡片在展开时整整右移一屏（真机反馈「直接百分百右滑」）。
 */
internal fun pushDrawerTranslationX(widthPx: Float, progress: Float): Float =
    widthPx * PUSH_DRAWER_FRACTION * progress.coerceIn(0f, 1f)

/**
 * 拖动像素 → 展开进度增量。
 *
 * 分母是卡片**实际行程**（65% 屏宽），不是整屏宽；否则卡片只走 65%，手指却按 100%
 * 换算，拖动会明显跑得比手快。
 */
internal fun pushDrawerDragDelta(deltaPx: Float, widthPx: Float): Float =
    if (widthPx <= 0f) 0f else deltaPx / (widthPx * PUSH_DRAWER_FRACTION)

/** 一次拖动的方向裁决：未定 / 归抽屉 / 归列表（本条手势内不再改判）。 */
internal enum class DrawerDragClaim { Pending, Claimed, Rejected }

/**
 * 起拖裁决（U115 返工第三轮）：行程过 [slopPx] 时**一次定生死**，之后整条手势不再改判。
 *
 * 「横向」两条判据，**任一**成立就算：
 * - 位移更横：`|totalX| > |totalY|`；
 * - 速度更横：`|velocityX| > |velocityY|`。
 *
 * 为什么必须带上速度：拇指是**弧形**拖的，累计纵向分量会一路涨，只看位移就会把
 * 「斜一点的右滑」判给列表 —— 真机两轮反馈（方向比 1.2 倍、1.0 倍）都在说同一件事：
 * 「滑不够直就被识别成上下滑」。甩动的意图在速度里，不在累计位移里。
 *
 * 为什么只判一次：判给列表（[DrawerDragClaim.Rejected]）就这条手势彻底不碰抽屉，
 * 消掉「列表先滚了半屏、抽屉才跟上」那种既糊又难认的中间态。
 *
 * 第三条闸门仍是**方向符合状态**：收起态只接右滑（输入板下沿 chip 行往左滚仍归它），
 * 展开态只接左滑（抽屉里的列表要能点、能滚）。
 *
 * [childOwnsHorizontal] 是最高优先级：横向滚动子区域（Markdown 表格）按下即登记，
 * 表格右滚与抽屉右滑方向完全相同，方向判据分不出来，只能由离手指最近的滚动面接管。
 */
internal fun drawerDragClaim(
    totalX: Float,
    totalY: Float,
    velocityX: Float,
    velocityY: Float,
    slopPx: Float,
    opening: Boolean,
    childOwnsHorizontal: Boolean = false,
): DrawerDragClaim {
    if (childOwnsHorizontal) return DrawerDragClaim.Rejected
    if (hypot(totalX, totalY) < slopPx) return DrawerDragClaim.Pending
    val horizontal = abs(totalX) > abs(totalY) || abs(velocityX) > abs(velocityY)
    if (!horizontal) return DrawerDragClaim.Rejected
    // 位移为零的纯甩动（起手就快甩）用速度定方向。
    val direction = if (totalX != 0f) totalX else velocityX
    val matches = if (opening) direction > 0f else direction < 0f
    return if (matches) DrawerDragClaim.Claimed else DrawerDragClaim.Rejected
}

/**
 * 展开态轻点是否收抽屉（U115 返工）：**只有落在会话卡区域**（`x >= 卡片左缘`）的轻点才收。
 *
 * 抽屉内部一律不收：会话条目、分段、项目行、「⋯」、列底新建都是抽屉自己的控件，
 * 轻点完抽屉就收起等于逼用户重开（真机反馈）。重命名 / 删除的 `AlertDialog` 在独立窗口里，
 * 不经过手势层，但触发它们的**长按**在这层 —— 不收才能用。
 * 收起的另一条路是左滑（`drawerDragAccepted`）与返回键。
 */
internal fun shouldCloseOnTap(xPx: Float, widthPx: Float, progress: Float, open: Boolean): Boolean =
    open && xPx >= widthPx * PUSH_DRAWER_FRACTION * progress

/** 开合 / 吸附共用的弹簧；抽屉自己的节奏只有这一处。 */
private val DrawerSpring = spring<Float>(stiffness = Spring.StiffnessMediumLow)

/**
 * 松手落点：只看**视觉进度**与速度，不看宿主的 `open`。
 *
 * 手势被系统 / 重组打断时，画面进度与宿主状态可能脱节；按状态判会把已经拉开的抽屉
 * 当成关闭态 —— 左滑被拒、轻点也不收，抽屉锁死在屏上（真机：无项目页右滑拉开后收不回）。
 * [startedOpen] 是起手那一刻画面所在的一侧（`progress >= 0.5`），两侧共用同一组阈值。
 */
internal fun drawerSettle(progress: Float, velocityPxPerSec: Float, startedOpen: Boolean): Boolean =
    if (startedOpen) !shouldClosePushDrawer(progress, velocityPxPerSec)
    else shouldOpenPushDrawer(progress, velocityPxPerSec)

/** 收起状态松手：过阈值、或向右甩都吸附到展开。 */
internal fun shouldOpenPushDrawer(progress: Float, velocityPxPerSec: Float): Boolean =
    progress >= PUSH_DRAWER_OPEN_THRESHOLD || velocityPxPerSec > 0f

/** 展开状态松手：只要不是几乎没动、或者向左甩，都收起。 */
internal fun shouldClosePushDrawer(progress: Float, velocityPxPerSec: Float): Boolean =
    progress < PUSH_DRAWER_CLOSE_THRESHOLD || velocityPxPerSec < 0f

/**
 * DeepSeek 式推屏抽屉（U115）：替换 M3 `ModalNavigationDrawer` 的「盖住」效果。
 *
 * 两层结构：
 * - 下层抽屉：背景铺满整屏，内容列只占 [PUSH_DRAWER_FRACTION]（页面收起后仍可见的部分）；
 * - 上层页面：整块用 `offset` 右移 65% 屏宽，**不缩放、不套 graphicsLayer**。
 *
 * 手势（U115 返工第三轮）：**整屏任意位置**右滑拉开（16dp 行程 + 一次定生死），
 * 展开后整屏左滑 / 点页面区域 / 返回键 / 抽屉头的关闭按钮都能收起。
 * 手势层是**稳定**的全屏父层（页面自己在布局上位移），所以拖动坐标不会跟着页面跑。
 * 累计位移必须用 `positionChangeIgnoreConsumed()`：转录区的 `LazyColumn` 一开始滚就会消费位移，
 * 用 `positionChange()` 拿到的增量会被清零，抽屉永远攒不够行程（真机反馈「识别成上下滑」的真凶）。
 *
 * 状态只有 [progress] 一个量（0 收起、1 展开），开合动画与拖动都写它；
 * 位移由布局按同一个值算，收起时严格回到 0。
 */
@Composable
internal fun PushDrawer(
    open: Boolean,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    drawerContent: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val progress = remember { Animatable(if (open) 1f else 0f) }
    val scope = rememberCoroutineScope()
    // 横向可滚的子区域（Markdown 表格）按下时登记，抽屉整条手势放弃 ——
    // 否则表格的右滑会先被抽屉拿走，表格永远滚不动。
    val exclusion = remember { HorizontalPanExclusion() }
    LaunchedEffect(open) {
        progress.animateTo(
            targetValue = if (open) 1f else 0f,
            animationSpec = DrawerSpring,
        )
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val slopPx = with(LocalDensity.current) { PushDrawerDeadZone.toPx() }
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(open, widthPx, slopPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // 起手方向按**画面进度**判，不按宿主 open：手势被系统 / 重组打断后
                        // 两者可能脱节，按 open 判会把已经拉开的抽屉当成关闭态 —— 左滑被拒。
                        val startedOpen = progress.value >= 0.5f
                        val opening = !startedOpen
                        // 子区域（横向滚动面）先登记时，这条手势整个归它：方向判据分不出
                        // 「表格右滑」与「抽屉右滑」，只能由离手指最近的滚动面裁定。
                        var claim = if (exclusion.active) DrawerDragClaim.Rejected else DrawerDragClaim.Pending
                        // 起手不限制位置（左边缘 20dp 拇指根本够不着），
                        // 但轻点关闭只看会话卡区域（`shouldCloseOnTap`）：抽屉里的控件点完不该把抽屉弄没。
                        var totalX = 0f
                        var totalY = 0f
                        var velX = 0f
                        var velY = 0f
                        var lastTime = down.uptimeMillis
                        // 手势内的权威进度：snapTo 是异步写入，松手判定读 `progress.value`
                        // 会滞后（甚至读到旧值把左滑判成展开）。
                        var visual = progress.value
                        // 只保留最后一次拖动写入；松手前取消，避免排队中的 snapTo
                        // 把刚启动的吸附动画取消掉，抽屉卡在半开。
                        var dragJob: Job? = null
                        // 落点与宿主状态一致时自己吸附；不一致时交给宿主，
                        // 由 LaunchedEffect(open) 做唯一一次动画。
                        val settle: (Boolean) -> Unit = { targetOpen ->
                            when {
                                targetOpen == open ->
                                    scope.launch { progress.animateTo(if (targetOpen) 1f else 0f, DrawerSpring) }
                                targetOpen -> onOpen()
                                else -> onClose()
                            }
                        }
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            // IgnoreConsumed：转录区开始滚就会消费位移，拿消费后的增量就永远攒不够行程。
                            val delta = change.positionChangeIgnoreConsumed()
                            val dt = (change.uptimeMillis - lastTime).coerceAtLeast(1L)
                            // 速度取滑动平均，单帧噪声不能左右裁决。
                            velX = velX * 0.5f + delta.x / dt * 1000f * 0.5f
                            velY = velY * 0.5f + delta.y / dt * 1000f * 0.5f
                            lastTime = change.uptimeMillis
                            totalX += delta.x
                            totalY += delta.y
                            if (claim == DrawerDragClaim.Pending) {
                                claim = drawerDragClaim(
                                    totalX = totalX,
                                    totalY = totalY,
                                    velocityX = velX,
                                    velocityY = velY,
                                    slopPx = slopPx,
                                    opening = opening,
                                    childOwnsHorizontal = exclusion.active,
                                )
                            }
                            if (claim == DrawerDragClaim.Claimed) {
                                change.consume()
                                visual = (visual + pushDrawerDragDelta(delta.x, widthPx)).coerceIn(0f, 1f)
                                dragJob?.cancel()
                                dragJob = scope.launch { progress.snapTo(visual) }
                            }
                            if (!change.pressed) {
                                dragJob?.cancel()
                                if (claim != DrawerDragClaim.Claimed) {
                                    if (shouldCloseOnTap(down.position.x, widthPx, visual, startedOpen)) {
                                        settle(false)
                                    }
                                } else {
                                    settle(drawerSettle(visual, velX, startedOpen))
                                }
                                break
                            }
                        }
                    }
                },
        ) {
            // 下层：抽屉背景铺满；内容列缩进到 65%，右侧 35% 被会话卡盖住后不会藏控件。
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(PUSH_DRAWER_FRACTION)) {
                    drawerContent()
                }
            }
            // 上层：页面整块右移 65%。只用**布局位移**（`offset`），不套 graphicsLayer：
            // 真机上带裁剪 / 投影的层会把页面内容一起吃掉，只剩一层空白在滑。
            // 位移就是唯一的视觉变换，页面该画什么就画什么。
            Box(
                Modifier
                    .fillMaxSize()
                    .offset {
                        IntOffset(pushDrawerTranslationX(widthPx, progress.value).roundToInt(), 0)
                    },
            ) {
                // 页面里的横向滚动面（Markdown 表格）共用同一块登记表：
                // 它按下时先登记，抽屉就不再抢这条手势。
                CompositionLocalProvider(LocalHorizontalPanExclusion provides exclusion) {
                    content()
                }
            }
        }
        // 放在内容之后：后注册者优先，抽屉展开时返回键先收抽屉，
        // 而不是落到会话页自己的返回处理上。入口集中在 PanelBack.kt（PanelBackTest 守）。
        rememberPanelBackToggle(open, onClose)
    }
}
