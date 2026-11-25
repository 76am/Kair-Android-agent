package dev.kair.app.ui.screens.chat

import dev.kair.app.core.model.ChatMessage

/** 一次布局快照里的单个可见项（只取触发键需要的两列，避免把 Compose 类型带进单测）。 */
internal data class VisibleItem(val index: Int, val offset: Int, val size: Int)

/** 贴底触发键：条数 / 视口高度 / 键盘高度 / 末个可见项的位置与底边。 */
internal data class FollowTarget(
    val count: Int,
    val viewportHeight: Int,
    val imeBottom: Int,
    val lastVisibleIndex: Int?,
    val lastVisibleBottom: Int?,
)

/**
 * 末条消息实例的**引用相等**包装，用作自动跟随的触发键。
 *
 * 流式输出每个 token 都会替换末条消息实例。用引用相等比较，避免在每个 token 上深比较
 * 整段正文（`ChatMessage.equals` 会遍历所有 parts）。同帧贴底由 `AutoScrollToLatest`
 * 的 `SideEffect` 负责（组合应用后、布局前）；这里保留实例键，是让布局兜底路径在只有
 * 正文变化时也有信号。
 */
internal class MessageIdentity(val message: ChatMessage?) {
    override fun equals(other: Any?): Boolean =
        other is MessageIdentity && other.message === message

    override fun hashCode(): Int = System.identityHashCode(message)

    override fun toString(): String = "MessageIdentity(${message?.id})"
}

/**
 * 由一次布局快照计算触发键。
 *
 * 取**末个可见项**而不是「末条消息的 size」：末条不在视口内时后者恒为 null，
 * 末条再长也发不出信号（D2 / D4 的洞）。换成 index + 底边后，「条数变化」
 * 「视口变小（键盘弹出）」「可见区随布局推进」三类都会换键；布局一稳定就不再发，
 * 由 `distinctUntilChanged` 去重。
 */
internal fun followTargetOf(
    count: Int,
    viewportHeight: Int,
    imeBottom: Int,
    visibleItems: List<VisibleItem>,
): FollowTarget {
    val last = visibleItems.lastOrNull()
    return FollowTarget(
        count = count,
        viewportHeight = viewportHeight,
        imeBottom = imeBottom,
        lastVisibleIndex = last?.index,
        lastVisibleBottom = last?.let { it.offset + it.size },
    )
}

/**
 * 自动跟随的完整触发键：**布局键 + 末条消息实例**。
 *
 * 两半各司其职：布局键覆盖「条数变了 / 视口或键盘变了 / 可见区随布局推进」，实例键覆盖
 * 「只有正文在长、布局产物还没更新」的兜底信号（同帧贴底见 `AutoScrollToLatest`）。
 *
 * 抽成函数而不是在 `snapshotFlow` 里就地拼 `target to MessageIdentity(...)`：
 * 那一段在 JVM 单测里驱动不了，就地拼的结果是**测试只能自己再拼一份同样的键**去断言
 * —— 生产侧哪天把实例那半删了，测试照样绿。抽出来之后 [ChatScrollTargetTest] 断言的
 * 是生产真正用的那个键。
 */
internal fun followKey(target: FollowTarget, lastMessage: ChatMessage?): Pair<FollowTarget, MessageIdentity> =
    target to MessageIdentity(lastMessage)

/**
 * D4 侦察判据：补滚后末个可见项底边仍超出视口时，返回要打的一行现场；否则返回 null。
 *
 * **只用于记录，不参与任何行为修正** —— D4 的触发条件未知，盲改只会制造新问题。
 * 抽成纯函数是为了让「什么时候该记」也能被单测钉住。
 */
internal fun d4ReconLine(
    target: FollowTarget,
    atBottom: Boolean,
    actualLastBottom: Int?,
    actualViewportHeight: Int,
): String? {
    if (actualLastBottom == null || actualLastBottom <= actualViewportHeight) return null
    return "D4 follow=true but not at bottom: target=$target atBottom=$atBottom " +
        "actualLastBottom=$actualLastBottom actualViewport=$actualViewportHeight"
}
