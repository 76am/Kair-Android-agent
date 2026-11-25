package dev.kair.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kair.app.R
import dev.kair.app.ui.screens.chat.DrawerProjectList
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 项目段空态（U116 无项目页真机反馈）：0 个项目时抽屉的项目列表曾是一整块空白，
 * 用户读成「新建项目的页面挂了」。判据：列表必须出现创建引导，不能只剩底色。
 */
@RunWith(AndroidJUnit4::class)
class DrawerProjectListTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun empty_projects_shows_create_hint() {
        rule.setKairContent(widthDp = 320, heightDp = 640) {
            DrawerProjectList(
                projects = emptyList(),
                sessionCounts = emptyMap(),
                currentId = null,
                onPick = {},
                onManage = {},
            )
        }
        val hint = InstrumentationRegistry.getInstrumentation()
            .targetContext.getString(R.string.projects_empty)
        rule.onNodeWithText(hint).assertIsDisplayed()
    }
}
