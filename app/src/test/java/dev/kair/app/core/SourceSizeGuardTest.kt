package dev.kair.app.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 体量守卫（B8）：主源码单文件不得超过 500 行，存量 13 个进白名单且**只准减不准增**。
 *
 * 为什么要它：`docs/架构评估-2026-10.md` 的「先减后不减也算失败」说的就是这件事 ——
 * `AgentSession.kt` 1713 行、`AppContainer.kt` 1190 行不是历史包袱，是**正在变胖**。
 * 一次性把 15 个都改到达标不现实（那就是一上来 15 个红，没人会修），
 * 所以取「白名单 + 棘轮」：新文件超限即红，白名单文件多一行也红。
 *
 * 范围只含**主源码**（`app/src/main` 与 `plugin-api/src/main`）：
 * 测试文件的长度反映的是场景数量，不是拆分压力；把它们套进同一个阈值，
 * 只会诱导人为了变绿而拆测试（`AGENTS.md` §7 明令禁止的放宽断言形态）。
 *
 * 白名单每条都写「为什么超阈值」与「有没有拆分计划」—— 没有计划就如实写「无计划」，
 * 别留空（P0-2 步骤 3）。
 *
 * **目标口径（负责人 2026-11-02 定）**：判据是「**只准减、不设绝对数**」，不是原评估里的「降到 10 个以内」——
 * 13 条里 10 条写「无拆分计划」是**刻意的同居**（职责本就该在一个文件里），任何绝对数都会变成一条注定失败的判据。
 * 唯一保留的靶子是 `AppContainer.kt`（三刀落点见评估 §9 第 4 条与看板 §4）。
 * 所以读这个守卫的红：**永远是「这一批有没有让某个文件变胖」**，不是「离 10 还差几个」。
 */
class SourceSizeGuardTest {

