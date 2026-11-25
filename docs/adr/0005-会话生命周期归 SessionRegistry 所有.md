# 0005. 会话生命周期归 `SessionRegistry` 所有

- **状态**：**已接受**（负责人批准 2026-10-27，未改任何结论）
- **日期**：2026-10-26（起草）／2026-10-27（批准）
- **批准记录**：负责人 67 于 2026-10-27 批准。**原「需负责人定容量 / 空闲阈值」一项已撤销**：
  查证现状为 `maxOpenSessions = 8` + LRU 淘汰（`AppContainer.evictIdleSessions`，无时间维度），
  批准时确认**维持现状**，本 ADR 不引入新的产品参数。批次 5 可开工，但**不早于批次 4 合入**
- **前置**：`docs/tasks/archive/P0-1-主循环测试底座.md`（B5）已完成
- **关联**：`docs/架构评估-2026-10.md` §3.1（隐式全局）、`docs/架构加固计划.md` §6.1

## 背景

会话实例存放在 `AppContainer.openSessions: ConcurrentHashMap<String, AgentSession>`。
围绕它的清理逻辑**已经基本齐备**——这一点必须先说清楚，否则会误判成本：

| 已有机制 | 位置 |
|---|---|
| `deleteProject` 关闭该项目下所有会话 | `AppContainer` |
| `closeSession` → `approvalGate.endSession` + `subagents.abortChildrenOf` + `unloadSession` | `AppContainer` |
| `unloadSession` 清 `runningJobs` / `_runningSessions` / `openSessions` / `sessionAccessedAt` / `cacheInspector` | `AppContainer` |
| `CacheInspector.remove(sessionId)` | `core/agent/CacheInspector.kt` |
| `evictIdleSessions`（上限 + 空闲优先 + 不卸载忙会话） | `AppContainer` |
| `AgentSession.closed`，关闭后丢弃持久化回调 | `core/agent/AgentSession.kt` |

**问题不在「有没有清」，而在「谁负责清」没有答案。**
这份逻辑散落在 1146 行的 `AppContainer` 里、分布在四个方法里，各自清一部分，
**没有一处是「按会话登记的全部资源」的清单**。后果是可预测的：

- 新增第 7 个按会话登记的东西时，没有任何地方会提醒还要加清理 ——
  上一批 6 个登记项里，`CacheInspector` 与 `ALLOW_SESSION` 就曾是漏掉的那两个
  （`docs/review/archive/verify-A.md` 有条目为证，后被技术负责人代改逐条补上）；
- 「后台运行会话不可被卸载」靠一个布尔表达式 `!it.isBusy && it.queue.value.isEmpty()` 守着，
  改漏了不报错；
- `AppContainer` 1146 行 + 19 处隐式全局引用，是 A4（会话作用域显式化）的拦路石。

因此本 ADR 要解决的不是今天的 bug，而是**明天的漏清理**。

## 决策

1. 引入 `SessionRegistry`（宿主层，`app/`），作为会话实例的**唯一持有者**。
   `AppContainer` 不再直接持有 `openSessions`，一切访问经 `SessionRegistry`。

2. 只有四个对外动作：

   - `open(project, meta)` — 创建并登记（保持现有 `getOrPut` 幂等语义）；
   - `get(id)` — 只读取出；
   - `release(id)` — **关闭的唯一入口**；
   - `evict(idleOnly = true)` — 空闲卸载（**永不卸载运行中会话**）。

   `release` 只释放该会话登记的资源；**删数据是 `deleteSession`**，两者不是一回事。

3. `release()` 是**唯一**触发清理的地方，且清理项以**注册表**形式集中声明，
   新增登记项时只改一处：

   | 清理项 | 现状 |
   |---|---|
   | `AgentSession.discard()` → `abort()` + 置 `closed`（停止持久化回调） | 已有 `closed` 标记，落点分散 |
   | `CacheInspector.remove(sessionId)` | 需新增 `remove` |
   | `ApprovalGate.endSession(sessionId)`（按 `"$sessionId/"` 前缀删） | 需新增 |
   | 子智能体收尾（`abortChildrenOf`） | 逻辑已有，调用点缺 |
   | 常驻容器会话解绑（若该会话持有） | 需定义「持有」关系 |
   | `previousBySession.remove(sessionId)` | 需新增 |

4. `AppContainer.deleteProject(project)` 与 `deleteSession(project, id)` 一律经 `release()`，
   不再各自 `releaseSession`。UI（`HomeScreen` 的删除入口）改调容器方法。

5. 「后台运行会话不可被卸载」写成 `evict()` 的断言，不是注释。

## 后果

**收益**

