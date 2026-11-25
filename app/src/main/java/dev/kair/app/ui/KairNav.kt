package dev.kair.app.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.kair.app.app.AppContainer
import dev.kair.app.core.browser.BROWSER_PANEL_ID
import dev.kair.app.core.storage.Project
import dev.kair.app.core.storage.SessionMeta
import dev.kair.app.ui.browser.AndroidBrowserController
import dev.kair.app.ui.browser.BrowserHost
import dev.kair.app.ui.browser.BrowserOverlayHost
import dev.kair.app.ui.browser.BrowserPanelHost
import dev.kair.app.ui.components.FocusSink
import dev.kair.app.ui.components.LocalFocusSink
import dev.kair.app.ui.components.parkFocus
import dev.kair.app.ui.screens.PanelHostKind
import dev.kair.app.ui.screens.PanelHostScreen
import dev.kair.app.ui.screens.PanelOverlay
import dev.kair.app.ui.screens.PluginsScreen
import dev.kair.app.ui.screens.SessionDrawerHost
import dev.kair.app.ui.screens.chat.ChatScreen
import dev.kair.app.ui.screens.chat.LocalBrowserPanelOpen
import dev.kair.app.ui.screens.noproject.NoProjectRoute
import dev.kair.app.ui.screens.panelHostKind
import dev.kair.app.ui.screens.settings.SettingsScreen

object Routes {
    /** 一个项目都没有时的唯一落点（U116 删旧首页后新增）。 */
    const val NO_PROJECT = "no-project"
    const val SETTINGS = "settings"
    const val PLUGINS = "plugins"

    /**
     * 草稿会话占位 id（U102）：路由里的 sessionId 用它表示「会话还没建档」——
     * 首条消息在草稿页建档后，经 [ChatScreen] 的 onSwitchSession 原位换成真 id。
     */
    const val DRAFT_SESSION_ID = "new"

    fun chat(projectId: String, sessionId: String) = "chat/$projectId/$sessionId"

    /** 草稿路由：0 会话项目与抽屉「新建会话」都落到这里。 */
    fun chatDraft(projectId: String) = "chat/$projectId/$DRAFT_SESSION_ID"

    fun panel(panelId: String) = "panel/${Uri.encode(panelId)}"
}