    /**
     * 白名单：相对仓库根的路径 → **允许达到的行数**（登记时的实测值）。
     * 超过这个数即失败；低于它不报错，但请把数字改小 —— 棘轮只该往一个方向转。
     *
     * **已移出白名单**：`ui/screens/chat/MessageList.kt`（2026-10-30 聊天界面批次把自动跟随 /
     * D4 侦察整段搬到 `AutoScroll.kt`，该文件降到 486 行 < 500，条目数 15 → 14）；
     * `ui/plugins/builtin/WorkspaceFilesPlugin.kt`（2026-11-05 U38 把 `FileList`、行组件与三个对话框
     * 抽到 `WorkspaceFilesUi.kt`（回收站面板共用同一份），摘掉「跳转回收站」按钮并把面板名字下沉
     * `strings.xml` 后实测 196 行 < 500，条目数 14 → 13）；
     * `core/plugin/Plugins.kt`（2026-11-07 U40 / U41 批把 `PluginContext` 整类搬到同目录
     * `PluginContext.kt`（约 130 行，本身不超 500），实测 410 行 < 500，条目数 14 → 13）。
     * 移出不代表可以随便长回去：再超过 500 就是新条目，必须登记并写理由。
     * 2026-11-06 U48 终止原因显式携带把 `TurnRunner.kt`（488 → 545）推过 500，登记为新条目
     * （条目数 13 → 14，为什么 / 何时拆写在条目上）—— 就是这条规矩说的那种「长回去」。
     */
    private val oversized: Map<String, Int> = mapOf(
        // 内核主循环。P2-2 已给出搬家方案（docs/tasks/archive/P2-2-拆AgentSession.md），
        // 架构加固计划的目标是先 ≤1200 再 ≤800。
        // 2026-10-27 批次 3 由 1638 调大到 1664（+26，失败可见接线）—— 第 1 次因功能增长调大。
        // 规则：同一文件**第 2 次**调大前必须先有拆分计划，否则打回（见 docs/review/2026-10-27-failure-visibility.md §6）。
        // 2026-10-27 批次 4 由 1664 调大到 1713（+49，A1 状态机 + 转移表接线）—— **第 2 次**。
        // 合规理由：拆分计划已存在且紧随其后（4b / A5，`docs/tasks/archive/P2-2-拆AgentSession.md`）。
        // 2026-10-27 **批次 4b（A5）由 1713 下调到 1193（-520）** —— 上面那句「第 2 次调大必须带
        // 拆分计划」的承诺兑现了，棘轮真的往回转了。**这是这条规则第一次被真正检验**：
        // 如果 4b 只把文件挪个位置而没降下来，从这一刻起「带计划」就成了空话。
        // 下一步 ≤800 归 A5 的第二步（剩余体量集中在 runLoop / runCompaction）。
        // 2026-10-28 **由 1193 收到 1192（-1）**：P2-a 去掉 diagnostics 默认值后 KDoc 重写，实测。
        // 2026-10-30 F2 由 1192 调大到 1222（+30，每天一条日期 system note：时钟接缝 +
        // `notifyDateIfNeeded`）—— 第 3 次因功能增长调大；拆分计划仍在（A5 第二步 ≤800）。
        // 2026-10-31 A5 第二步兑现：**1222 → 781**（-441），承诺的 ≤800 达成。
        // 三块搬出：`TurnRunner`(422) / `CompactionRunner`(187) / `HistoryEdits`(108)，
        // 三个新文件都在 500 以下、**不进本白名单**（条目数仍是 14）。
        // 评审（P2）指出先记成 792 比实测多 11 行余量 —— 已收到实测 781（余量等于豁免）。
        // 2026-10-31 压缩时机修复由 781 收到 779（-2）：`offloadOlderImages` 从「落盘并
        // 返回 Boolean」改成「只返回内存候选」，KDoc 反而短了。**只跟着实测往下走。**
        // 2026-11-01 U18 由 779 调到 786（+7，第 4 次因功能增长调大）：`steer` 增加
        // systemNote 通道（后台通知按步投递，不再等回合结束）+ `inbound` 入站构造收口。
        // 拆分计划仍有效（A5 第二步 ≤800，当前 786 在目标内）；没有新增职责，只是通道。
        // 2026-11-01 U18 复核 P2 由 786 调到 792（+6，**第 5 次因功能增长调大**）：
        // `finishSerialWork` 的排空条件要从「只在停止收尾时」改成「停止或队列非空」——
        // 收尾广播窗口里落地的通知没人取就是三重代价（进不了请求 / 界面看不见 /
        // 会话永不判空闲被 LRU 跳过）。多的是解释这一行为什么必须在这儿，逻辑只有一行。
        // 2026-11-01 增量复核的三条 P3 由 792 调到 795（+3，**第 6 次**）：drain 前判
        // `isRunning`（「置空闲 → 读队列」不原子，中间插进来的 send 已自起一轮，再 drain
        // 就是两个回合）、`compactNow` 的重复排空删掉（§10.4 一个概念一个实现点）。
        // 2026-11-01 U23 由 795 下调到 790（-5）：图片落盘分支收进 `ImageDownscaler.store`。
        // 2026-11-01 U23 的 B 审修复再由 790 下调到 780（-10）：`attachImages` 整体移进
        // `ImageDownscaler.attach`，`append` 只剩一行接线；位图缩放移出内核（`app/app/BitmapImageScaler.kt`），
        // `core` 不再引 `android.graphics`（ArchitectureGuard 红的那条）。距 ≤800 触发线仍有 20 行余量。
        // 同日 U23 真机追因由 780 上调到 785（+5）：`imageLog` 构造参数 + 说明 —— 缩放的四条降级
        // 落点在设备上原本完全没有痕迹，「缩放没生效」与「缩放器崩了」长得一样。真实功能增长。
        // 2026-11-02 U30 由 785 上调到 788（+3）：`settingsProvider: () -> AgentSettings` 换成
        // `assemblyProvider: () -> AgentAssembly`，TurnExecutor 的依赖面多一个 `assembly` 入口
        // （1 行）连同它「为什么不能只给 settings」的 2 行注释。改的是**装配产物的走向**，
        // 实现没进来。
        // 2026-11-06 收尾卡死批由 788 下调到 786（-2）：`send()` 消息统一入 FOLLOW_UP 队列
        // 受托，Idle 直发不再把消息关进 `launchRun` 的 lambda（取消窗口消息静默消失的洞），
        // 注释随修法换写、净 -2。**只跟着实测往下走。**
        // 2026-11-06 U48 由 786 上调到 789（+3）：TurnExecutor.Host 注入 `diagnostics`
        // （空回合计数写入面）与 TurnRunner.Host 注入 `isSessionClosed`（关闭区分），各 1 行
        // 接线 + 共 1 行说明。逻辑本体在 TurnRunner / TurnExecutor / Diagnostics，这里只有接线。
        // 2026-11-07 U57 由 789 上调到 839（+50）：停止的硬上界与「被放弃轮次不许写历史」。
        // **账目本体已拆出去**（`core/agent/StopWatchdog.kt`，122 行，不进白名单），这里留下的
        // 是会话级接线：`stopWatchdog` 一个实例（8 行）、写历史唯一入口的 4 处闸门（`updateAssistant` /
        // `append` / `onPersist` / `restoreMessage`，含「为什么挡在写入面」的说明）、
        // `finishSerialWork` 与 `forceClose` 两段收口、`stopHardLimitMs` 构造参数（测试要能调小，
        // 生产唯一来源仍是 `StopHardLimitMs`）。
        // **已超出 A5 第二步的 ≤800 目标 19 行**：下一批动本文件的必须先拆，拆点按 A5 的顺序是
        // `runUser` 与 `compactRun` 两段（`docs/tasks/archive/P2-2-拆AgentSession.md`），
        // **不许再靠上调本条目通过**。
        // 2026-11-07 U58 由 839 下调到 819（-20，实测）：压缩依赖面收口到 `CompactionControl`
        // （`compactIfNeeded` / `runCompaction` / `effectiveCompactionSettings` / 死代码
        // `fixedPrefixTokens` 搬走），这里只剩 `compaction` 的接线与 `compactNow` 委托。
        // 2026-11-10 U42 由 819 下调到 806（-13，实测）：configProvider / assemblyProvider /
        // contextWindowProvider / toolFilter 四个构造参数并入一个 inputsProvider，接线净删。
        // 2026-11-12 U77 由 806 收到 805（-1，实测）：写入面整体搬进 `core/agent/HistoryWrites.kt`
        // （新增 / 覆盖 / 删除 / 修复 / 重写与它们的归属闸门），abort 的单指针改成活动集合、
        // 看门狗改收整份快照。**搬出是被上一批那句「不许再上调」逼出来的**：本批改的正是
        // 写入面，留在原地一定超线。拆点在写入面而不是 A5 排的 `runUser` / `compactRun` ——
        // 那两段早就是一行委托，余下体量集中在状态与写入，见 `HistoryWrites` 的文件头。
        // 2026-11-12 U76 由 805 收到 804（-1，实测）：修主线遗留的一处多余空行
        // （`toolResultMessage` 前，ktlint Needless blank line），按棘轮「往下走就改小登记」。
        "app/src/main/java/dev/kair/app/core/agent/AgentSession.kt" to 804,
        // 回合执行器（2026-10-31 A5 第二步从上面的 AgentSession 拆出）。**2026-11-06 U48 越过
        // 500 未登记线（488 → 545，+57）**：四条静默出口改可枚举原因 —— 终止原因显式携带
        // 不反查、步数用尽提示、关闭 / 中止区分、B 审的队列竞态出口处置，多的是每个出口
        // 「为什么是这个原因」的护栏注释（砍注释过守卫属于作弊，AGENTS.md §7）。
        // **何时拆**：`runLoop` 里的两阶段工具执行段（~120 行）是下一个抽出点（独立成协作件，
        // 同 A5 拆 `TurnExecutor` 的套路）；下一次要往 `runLoop` 加逻辑的批次**先拆后加**，
        // 拆完删掉本条目（拆出的新文件 < 500 不进白名单）。
        // 2026-11-10 U42 由 545 下调到 542（-3，实测）：Host 的 settings / toolFilter /
        // contextWindow 三条并入 inputs，溢出判定改读本发请求快照（不再回读设置）。
        // 2026-11-24 由 542 调到 546（+4，实测）：空回复提示文案 + `appendSystemNote`
        // （步数提示块同时收窄 7 行）；重试策略在新文件 `EmptyResponseRetry.kt`，
        // `runLoop` 仍是 215，没有往循环里加逻辑。
        "app/src/main/java/dev/kair/app/core/agent/TurnRunner.kt" to 546,
        // 装配层。P4-1 去 Android 化（docs/tasks/archive/P4-1-装配层去Android化.md）会分走一半职责。
        // 2026-10-27 批次 3 由 1146 调大到 1189（+43，诊断记录接线）—— 第 1 次因功能增长调大。
        // 2026-10-27 批次 4 由 1189 调大到 1190（+1，`status` 收集改成 `turnState`）—— 第 2 次，
        // 拆分计划见 P4-1（批次 9）。只 +1，属接线，不是新功能。
        // 2026-10-28 **由 1190 下调到 1133（-57）**：批次 6 B 段开工前解行数预算（G1 的 W1）——
        // 把回合摘要 `digestTurns` 与后台注入判定 `mayAdoptActiveSession` 两段纯逻辑搬到
        // `app/app/PluginBridgeSupport.kt`。**登记值不调大，只跟着实测往下走。**
        // 2026-10-28 **由 1133 下调到 1114（-19）**：P4-1 / A2 兑现拆分计划 ——
        // 「设置 → AgentSettings」装配抽成纯函数 `buildAgentSettings`（`app/AgentSettingsFactory.kt`），
        // `agentSettings` 只剩快照读取 + 工具过滤 + 诊断记录三件接线。
        // 2026-10-28 **由 1114 收到 1112（-2）**：AppEnvironment 双构造路径落地后实测。
        // 2026-10-30 由 1112 调大到 1116（+4，后台任务管理器字段 + 关闭清理接线）——
        // 管理器实现全在新文件 `app/BackgroundTasks.kt`，这里只留接线；属第 3 次因功能增长调大。
        // 2026-10-31 R1 由 1116 上调到 1125（+9）：受保护进程登记字段 `protectedProcesses`
        // （含两行说明为什么归属整个应用）+ import。实现全在新文件 `core/proot/ProtectedProcesses.kt`。
        // 2026-11-02 U19② 由 1125 上调到 1130（+5）：`deleteProject` 里清掉该项目工作区的段落缓存
        // 作用域（§10.5「删除项目时谁负责清理」这条现在才有答案）—— 一个局部 `workspace`（两处
        // 路径取值同一个 File，不再各调一次 `workspaceDir()`；访问器仍按用途分别是 `.absolutePath`
        // 与 `.path`，因为桶键就是装配侧那个 `.path`）+ 三行「为什么必须赶在删除之前取路径」+
        // 一行接线。缓存本体不在这个文件里。
        // 2026-11-02 U30 由 1130 上调到 1139（+9）：命中率观测面的宿主侧接线 —— import 1 行（
        // `agentSettings` 返回类型换成 `AgentAssembly` 后「为什么读数**不在这里记**」的解释 5 行、
        // 子智能体改用临时 `CacheInspector()` 的理由 4 行。读数本体在 `core/agent/` 那几个文件里，
        // 这里只有接线与被接线的东西；`recordSectionCache` 那条「顺手在这里也记一份」的路
        // 是 U30 第一轮 B 审 P1 的复活路径，已由 `SectionCacheStatsTest` 反向钉住。
        // 2026-11-03 U31 第一刀由 1139 下调到 1017（-122）：插件运行时桥（限流窗口 +
        // 四个 `AgentBridge` 方法）119 行搬进 `app/PluginRuntimeBridge.kt`，插件启用 / 禁用
        // 与动态插件删除 77 行搬进 `app/PluginLifecycleOps.kt`，容器只留接线与六个一行委托
        // （实测 `wc -l` = 1017）。第二、三刀各自单独下调。
        // 2026-11-03 U31 第二刀 + 3a 由 1017 下调到 934（-83）：附件回收 53 行搬进
        // `app/AttachmentGc.kt`（连同两个常量），工作区文件访问 51 行（含 KDoc；成员体 32 行）搬进
        // `app/WorkspaceFileAccess.kt`，容器只留委托与接线（实测 `wc -l` = 934）。
        // 第三刀（session lifecycle）未动，单独交下一批。**行号按函数名定位**：搬完已经漂了 ——
        // 该段现在从 `:644` 的 `// ---- session lifecycle` 起，解析族那段是 `:524`–`:642`，
        // 逐成员的新锚点记在交接 维护-拆AppContainer二三刀.md 的终审一节。
        // 2026-11-03 U31 ③-a 由 934 下调到 859（-75）：解析族 9 个成员（含 KDoc）搬进
        // `app/SessionAssembly.kt`，容器只留同名委托与一处 wiring（实测 `wc -l` = 859）。
        // 新文件 165 行 < 500，不进白名单；三条私有成员的可见性在协作对象里是 internal
        // （容器委托仍 private，对外签名逐字不变）。
        // 2026-11-03 U31 ③-b 由 859 下调到 736（-123）：编排段（两处 AgentSession 构造 +
        // open / close / delete / new / 会话日志联动，含 KDoc）搬进 `app/SessionLifecycle.kt`，
        // 容器只留同名委托与 sessionLifecycle wiring（实测 `wc -l` = 736）。
        // 两处构造本身收口到 `app/SessionAssembly.kt` 的 `agentSession(spec)`（一处装配）。
        // 2026-11-15 U89M 由 736 上调到 739（+3）：音频接收端换 RTP 实现的那句 KDoc。
        // 这是**唯一一次上调**，也是棘轮的例外：接收端从裸 PCM 换成 RTP 是路线变更，
        // 不是往编排段里塞代码。若继续涨，应先把音频 wiring 搬进 `SessionAssembly.kt`。
        "app/src/main/java/dev/kair/app/app/AppContainer.kt" to 739,
        // MCP 协议解码集中一处，刻意为之。无拆分计划。
        // 2026-11-07 U41 由 748 收到 747（-1，实测）：`terminate` 的 KDoc 压成两行。
        "app/src/main/java/dev/kair/app/core/mcp/McpProtocol.kt" to 747,
        // 容器插件：面板 + 命令通道 + 常驻会话宿主三类职责同居一处。
        // A4（隐式工作区）会拆掉一部分，但**没有整体拆分计划**。
        // 2026-10-30 D1 由 742 调大到 751（+9，ProotPanel 补 verticalScroll —— 宿主不提供滚动，
        // 面板自己负责；含 import 与契约说明）—— **第 1 次因功能增长调大**。
        // 2026-10-30 F1 由 751 收到 750（-1）：镜像 / 失败 UI 抽到 ProotRootfsUi.kt，
        // 面板不再继续变胖。**登记值只跟着实测往下走。**
        // 2026-10-30 第 9 笔（镜像预设）本来多了一个 import 顶到 751；B 审把装配处那行
        // 注释压掉、保住 750 —— **不放宽登记值**（放宽一次，下一次就更容易放宽）。
        // 2026-10-30 单挂载改造由 750 收到 733（-17）：热挂载提示文案 / 面板计数换成
        // 「项目根目录」一行，MCP cwd 改为项目路径。**只跟着实测往下走。**
        // 2026-10-31 R1 由 733 上调到 753（+20）：会话启动块抽成 `startSession`
        // （activate 本身反而降到阈值内），外加 `protectedCommands` 构造参数与会话就绪后的恢复调用。
        // 2026-10-31 X1 由 753 收到 751（-2）：新增唯一清理点调用，同时压缩启动注释抵消。
        // 2026-11-08 U59 由 751 收到 725（-26）：两条通道的装配与启动搬进 `ProotSessionWiring.kt`
        // （PTY 与帧协议会话同一套 host 语义），这里只留一行调用。
        // 2026-11-08 U63 由 725 收到 **685**（-40）：执行通道切换 `ModeSelector` 搬进
        // `ProotRootfsUi.kt`（选中态要 Compose 状态，原处一改就顶破登记值）。**只跟着实测往下走。**
        "app/src/main/java/dev/kair/app/ui/plugins/builtin/ProotPlugin.kt" to 685,
        // 插件页：筛选 / 详情 / 卸载确认都在一个文件。无拆分计划。
        // 2026-11-08 U64 由 685 调到 **686**（+1）：插件页新增一行「已内置（外部版本未装载）」入口；
        // 渲染体本身拆进了 `ui/screens/ShadowedPluginsSection.kt`（72 行，不进白名单），这里只剩调用。
        // 2026-11-11 U46 由 686 收到 **684**（-2）：插件贡献导出改组合期直读，
        // `PanelHostScreen` 两处 `remember(panelId)` 块收成单行直读，注释走行内，净 -2。
        // 2026-11-13 U86 由 684 收到 **615**（-69，实测）：`PanelHostScreen` 整体拆到同目录
        // `PanelHostScreen.kt`（沉浸分支「先拆再改」，任务书 §7.1），本文件只剩插件页本体。
        "app/src/main/java/dev/kair/app/ui/screens/PluginsScreen.kt" to 615,
        // 六件文件工具同源实现，拆之前要先定 FileBackend 边界。无拆分计划。
        "app/src/main/java/dev/kair/app/core/tools/BuiltinTools.kt" to 691,
        // 对话页主体。批次 4（A1）起状态消费点收敛到单一 `turnState`，不减行。
        // 2026-10-27 批次 4 由 646 调大到 649（+3，`status`/`compacting`/`stopping` 三处订阅
        // 合成一处）—— 第 1 次因功能增长调大。无拆分计划。
        // 2026-10-30 **由 649 收到 644（-5）**：PR #34 把 `scrollToLatest` 的调用点与 D4 侦察
        // 挪去 `AutoScroll.kt`。**多出来的余量等于豁免**，实际多少就登记多少。
        // 2026-10-31 C1 由 644 上调到 661（+17）：退后台清焦点（2 行 LifecycleEventEffect +
        // 说明）与点空白清焦点（父级 pointerInput，5 行），外加 2 个 import。都是行为本身。
        // 2026-11-01 U22 由 661 收到 588（-73）：输入区整段搬到 `Composer.kt` 的
        // `ComposerInputBar`（同文件新增的排队区动作也留在 Composer 侧），这里只剩调用点。
        // **只跟着实测往下走。**
        // 2026-11-01 U22 B 审 §B4.4 P2 由 588 调到 593（+5，撤回一帧竞态的守卫与说明）——
        // 负责人指令「要么修这一行，要么写明不防」，选了修；不改 removeQueued 签名（插件 ABI）。
        // 2026-11-01 插话挪进排队区由 593 收到 **590**（-3）：`submit` 的 mode 分支删掉、
        // 改道实现收进 `Composer.promoteToSteer`，这里只剩一行调用点。
        // 2026-11-05 由 590 收到 **580**（-10）：↓ 按钮连同悬浮配色 / 阴影抽到
        // `AutoScroll.kt` 的 `JumpToBottomButton`，`ChatScreen` 只剩一行调用。
        // 2026-11-06 U52 由 580 上调到 **611**（+31）：三处 `remember` 的同步读盘改成一个
        // `produceState` + `Dispatchers.IO`，并把加载中 / 缺失 / 就绪三种结果显式化
        // （新增 `ChatSessionState` 与其说明）。同批删掉了不可达的 `chat_title` 回退资源。
        // **无拆分计划**：整屏仍是一个组合函数，拆碎会把顶栏 / 列表 / 输入区的状态切散。
        // 2026-11-07 U58 由 611 下调到 609（-2，实测）：停止键与状态条改为吃 `turnState`，
        // 这里不再各存一个 `busy` / `stopping` 派生值。
        // 同日命中率口径统一再由 609 收到 **608**（-1）：圆环改读 `CacheReport.hitTally.weightedRate`，
        // 不再需要 `dev.kair.app.core.model.cacheHitRate` 的 import。**只跟着实测往下走。**
        // 2026-11-09 U66 由 608 上调到 **612**（+4，实测）：瞬态提示条的宿主从 Scaffold 的
        // 屏幕底部挪进消息区顶部（弹出时压住「停止 / 发送」），多一行调用与三行理由注释。
        // 规矩见 docs/界面规范.md §3。**无拆分计划**不变。
        // 2026-11-17 U99 由 612 **收到 596**（-16，实测）：控件行从板上方挪进板里之后，
        // 坞与控件行拆成两个组件（`ComposerDock` / `ComposerControlRow`），这一屏只剩装配；
        // 顺带把「输入栏在上、坞在下」的调用顺序正过来。**只跟着实测往下走。**
        // 2026-11-25 行级虚拟化由 596 **收到 594**（-2，实测）：折叠体拆到 `FeedRows.kt` 后，
        // 本屏只多三处接线（展开态 / buildRows / 行渲染），净行数反而降了。
        "app/src/main/java/dev/kair/app/ui/screens/chat/ChatScreen.kt" to 594,
        // 压缩纯逻辑 + 内置策略。P1-1（B3）会改摘要降级，不减行。无拆分计划。
        // 2026-10-27 批次 3 由 584 调大到 599（+15，降级理由透传）—— 第 1 次因功能增长调大。
        // 2026-11-24 由 599 调到 **617**（+18，实测）：单回合超过保留预算 2 倍时，切点退到
        // 回合内部的步骤边界（94 步单回合压不动的兜底）；多的是判定与「为什么切 assistant」
        // 的护栏注释。仍无拆分计划。
        // 2026-11-25 由 617 调到 **628**（+11，实测）：保留量是下限而不是上限 —— 向前吸附到
        // 一个刚发的新回合时，退到包含累加点的回合起点（真机 31k 预算只留 4k 的修复），
        // 并把「唯一超大回合也能切」从 cutIndex<=0 的早退里放出来。仍无拆分计划。
        "app/src/main/java/dev/kair/app/core/agent/Compaction.kt" to 628,
        // Gemini 原生协议（含 schema 裁剪）。与其余三家同形，无拆分计划。
        // 2026-10-30 由 536 收到 535（-1，实测）。同上：只跟着实测往下走。
        "app/src/main/java/dev/kair/app/core/ai/GeminiProvider.kt" to 535,
        // 常驻容器会话状态机。无拆分计划。
        // 2026-10-30 单挂载改造由 519 收到 474（-45）：热挂载 `syncMounts` 整段删除、
        // `mountedProjects` 改名 `mountedMounts`。**只跟着实测往下走。**
        // 2026-10-31 R1 由 474 上调到 475（+1）：只有一行注释（会话脚本改调 buildSessionScript）。
        // 同日 R1 续 +7 → 482：受保护进程的 `protectedCommands` 构造参数与说明（清扫跳过它们）。
        // 2026-10-31 X1 由 482 收到 463（-19）：启动 / 停止不再清挂载（清理点移到 App 启动后
        // 的唯一入口），`purgeMounts` 整段删除。**只跟着实测往下走。**
        // 2026-11-01 U16 由 463 收到 453（-10）：「命令 ↔ 退出帧」的配对状态搬到
        // `core/proot/ExecFrameQueue.kt`（拆出时 73 行，现 86；新文件不进白名单），这里只留调用点。
        // 同日 B 审 P2 由 453 调到 455（+2）：`exec` 加「一条命令一个退出帧」的守门
        // （多行直接拒绝）+ 写入异常路径撤销条目。两条都是配对前提的守门，不是新功能；
        // 相对上批仍净 -8（463 → 455）。
        // 2026-11-07 U60 由 455 上调到 **497**（+42）：`exec()` 加 120s 上界、
        // `suspectStalled` 可观察标记与面板复位点（超时不撤队列条目 —— 配对前提）。
        // 真实功能增长；仍 < 500，无拆分计划。
        // 2026-11-08 A 复判由 497 上调到 **499**（+2）：B 审 P1 把构造参数里的
        // `outputOf` 快照挪到 `entry.done.await()` 之后（原写法左到右求值会取到空缓冲），
        // 多了注释与 `val code` 两行。仍 < 500，无拆分计划。
        // 2026-11-08 U59 由 499 收到 **496**（-3）：启动三元组构造搬进 `SessionLaunchFactory`
        // （PTY 通道复用同一份），两个 start 的 ProcessBuilder 段合并成 `startProcess`。
        // 2026-11-10 U44 由 496 收到 **487**（-9）：启动闸门（READY / 启动期断流 / 代次）进类，
        // 但起进程搬 `SessionProcessLauncher`、转录搬 `SessionTranscript`、帧解析搬 `SessionFrames`，
        // 三处腾出的行数盖过新增；内层 `exec` 的两处「未在运行」检查也合并成一处。
        // 2026-11-14 U89 E 由 487 收到 **474**（-13）：会话级音频（`.asoundrc` 安装 /
        // `kair-audio-status` / `nc` 补装）搬进 `SessionAudio.kt`，这里只留调用点与宿主提示入口。
        "app/src/main/java/dev/kair/app/core/proot/ContainerSession.kt" to 474,
        // MCP 服务器管理面板。无拆分计划。
        // 2026-11-17 U97 由 517 收到 **518**（+1）：面板图标的字面量 `"\uD83D\uDD0C"` 换成
        // `PluginGlyph.Plug` 常量，多一行 import —— 图标本身只是换了个写法，没有新逻辑。
        // 这一行本可以靠删注释压回去，但那属于「为了过守卫而砍说明」（AGENTS.md §7），不走。
        "app/src/main/java/dev/kair/app/ui/plugins/builtin/McpPlugin.kt" to 518,
        // root 命令通道（PATH 解析 / su 定位 / 进程管理）。无拆分计划。
        // 2026-10-28 **由 517 下调到 514（-3）**：C1 提交 1 把挂载清单类型化（`core/proot/Mount.kt`），
        // `PURGE_POINTS` 常量表退役、回收序改由清单派生。**登记值只跟着实测往下走。**
        // 2026-10-30 单挂载改造由 514 收到 512（-2）：`WORKSPACE_MOUNT` 常量删除，
        // 新增 `containerWorkspacePathFor` 后 KDoc 压缩抵消。
        // 2026-10-31 R1 由 512 上调到 528（+16）：新增会话清扫入口 `buildSessionScript`
        // 与 `buildScript` 的 `reapStale` 参数（含说明为什么必须在 chroot 之前）。真实功能增长。
        // 同日 R1 续 +5 → 533：`protected` 参数（受保护命令行透传给 reapCommand）。
        // 2026-10-31 X1 由 533 收到 531（-2）：删 trap cleanup 与取消时的 umount，新增幂等
        // 挂载段与唯一清理点入口（清理脚本本身搬到 `MountPurge.kt`）。净减 2 行。
        // 2026-11-07 U60 由 531 上调到 **545**（+14）：新增 `killDetachedTree`（后台任务以
        // root 运行，宿主无权 SIGKILL，必须经 su 递归收树；复用既有的 `killRootDescendants`）。
        // 真实功能增长；无拆分计划（通道入口与收树脚本必须同居，拆开会多一份 fork 语义）。
        "app/src/main/java/dev/kair/app/core/proot/RootCommandRunner.kt" to 545,
    )

