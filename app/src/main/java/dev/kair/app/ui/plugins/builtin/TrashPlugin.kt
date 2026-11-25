package dev.kair.app.ui.plugins.builtin

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kair.app.R
import dev.kair.app.app.AppContainer
import dev.kair.app.core.plugin.ChoiceOption
import dev.kair.app.core.plugin.PanelContribution
import dev.kair.app.core.plugin.Plugin
import dev.kair.app.core.plugin.PluginContext
import dev.kair.app.core.plugin.PluginSettingsStore
import dev.kair.app.core.plugin.SettingField
import dev.kair.app.core.plugin.SettingsPanelSpec
import dev.kair.app.core.tools.LocalFileBackend
import dev.kair.app.ui.components.DestructiveTextButton
import dev.kair.app.ui.components.FileEditorBody
import dev.kair.app.ui.components.humanSize
import dev.kair.app.ui.icons.PluginGlyph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 回收站插件：全局回收站（`<storageRoot>/trash`，容器里 bind 成 `/trash`）的独立面板 +
 * 按时间 / 条数 / 体积的自动清理 + 一键清空。
 *
 * 做成插件而不是往文件管理器里再加一段，是「能力皆可插拔」（`AGENTS.md` §1 第 1 条）：
 * 回收站与项目无关（所有工作区共享一份），跟着一个面板走比跟着文件管理器走更独立。
 * 文件管理器原来那颗「跳转回收站」按钮已删 —— 那是同一个入口的两种存在方式，不是两个能力。
 *
 * 三条上限**各自独立触发**（0 = 该条不启用），任一超限就从最旧的开始删到达标；判据本体是
 * `TrashMaintenance.kt` 里的纯函数 [selectTrashPurge]，删的动作只有 [purgeTrash] 一处。
 *
 * **触发时机只有两条**：应用存活期每 6 小时一轮 + 每次启动补扫一次。全仓没有
 * WorkManager / AlarmManager / JobScheduler，进程被系统回收后定时就没了 —— 这句写进了
 * 用户可见的开关描述里，不假装可靠（`docs/archive/插件接口扩展提案.md` §3.3 的要求）。
 */
class TrashPlugin(private val container: AppContainer) : Plugin {

    override val id = ID

    // 文案的事实源在 strings.xml：接口要求的字面量位留空，宿主渲染时优先取 res（见 PluginDisplayText）。
    override val name = ""
    override val description = ""
    override val nameRes = R.string.trash_plugin_name
    override val descriptionRes = R.string.trash_plugin_desc