- 「按会话登记的东西」有了唯一登记点，新增登记项时只改一处，
  「登记了但没人清」这一类缺陷在结构上变得困难；
- 为 A4（`activeSessionId` 显式化）提供一张边界明确的表 —— A4 是在这之上做收敛，
  而不是在 1146 行、19 处隐式全局引用的 `AppContainer` 上做跨层重构；
- `AppContainer` 显著变薄，向「唯一装配点，只做接线」这个定位（`代码索引.md` §1.3）靠拢。

**代价**

- 新增一层间接：所有 `openSessions[...]` 的读写点要改（宿主层内部，不动契约）；
- `evict` 策略需要定容量 / 空闲阈值，属于产品决策，须负责人确认
  —— **已确认（2026-10-27）：维持现状，上限 8 + LRU，不加时间维度**。此项不再阻塞开工；
- 与 `ProotPlugin` 的常驻容器会话存在「谁持有谁」的耦合，需要显式定义关系，
  否则会出现「会话关了容器还挂着」的新形态。

## 被否决的选项

| 选项 | 否决理由 |
|---|---|
| 维持现状（清理逻辑散在 `AppContainer` 四个方法里） | 现在能工作，但新增登记项时无人提醒要加清理；上一批已经漏过两次，只是被技术负责人代改补上了 |
| 在 `AgentSession` 里加 `onClose` 回调列表 | 清理项属于宿主资源，放内核会让 `core` 反向依赖宿主概念；且内核四块不宜再加职责 |
| 靠 LRU + 弱引用自动回收 | 「删除后收尾又写回文件」这类问题需要**确定性**的关闭语义，弱引用给不了 |
| 直接做 A4（先显式化，生命周期以后再说） | 在「会话随时可能半死」的地基上做跨层重构，风险最高的一项反而最先做 |

## 验收（拍板后由技术负责人 / B 审复核）

- [ ] 关闭一个会话后，上表 6 项资源在测试里**被机械验证为已清空**（逐项断言，不是「应该清了」）；
- [ ] 删除项目时其打开会话被逐个关闭（含子智能体），`runningSessions` 无幽灵条目；
- [ ] 删除运行中会话后，`.jsonl` 不再被收尾写回；
- [ ] 有「后台运行会话不可被卸载」的用例（跑一个长任务，触发 `evict`，断言会话仍在）。

## 实现补记（**技术负责人 2026-10-27 合并时补，不改变上面任何一条决策**）

落地时与本文有三点差异，都是实现细节而非决策变更，记在这里免得下一个人对着本文找茬：

1. **对外动作不止四个**。除 `open` / `get` / `release` / `evict` 外还有：
   - `releaseWhere(predicate)` —— `deleteProject` 需要「关掉该项目下的会话」，筛选条件
     （按工作区匹配）只有 `AppContainer` 算得出来，注册表不认识「项目」这个概念。
     它是 `release()` 的批量入口，**不是另一条清理路径**；
   - `size` / `ids` / `all()` —— 只读观测，供 `referencedAttachmentShas` 等既有调用点使用。
   决策 2「只有四个对外动作」的**意图**是「生命周期只有一个入口」，上述几项没有破坏它：
   `releaseWhere` 内部调用的仍是 `release()`。
   另记一处对齐，免得下面有人以为「被删了一个参数」：决策 2 那行原文写的是 `close(id, reason)`，
   而**落地始终是单参**（骨架 `992e772f` 起就是 `fun close(id: String): Boolean`，U73 改名后为 `release(id)`）——
   `reason` 从未实现过，「为什么清」由 `Cleanup` 枚举与调用点注释承担，不靠入参传递。
   U73 的改名笔把本行按实际签名写成了 `release(id)`，这条 arity 偏差属于落地差异，登记在本节，
   读决策 2 时以 `release(id)` 为准。

2. **清理清单实际是 10 项，不是上表的 6 项**。实现时又找出三项按会话登记的资源：
   `diagnostics.remove`（批次 3 才加的）、`RequestCapture.clear`（原本只在 `deleteSession` 里清，
   `deleteProject` 那条路径漏了）、`pluginSendLog.remove`（本就没人清）。
   **这三项都是靠人肉找出来的，不是靠文档** —— 恰恰印证了本 ADR 背景里说的
   「新增登记项时没有任何地方会提醒」。

3. **`evict` 清的项比 `release` 少**：审批规则、子智能体、请求记录、限流窗口**都不随淘汰清**
   —— 淘汰只是内存回收，用户回来时这些应当还在。限流窗口尤其不能清：
   淘汰后会话 id 还在用，清掉槽等于把「每分钟 6 条」的额度重置。
