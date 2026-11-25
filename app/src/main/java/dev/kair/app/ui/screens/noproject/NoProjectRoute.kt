package dev.kair.app.ui.screens.noproject

import androidx.compose.runtime.Composable
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import dev.kair.app.app.AppContainer
import dev.kair.app.core.storage.Project
import dev.kair.app.ui.Routes
import dev.kair.app.ui.screens.SessionDrawerHost

/**
 * 无项目路由（U116）：把「无项目页 + 抽屉」接到导航层。
 *
 * 本页是根目的地：建项目 / 进会话都必须**原位替换**本页，不能往栈里再压一层 ——
 * 否则返回键会退回一个已经过时的「无项目」页。
 */
@Composable
internal fun NoProjectRoute(
    container: AppContainer,
    nav: NavHostController,
    entry: NavBackStackEntry,
    leaveInput: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPlugins: () -> Unit,
) {
    val goDraft: (Project) -> Unit = { project ->
        leaveInput()
        nav.navigate(Routes.chatDraft(project.id)) {
            popUpTo(entry.destination.id) { inclusive = true }
        }
    }
    SessionDrawerHost(
        container = container,
        currentSessionId = "",
        onBeforeOpenDrawer = leaveInput,
        onOpenSession = { project, session ->
            leaveInput()
            nav.navigate(Routes.chat(project.id, session.id)) {
                popUpTo(entry.destination.id) { inclusive = true }
            }
        },
        onNewSession = goDraft,
        onProjectCreated = goDraft,
        onOpenSettings = onOpenSettings,
        onOpenPlugins = onOpenPlugins,
        onSessionDeleted = { _, _ -> },
        onProjectDeleted = {},
    ) { openDrawer ->
        NoProjectScreen(
            container = container,
            onOpenDrawer = openDrawer,
            onProjectCreated = goDraft,
        )
    }
}
