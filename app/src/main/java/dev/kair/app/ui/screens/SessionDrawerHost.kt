package dev.kair.app.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.kair.app.app.AppContainer
import dev.kair.app.core.storage.Project
import dev.kair.app.core.storage.SessionMeta
import dev.kair.app.ui.components.PushDrawer
import dev.kair.app.ui.screens.chat.SessionDrawerContent

/**
 * 会话壳（U116）：把推屏抽屉与三种落点（会话页 / 新会话页 / 无项目页）的接线收在一处。
 *
 * 抽屉状态归宿主所有：拉开的入口（☰ / 右滑）只把 [open] 置真；导航回调统一先关抽屉，
 * 再交给路由层 —— 点 0 会话项目、新建项目后都不能让抽屉挂在新页面上。
 */
@Composable
internal fun SessionDrawerHost(
    container: AppContainer,
    currentSessionId: String,
    onBeforeOpenDrawer: () -> Unit,
    onOpenSession: (Project, SessionMeta) -> Unit,
    onNewSession: (Project) -> Unit,
    onProjectCreated: (Project) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPlugins: () -> Unit,
    onSessionDeleted: (Project, String) -> Unit,
    onProjectDeleted: (Project) -> Unit,
    content: @Composable (openDrawer: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    // 拉开抽屉也算「离开输入」：安卓 8 上只清焦点键盘会留（U103）。☰ 与右滑共用。
    val openDrawer: () -> Unit = {
        onBeforeOpenDrawer()
        open = true
    }
    LaunchedEffect(open) {
        if (open) refresh++
    }
    PushDrawer(
        open = open,
        onOpen = openDrawer,
        onClose = { open = false },
        drawerContent = {
            SessionDrawerContent(
                container = container,
                currentSessionId = currentSessionId,
                refresh = refresh,
                onRefresh = { refresh++ },
                onOpenSession = { project, session ->
                    open = false
                    onOpenSession(project, session)
                },
                onNewSession = { project ->
                    open = false
                    onNewSession(project)
                },
                onProjectCreated = { project ->
                    open = false
                    onProjectCreated(project)
                },
                onOpenSettings = {
                    open = false
                    onOpenSettings()
                },
                onOpenPlugins = {
                    open = false
                    onOpenPlugins()
                },
                onSessionDeleted = onSessionDeleted,
                onProjectDeleted = onProjectDeleted,
            )
        },
    ) { content(openDrawer) }
}