    override fun activate(ctx: PluginContext) {
        val root = container.trashDir
        val backend = trashBackend(root)

        ctx.panel(
            PanelContribution(
                id = "panels.trash",
                title = "",
                titleRes = R.string.trash_plugin_name,
                icon = PluginGlyph.Trash,
                order = 35,
                content = { TrashPanel(container) },
            ),
        )
        ctx.settingsPanel(
            trashSettingsSpec {
                // onClick 不是 suspend：真正删除丢进插件作用域（卸载即取消）。这里不弹提示 ——
                // SettingField.Action 没有回传结果的通道，结果读数在回收站面板里看。
                ctx.scope.launch { emptyTrash(root, backend) }
            },
        )

        // 启动即补扫一轮，之后每 6 小时一轮；卸载时随 ctx.scope 一起取消。
        ctx.scope.launch {
            while (isActive) {
                // 兜住整轮异常：抛出会杀掉这个唯一的轮询协程，插件看起来仍「已启用」，
                // 但此后永远不会再清理（同一形状的坑见 SchedulerPlugin.fire()）。下一轮重试即可。
                runCatching {
                    val limits = trashLimitsOf(ctx.settings)
                    // 总开关关着 = 三条都不启用：连盘都不必读（手动清空不受这条门控管）。
                    if (!limits.allOff) sweepTrash(root, limits, backend, System.currentTimeMillis())
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private companion object {
        const val ID = "dev.kair.plugin.trash"

        /** 轮询周期：阈值都是「天」级，更细的周期只多耗电（沿用定时任务插件的经验，不进设置）。 */
        const val POLL_INTERVAL_MS = 6L * 60 * 60 * 1000
    }
}

/**
 * 回收站用的文件后端：**只用应用身份**（`LocalFileBackend`），不借容器视图。
 *
 * 两个理由：
 * 1. 容器升级路径要靠「工作区内 → 相对路径」拼容器内命令，而回收站按设计在所有工作区之外，
 *    面板也没有一个可以当锚点的「当前工作区」（无会话时回收站照样要能清）。那条空转是
 *    `docs/tasks/2026-11-05-回收站自动清理与一键清空.md` §6 第 1 条的挂账，本批不修路径映射，
 *    而是靠 [purgeTrash] 的「删后复查残留」把它变成**如实报数**，不再报「已清空」。
 * 2. 一轮清理最多要删两千条：逐条升级容器就是逐条 spawn 进程，代价远超收益。
 *
 * 今天回收站能被应用删，靠的是容器命令以 `umask 000` 落权限（同文档 §6 第 2 条）——
 * 那是既有隐式依赖，不是本批新增的。
 */
private fun trashBackend(root: File): LocalFileBackend = LocalFileBackend(root.path)

/**
 * 存储里的三条上限 → 判据用的 [TrashLimits]。
 *
 * 保留时长是 Choice（落库为字符串），另两条是 Number（落库为整数）—— 读法不同但**默认值同一份**，
 * 所以这条映射单独一处：轮询与测试都走它，默认值走偏会在 `TrashMaintenanceTest` 当场红。
 *
 * **总开关关着就返回三条全 0**（[TrashLimits.allOff]）。门控放在这个唯一读取点、而不是在轮询
 * 循环里另读一句 `getBoolean`：默认值是「关」，而开关之外有两条自动删文件的路径（启动补扫 +
 * 6 小时轮询），漏读一次就是「用户以为没开，回收站里的东西却在被删」—— 本批最贵的一种错。
 * 手动清空**不受这里门控**（两个入口直接调 [emptyTrash]）：拍板是「关掉开关后不再自动清，手动清空仍可用」。
 */
internal fun trashLimitsOf(store: PluginSettingsStore): TrashLimits =
    if (!store.getBoolean(TRASH_KEY_ENABLED, false)) TrashLimits(0, 0, 0L)
    else trashLimits(
        maxAgeDays = store.getString(TRASH_KEY_MAX_AGE_DAYS, TRASH_DEFAULT_MAX_AGE_DAYS.toString())
            .toIntOrNull() ?: TRASH_DEFAULT_MAX_AGE_DAYS,
        maxItems = store.getInt(TRASH_KEY_MAX_ITEMS, TRASH_DEFAULT_MAX_ITEMS),
        maxSizeMb = store.getInt(TRASH_KEY_MAX_SIZE_MB, TRASH_DEFAULT_MAX_SIZE_MB),
    )

/** 阈值设置分区：开关 + 三条上限 + 一个「手动清空」Action。纯声明，插件接线与测试共用一份。 */
internal fun trashSettingsSpec(onEmptyAll: (PluginSettingsStore) -> Unit): SettingsPanelSpec {
    val emptyAction = SettingField.Action(
        key = TRASH_KEY_EMPTY_NOW,
        buttonLabel = "",
        onClick = onEmptyAll,
        labelRes = R.string.trash_action_label,
        descriptionRes = R.string.trash_action_desc,
    ).apply { buttonLabelRes = R.string.trash_action_button }

    return SettingsPanelSpec(
        id = "settings.trash",
        title = "",
        icon = PluginGlyph.Trash,
        order = 45,
        // 拍板第 3 项「面板内按钮 + 设置分区 Action 两处」= 双入口：本分区在「设置」页与
        // 插件详情页同时渲染（ContextPlugin 同族），两处读写同一份数据、共用同一个清空执行体。
        showInSettings = true,
        fields = listOf(
            SettingField.Toggle(
                key = TRASH_KEY_ENABLED,
                default = false,
                labelRes = R.string.trash_enabled_label,
                descriptionRes = R.string.trash_enabled_desc,
            ),
            SettingField.Choice(
                key = TRASH_KEY_MAX_AGE_DAYS,
                default = TRASH_DEFAULT_MAX_AGE_DAYS.toString(),
                labelRes = R.string.trash_age_label,
                descriptionRes = R.string.trash_age_desc,
                // 「不限制」是一档：三条既然各自独立触发，年龄这条也要能单独关掉（0 = 不启用）。
                options = listOf(
                    ageOption("0", R.string.trash_age_off),
                    ageOption("7", R.string.trash_age_7),
                    ageOption("15", R.string.trash_age_15),
                    ageOption("30", R.string.trash_age_30),
                ),
            ),
            SettingField.Number(
                key = TRASH_KEY_MAX_ITEMS,
                default = TRASH_DEFAULT_MAX_ITEMS,
                min = 0,
                max = TRASH_MAX_ITEMS_CEILING,
                labelRes = R.string.trash_items_label,
                descriptionRes = R.string.trash_items_desc,
            ),
            SettingField.Number(
                key = TRASH_KEY_MAX_SIZE_MB,
                default = TRASH_DEFAULT_MAX_SIZE_MB,
                min = 0,
                max = TRASH_MAX_SIZE_MB_CEILING,
                labelRes = R.string.trash_size_label,
                descriptionRes = R.string.trash_size_desc,
            ),
            emptyAction,
        ),
    ).apply {
        // 与面板标题、插件名同一份文案；SettingsPanelSpec.titleRes 只能在类体（U37 判据 2）。
        titleRes = R.string.trash_plugin_name
    }
}

/** 保留时长选项：`value` 落库，标签走资源（内置插件不在源码里写显示文案）。 */
private fun ageOption(value: String, res: Int): ChoiceOption =
    ChoiceOption(value, "").apply { labelRes = res }

@Composable
private fun TrashPanel(container: AppContainer) {
    val root = container.trashDir
    val backend = remember(root) { trashBackend(root) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var dir by rememberPanelBackPath(root.path, root.path)
    var openFile by remember(root) { mutableStateOf<String?>(null) }
    // 清空之后要让读数重拉；FileList 自己的列表刷新在它内部，这里只管面板这一层。
    var refresh by remember(root) { mutableStateOf(0) }
    var readout by remember(root) { mutableStateOf<Pair<Int, Long>?>(null) }
    var status by remember(root) { mutableStateOf<String?>(null) }
    var confirmEmpty by remember(root) { mutableStateOf(false) }

    // 「多少条 / 占多大」要递归求和，放 IO 上、先显示统计中：条目堆到几千条时这不是瞬间能算完的。
    LaunchedEffect(root, refresh) {
        readout = withContext(Dispatchers.IO) {
            val items = scanTrash(root, needSizes = true)
            items.size to items.sumOf { it.sizeBytes }
        }
    }

    // 上翻上限就是回收站自身：面板不该翻到 Kair 根去看别的目录（与文件管理器同一条策略）。
    val policy = remember(root) { FsPolicy(cap = root.path, manageable = listOf(root.path)) }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = readout?.let { stringResource(R.string.trash_summary, it.first, humanSize(it.second)) }
                    ?: stringResource(R.string.trash_counting),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            DestructiveTextButton(
                text = stringResource(R.string.trash_empty_all),
                onClick = { confirmEmpty = true },
            )
        }
        status?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }

        val file = openFile
        if (file != null) {
            FileEditorBody(
                backend = backend,
                absolutePath = file,
                label = file.removePrefix(root.path + "/"),
                modifier = Modifier.fillMaxSize(),
                onBack = { openFile = null },
            )
        } else {
            FileList(
                backend = backend,
                dir = dir,
                // 回收站不属于任何工作区，没有「回到工作区根」这回事；返回上级由宿主的面板导航给。
                homePath = null,
                policy = policy,
                onEnter = { dir = it },
                onOpenFile = { openFile = it },
                onDeleteSessionLog = { false },
                // 一键清空是**面板层**删的：`dir` 在根目录时不会变、backend 也不变，
                // 不把这里的 refresh 透下去，列表就会停在旧快照（摘要变了、条目还在）。
                refreshKey = refresh,
            )
        }
    }

    if (confirmEmpty) {
        ConfirmDialog(
            title = stringResource(R.string.trash_empty_confirm_title),
            message = stringResource(R.string.trash_empty_confirm_message, readout?.first ?: 0),
            onDismiss = { confirmEmpty = false },
            onConfirm = {
                confirmEmpty = false
                scope.launch {
                    status = describePurgeResult(context, emptyTrash(root, backend))
                    // 清的是整个回收站：正在看子目录时也回到顶层，否则列表会停在一个已消失的目录里。
                    dir = root.path
                    refresh++
                }
            },
        )
    }
}

/** 清理结果 → 一句如实的话：全删光 / 一条没删掉 / 部分残留，是三种不同提示，不合成「已清空」。 */
private fun describePurgeResult(context: Context, result: TrashPurgeResult): String = when {
    result.requested == 0 -> context.getString(R.string.trash_empty_nothing)
    result.deleted == 0 -> context.getString(R.string.trash_empty_failed, result.failed)
    result.fullyCleared -> context.getString(R.string.trash_empty_done, result.deleted)
    else -> context.getString(R.string.trash_empty_partial, result.deleted, result.failed)
}

internal const val TRASH_KEY_ENABLED = "trash.enabled"
internal const val TRASH_KEY_MAX_AGE_DAYS = "trash.max_age_days"
internal const val TRASH_KEY_MAX_ITEMS = "trash.max_items"
internal const val TRASH_KEY_MAX_SIZE_MB = "trash.max_size_mb"
internal const val TRASH_KEY_EMPTY_NOW = "trash.empty_now"

internal const val TRASH_DEFAULT_MAX_AGE_DAYS = 7
internal const val TRASH_DEFAULT_MAX_ITEMS = 2000
internal const val TRASH_DEFAULT_MAX_SIZE_MB = 2048

/** 输入框的区间上限（不是默认值）：`SettingField.Number` 只接受 9 位数字，这里留出够用的档位。 */
internal const val TRASH_MAX_ITEMS_CEILING = 100_000
internal const val TRASH_MAX_SIZE_MB_CEILING = 65_536