    @Test
    fun `no new file exceeds the size limit`() {
        val files = mainSources()
        assertTrue("主源码里一个 .kt 都没扫到 —— 扫描路径写错了，不等于没有超限文件", files.isNotEmpty())
        val offenders = files
            .map { repoRelative(it) to it.readLines(Charsets.UTF_8).size }
            .filter { (_, lines) -> lines > LIMIT }
            .filter { (path, _) -> path !in oversized }
        assertTrue(
            buildString {
                appendLine("新建 / 已存在的文件超过 $LIMIT 行且未登记在白名单里（B8）：")
                offenders.forEach { (path, lines) -> appendLine("  $path = $lines 行") }
                appendLine()
                appendLine("正确做法是**拆**，不是加白名单：加一个条目等于承认这里再多一个 500+ 行的文件。")
                append("确实需要豁免时，在 SourceSizeGuardTest.oversized 写清「为什么」与「何时拆」，PR 里单列一条。")
            },
            offenders.isEmpty(),
        )
    }

    @Test
    fun `whitelisted files do not grow`() {
        val grown = oversized.toList().mapNotNull { (path, allowed) ->
            val actual = File(repo, path).let { if (it.isFile) it.readLines(Charsets.UTF_8).size else -1 }
            when {
                actual < 0 -> Triple(path, allowed, "文件不存在")
                actual > allowed -> Triple(path, allowed, "实际 $actual 行，比登记的 $allowed 行多了 ${actual - allowed} 行")
                else -> null
            }
        }
        assertTrue(
            buildString {
                appendLine("白名单不是「超更多的豁免」（B8 棘轮）：")
                grown.forEach { (path, allowed, why) -> appendLine("  $path（登记 $allowed 行）：$why") }
                appendLine()
                append("要往下走请顺手把登记的数字改小 —— 棘轮只准往回转，不然下一个人可以免费加回来。")
            },
            grown.isEmpty(),
        )
    }