@Composable
fun KairNavHost(container: AppContainer) {
    val nav = rememberNavController()
    // 统一返回入口：栈里只剩根目的地时不可再弹。裸 popBackStack() 不设防，快速连点
    // 返回会连删当前页与根页，回退栈被弹空后 NavHost 无目的地可渲染 → 白屏卡死。
    // 根页可能是会话 / 草稿 / 无项目页三种，不能再按某一个路由名判断。
    val navigateBack: () -> Unit = {
        if (nav.previousBackStackEntry != null) nav.popBackStack()
    }
    // U104：收焦点改「停进焦点池」而不是清空（清空会连 View 层焦点一起丢，招来框架的
    // 兜底抢焦，见 LocalFocusSink 注释）。池子挂在 NavHost 外层，跨屏存活。
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusSink = remember { FocusRequester() }
    val leaveInput: () -> Unit = {
        keyboard?.hide()
        parkFocus(focusSink, focusManager)
    }
    // 启动先停进池子：冷启动时窗口获焦同样会触发兜底请求，先把「有焦点」这件事做实，
    // 顺带保证首屏不会自动弹输入法。
    LaunchedEffect(Unit) { parkFocus(focusSink, focusManager) }
    val goSettings: () -> Unit = { leaveInput(); nav.navigate(Routes.SETTINGS) }
    val goPlugins: () -> Unit = { leaveInput(); nav.navigate(Routes.PLUGINS) }
    // 删除后的落点：宿主按当前路由参数判断，见 [leaveDeletedSession] / [leaveDeletedProject]。
    val onSessionDeleted: (Project, String) -> Unit = { project, deletedId ->
        leaveDeletedSession(nav, project, deletedId)
    }
    val onProjectDeleted: (Project) -> Unit = { deleted ->
        leaveDeletedProject(nav, container, deleted)
    }

    val startDestination = remember { initialRoute(container) }

    // 浏览器面板的常驻宿主提到根级：设置页 / 插件独立路由也能在截图时临时滑出它，
    // 不再随 chat 路由卸载。会话内的入口仍走 LocalBrowserPanelOpen，写同一份状态。
    var browserOpen by remember { mutableStateOf(false) }
    var browserFullscreen by remember { mutableStateOf(false) }
    val browserController = BrowserHost.controller(LocalContext.current.applicationContext)
    val openBrowser: () -> Unit = {
        browserFullscreen = false
        browserOpen = true
    }

    CompositionLocalProvider(LocalFocusSink provides focusSink, LocalBrowserPanelOpen provides openBrowser) {
        Box(Modifier.fillMaxSize()) {
            NavHost(navController = nav, startDestination = startDestination) {
                // 无项目页（U116）：旧首页删除后一个项目都没有时的唯一落点。
                // 它同样带 ☰ 推屏抽屉，建项目 / 进会话都原位替换本页。
                composable(Routes.NO_PROJECT) { entry ->
                    ActiveRoute(entry) {
                        NoProjectRoute(
                            container = container,
                            nav = nav,
                            entry = entry,
                            leaveInput = leaveInput,
                            onOpenSettings = goSettings,
                            onOpenPlugins = goPlugins,
                        )
                    }
                }

                composable(
                    route = "chat/{projectId}/{sessionId}",
                    arguments = listOf(
                        navArgument("projectId") { type = NavType.StringType },
                        navArgument("sessionId") { type = NavType.StringType },
                    ),
                ) { entry ->
                    val projectId = entry.arguments?.getString("projectId").orEmpty()
                    val sessionId = entry.arguments?.getString("sessionId").orEmpty()
                    // 抽屉里的项目 / 会话切换一律**原位替换**（U107 / U116）：压栈会让返回键
                    // 倒着走一遍进过的项目与会话；0 会话项目也必须换掉当前页，不能留在原会话。
                    val goDraft: (Project) -> Unit = { project ->
                        leaveInput()
                        nav.replaceChatRoute(Routes.chatDraft(project.id))
                    }
                    val onOpenSession: (Project, SessionMeta) -> Unit = { project, session ->
                        leaveInput()
                        nav.replaceChatRoute(Routes.chat(project.id, session.id))
                    }
                    // 会话内打开面板的两条路（U93）：工具型面板走**覆盖层**——会话留在下面不卸载，
                    // 收起即回原位，不再经过「返回列表 → 进会话 → 展开」；沉浸面板（图形桌面）
                    // 仍走独立路由，它要整块屏幕，塞进 74% 的 sheet 只会缩水。
                    var overlayPanel by remember { mutableStateOf<String?>(null) }
                    // 离开会话（含切会话 / 进设置）时收起根级面板；截图期间例外 ——
                    // 收起会让 PixelCopy 抓空，抓完由截图宿主自己恢复。
                    DisposableEffect(entry, browserController) {
                        onDispose {
                            if (!browserController.state.value.capturing) {
                                browserOpen = false
                                browserFullscreen = false
                            }
                        }
                    }
                    ActiveRoute(entry) {
                        SessionDrawerHost(
                            container = container,
                            currentSessionId = sessionId.takeUnless { it == Routes.DRAFT_SESSION_ID }.orEmpty(),
                            onBeforeOpenDrawer = leaveInput,
                            onOpenSession = onOpenSession,
                            onNewSession = goDraft,
                            onProjectCreated = goDraft,
                            onOpenSettings = goSettings,
                            onOpenPlugins = goPlugins,
                            onSessionDeleted = onSessionDeleted,
                            onProjectDeleted = onProjectDeleted,
                        ) { openDrawer ->
                            Box(Modifier.fillMaxSize()) {
                                CompositionLocalProvider(
                                    LocalBrowserPanelOpen provides {
                                        browserFullscreen = false
                                        browserOpen = true
                                    },
                                ) {
                                    ChatScreen(
                                        container = container,
                                        projectId = projectId,
                                        sessionId = sessionId,
                                        // 草稿建档完成 → 原位换成真 id（竞态细则见 switchDraftToReal）。
                                        onSwitchSession = { realId -> switchDraftToReal(nav, projectId, entry, realId) },
                                        onOpenSession = onOpenSession,
                                        onNewSession = goDraft,
                                        onOpenDrawer = openDrawer,
                                        onOpenPanel = { id ->
                                            if (id == BROWSER_PANEL_ID) {
                                                // 浏览器不吃 ModalBottomSheet：WebView 要跨「关闭」存活，
                                                // 由常驻宿主平移出入，入口仍走同一个 panel id。
                                                overlayPanel = null
                                                browserFullscreen = false
                                                browserOpen = true
                                            } else {
                                                // 宿主选择抽成纯函数（panelHostKind），instrumented 测试可直接断言
                                                // 「沉浸 → 全屏、工具型 → 覆盖层」，不必起一个真实会话。
                                                val chrome = container.plugins.panels().firstOrNull { it.id == id }?.chrome
                                                when (panelHostKind(chrome)) {
                                                    PanelHostKind.FullScreen -> nav.navigate(Routes.panel(id))
                                                    PanelHostKind.Overlay -> overlayPanel = id
                                                }
                                            }
                                        },
                                        onOpenSettings = goSettings,
                                        onOpenPlugins = goPlugins,
                                    )
                                }
                                overlayPanel?.let { id ->
                                    // 返回键 / 下滑 / 点遮罩都由 ModalBottomSheet 收敛到 onClose。
                                    // 「全屏」= 换宿主：收起覆盖层、进独立路由（U100）。面板会重建，
                                    // 插件自己 remember 的展开状态回到初始 —— 这是设计好的，不是 bug。
                                    PanelOverlay(
                                        container = container,
                                        panelId = id,
                                        onClose = { overlayPanel = null },
                                        onFullscreen = {
                                            overlayPanel = null
                                            nav.navigate(Routes.panel(id))
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                composable(Routes.SETTINGS) { entry ->
                    ActiveRoute(entry) {
                        SettingsScreen(container = container, onBack = navigateBack, onOpenPlugins = goPlugins)
                    }
                }

                composable(Routes.PLUGINS) { entry ->
                    ActiveRoute(entry) {
                        PluginsScreen(
                            container = container,
                            onBack = navigateBack,
                            onOpenPanel = { nav.navigate(Routes.panel(it)) },
                        )
                    }
                }

                composable(
                    route = "panel/{panelId}",
                    arguments = listOf(navArgument("panelId") { type = NavType.StringType }),
                ) { entry ->
                    val panelId = entry.arguments?.getString("panelId").orEmpty()
                    ActiveRoute(entry) {
                        PanelHostScreen(container = container, panelId = panelId, onBack = navigateBack)
                    }
                }
            }
            // 根级浏览器宿主：任何路由下都在组合里——设置页 / 插件独立路由截图时也能滑出面板。
            BrowserRootLayers(
                controller = browserController,
                open = browserOpen,
                fullscreen = browserFullscreen,
                onVisible = { browserOpen = it },
                onFullscreen = { browserFullscreen = it },
            )
            FocusSink(focusSink)
        }
    }
}

/**
 * 冷启动直达（U102 / U116）：上次项目有会话 → 最近一条；无会话 → 新会话页；
 * 上次项目已失效 → 回落第一个项目；一个项目都没有 → 无项目页。
 * 不再经过旧首页（项目下拉 + 迷你输入行），三条路都落在新抽屉外壳里。
 * 这里的索引读是 JSON 小文件，与原先首页直读同成本，可接受。
 */
private fun initialRoute(container: AppContainer): String {
    val projects = container.storage.listProjects()
    val project = projects.firstOrNull { it.id == container.settings.lastProjectId }
        ?: projects.firstOrNull()
    val latest = project?.let { p -> container.storage.listSessions(p.id).maxByOrNull { it.updatedAt } }
    return when {
        project == null -> Routes.NO_PROJECT
        latest != null -> Routes.chat(project.id, latest.id)
        else -> Routes.chatDraft(project.id)
    }
}

/**
 * 草稿建档完成时，是否该把路由换成真 id。判据是「草稿条目是否仍是当前条目」。
 *
 * 不能按 lifecycle 状态判：草稿是导航过渡中进入组合的，此时 entry 可能还没到 RESUMED；
 * 一次性 `LaunchedEffect` 被这道守卫拦下后不会重试，路由就永久停在 `new`
 * —— 后续自动命名写到不存在的 id、删除当前会话时对不上真 id，都是这条的后遗症。
 * 按 entry id 比较没有时序问题：被返回键 pop 掉的条目也不再是 current，语义不变。
 */
internal fun shouldReplaceDraftRoute(currentEntryId: String?, draftEntryId: String): Boolean =
    currentEntryId == draftEntryId

/**
 * 草稿 → 真 id 的原位替换：popUpTo 用**这条草稿自己的目的地 id**（inclusive），
 * 栈深不变；进程被杀后恢复直接落在真路由上，不会再建一档。
 *
 * 返回键竞态修复：建档在 IO 线程异步完成，用户等不及按返回时草稿已被 pop、
 * 但退场动画里还在组合 —— 不判「仍是当前条目」就会把用户拽回刚建的空会话；不按 id
 * 而按路由模板 popUpTo，则会在草稿已不在栈时误弹**下面那页会话**（模板匹配
 * 栈顶 chat 条目）。两道都要。
 */
private fun switchDraftToReal(nav: NavHostController, projectId: String, draft: NavBackStackEntry, realId: String) {
    if (!shouldReplaceDraftRoute(nav.currentBackStackEntry?.id, draft.id)) return
    // `popUpTo(String)` 收的是**路由**，不是 NavBackStackEntry.id；传 entry id 永远匹配不上，
    // 草稿条目会留在栈里（返回时又回到 `new` 再建一条会话）。按目的地 id 出栈才真的替换。
    val chatDestId = draft.destination.id
    nav.navigate("chat/$projectId/$realId") {
        popUpTo(chatDestId) { inclusive = true }
    }
}

/**
 * 当前会话被删除后，原位替换成另一条会话 / 新草稿（U105）。
 *
 * 不能按 `"chat/{projectId}/{sessionId}"` 字符串出栈：带参数的目的地匹配不可靠，
 * `popUpTo` 会静默失效；再叠加 `launchSingleTop`，导航会复用当前条目只换参数 ——
 * 会话页状态不重建，用户看到的就是「删了还停留在被删的会话里」。
 * 这里改为按**当前条目的目的地 id** 出栈，并关掉 singleTop，强制新条目、新状态。
 */
private fun NavHostController.replaceChatRoute(route: String) {
    val chatDestId = currentBackStackEntry?.destination?.id
    navigate(route) {
        if (chatDestId != null) popUpTo(chatDestId) { inclusive = true }
        launchSingleTop = false
    }
}

/**
 * 删的是当前会话：原位换回**空会话界面**（U110）。
 *
 * 不再挑「同项目最近一条」：删除是用户明确的清场动作，落到另一条旧会话会让界面
 * 看起来没删干净；也不在草稿路由上顺手建档 —— 空会话直到下一条消息才落库。
 *
 * 「是不是当前这条」由宿主按**当前路由参数**判定，而不是抽屉里的 currentSessionId：
 * 抽屉是用户在会话中途拉开的，参数可能已经落后（草稿换真 id 等），用旧值比较会漏跳，
 * 表现就是「删了还留在已删除的会话里，切到别的会话才生效」。
 */
private fun leaveDeletedSession(
    nav: NavHostController,
    project: Project,
    deletedId: String,
) {
    if (nav.currentBackStackEntry?.arguments?.getString("sessionId") != deletedId) return
    nav.replaceChatRoute(Routes.chatDraft(project.id))
}

/**
 * 删掉的是当前项目：离开这条路由，落到剩余项目的最近会话（没有会话就落草稿），
 * 一个项目都不剩才回无项目页（U116）。不能只让抽屉刷新：当前 ChatScreen 拿的还是
 * 已删项目的 id，继续发消息会往幽灵项目里写。
 */
private fun leaveDeletedProject(nav: NavHostController, container: AppContainer, deleted: Project) {
    if (nav.currentBackStackEntry?.arguments?.getString("projectId") != deleted.id) return
    val next = container.storage.listProjects().firstOrNull()
    if (next == null) {
        val chatDestId = nav.currentBackStackEntry?.destination?.id
        nav.navigate(Routes.NO_PROJECT) {
            if (chatDestId != null) popUpTo(chatDestId) { inclusive = true }
        }
        return
    }
    container.settings.lastProjectId = next.id
    val latest = container.storage.listSessions(next.id).maxByOrNull { it.updatedAt }
    nav.replaceChatRoute(latest?.let { Routes.chat(next.id, it.id) } ?: Routes.chatDraft(next.id))
}

/**
 * 转场期间 NavHost 仍会保留旧页面并让它参与命中测试：页面切换的动画里快速点击，
 * 会命中已经看不见的上一页控件。这里在目的地不是 RESUMED 时于 Initial pass 吞掉所有
 * 指针事件（含子控件），动画结束、生命周期回到 RESUMED 后自动恢复交互。
 */
@Composable
private fun ActiveRoute(entry: NavBackStackEntry, content: @Composable () -> Unit) {
    val state by entry.lifecycle.currentStateFlow.collectAsState()
    Box(
        Modifier
            .fillMaxSize()
            .then(
                if (state == Lifecycle.State.RESUMED) Modifier
                else Modifier.pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                        }
                    }
                }
            ),
    ) {
        content()
    }
}

/** 根级浏览器宿主：前台面板 + 后台悬浮窗；任何路由下都在组合里。 */
@Composable
private fun BrowserRootLayers(
    controller: AndroidBrowserController,
    open: Boolean,
    fullscreen: Boolean,
    onVisible: (Boolean) -> Unit,
    onFullscreen: (Boolean) -> Unit,
) {
    BrowserPanelLayer(
        visible = open,
        fullscreen = fullscreen,
        onVisible = onVisible,
        onFullscreen = onFullscreen,
    )
    BrowserOverlayHost(controller)
}

/**
 * 浏览器面板这一层的状态转发：把 visible / fullscreen 与三个回调收成一组，
 * 避免 `KairNavHost` 为这块再涨几行（方法长度守卫 P5-2）。
 */
@Composable
private fun BrowserPanelLayer(
    visible: Boolean,
    fullscreen: Boolean,
    onVisible: (Boolean) -> Unit,
    onFullscreen: (Boolean) -> Unit,
) {
    BrowserPanelHost(
        visible = visible,
        fullscreen = fullscreen,
        onClose = {
            onVisible(false)
            onFullscreen(false)
        },
        onToggleFullscreen = { onFullscreen(!fullscreen) },
    )
}
