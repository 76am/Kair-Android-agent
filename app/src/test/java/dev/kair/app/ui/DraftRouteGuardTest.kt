package dev.kair.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 草稿建档 ↔ 返回键竞态的判据（U102 遗留 bug）：建档在 IO 线程异步完成，
 * 用户等不及按返回时条目已被 pop —— 此时**不能**再把路由换成真 id，否则
 * 「按返回」变成「进新会话」，还会误弹下面那页会话。
 *
 * 判据从「entry 生命周期是否 RESUMED」改为「草稿 entry 是否仍是当前条目」：
 * 进入导航过渡时 lifecycle 可能还没到 RESUMED，一次性 effect 被拦下后不会重试，
 * 路由就永久停在 `new`（U110 修的就是这条）。
 */
class DraftRouteGuardTest {

    @Test
    fun current_draft_entry_still_switches_to_real_id() {
        assertTrue(shouldReplaceDraftRoute(currentEntryId = "entry-new", draftEntryId = "entry-new"))
    }

    @Test
    fun popped_or_replaced_draft_does_not_switch() {
        // 返回键已把草稿 pop：当前条目变成下面那页，两者 id 不同。
        assertFalse(shouldReplaceDraftRoute(currentEntryId = "entry-home", draftEntryId = "entry-new"))
        // 还没有当前条目（导航尚未落栈）同样不换。
        assertFalse(shouldReplaceDraftRoute(currentEntryId = null, draftEntryId = "entry-new"))
    }
}