    @Test
    fun `oversized inventory is the size we think it is`() {
        // 白名单条目数与 §2.3 #2 / 架构加固计划登记的 14 个不一致时，说明有人加了豁免
        // 或整类文件消失了 —— 两种都该被看见。
        assertTrue(
            "白名单当前登记 ${oversized.size} 个文件，docs/团队分工与交付流程.md §2.3 #2 记的是 $EXPECTED_WHITELIST 个；" +
                "改完这一处请同步改那一处。",
            oversized.size == EXPECTED_WHITELIST,
        )
    }

    private fun mainSources(): List<File> = mainRoots()
        .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

    private fun mainRoots(): List<File> = listOf(
        requireDir(File(repo, "app/src/main"), "app 主源码"),
        requireDir(File(repo, "plugin-api/src/main"), "plugin-api 主源码"),
    )

    private fun requireDir(file: File, what: String): File {
        assertTrue("$what 不存在（$file）—— 体量守卫无法运行", file.isDirectory)
        return file
    }

    private fun repoRelative(file: File): String =
        file.absolutePath.removePrefix(repo.absolutePath + File.separator).replace(File.separatorChar, '/')

    private val repo: File by lazy { findRepoRoot() }

    private fun findRepoRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "docs").isDirectory && File(dir, "app").exists()) return dir
            dir = dir.parentFile
        }
        error("找不到仓库根（须含 docs/ 与 app/），体量守卫无法运行")
    }

    private companion object {
        const val LIMIT = 500
        const val EXPECTED_WHITELIST = 13
    }
}
