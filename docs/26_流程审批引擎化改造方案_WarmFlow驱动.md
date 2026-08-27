# 流程审批引擎化改造方案（Warm-Flow 驱动）

> **状态：未采纳（2026-08-25 评审后改选方案一）**。本文档仅作选型记录保留，实际执行方案见 `docs/27_流程审批配置化改造方案_模板驱动.md`。
> 原选型结论：方案二——以 Warm-Flow 流程定义为唯一拓扑来源，重写审批推进为引擎驱动。
> 边界（已与业务确认）：串行节点自由增删改 + 特殊节点（表决/行长/秘书岗）保留现有业务逻辑 + 在途单按冻结口径走完。
> 依据：方案讨论基于当前仓库 HEAD 源码核实，引用格式 `文件:行号`。

---

## 1. 背景与目标

现状的审批拓扑是硬编码的（痛点清单见 §3），增加一个节点必须改代码、发版、重排矩阵优先级。改造目标：

1. **流程拓扑配置化**：审批节点（含顺序）在库里配置，增删节点、调整顺序不需要改代码、不需要发版，发布即生效（对新提交）。
2. **流转引擎化**：节点推进由 Warm-Flow 引擎驱动，业务状态是引擎状态的投影，废除 `ApprovalServiceImpl` 里的手写推进状态机。
3. **治理能力沿用**：流程定义走与矩阵/LPR 相同的 草稿→送审→双人复核发布→停用 生命周期，全程审计留痕（`ccr_config_change_log`）。
4. **存量无感**：在途申请按提交时冻结的流程定义版本与终审口径走完，新提交按新定义。

## 2. 非目标（明确不做）

- 不支持并行会签、任意 DAG 拓扑。拓扑仍为"串行链 + 条件跳过节点"（覆盖秘书岗场景）。后续需要再单独立项。
- 不重写表决、行长决策的业务逻辑（委员名单冻结、计票、超时强制计票、整单否决、匿名意见），只把它们的**触发点**改为引擎事件。
- 不做在途单的流程迁移。在途单走旧路径直至终态（双轨期，见 §8）。
- 不改节点审批人指派体系（`ccr_node_assignee` 四层解析已配置化，且原生支持按 `flow_key` 区分流程专属配置，`NodeAssigneeResolver.java:150-163`）。

## 3. 现状盘点：硬编码清单（本次改造要拆除的）

| 位置 | 硬编码内容 |
|---|---|
| `backend/ccr-approval/.../support/RouteChains.java:19-21` | 贷款链 BM→GM→VP→GROUP、存款链 BM→GROUP，全部常量 |
| `backend/ccr-workflow/.../WarmFlowServiceImpl.java:183-200` | `rate_approval` / `deposit_approval` 两条流程定义的节点列表，启动时初始化，已发布则跳过 |
| `RateMatrixRouterImpl.buildChain()` :605-620 | 路由链按矩阵命中行 priority 拼接，加节点要重排全局 priority |
| `RateMatrixRouterImpl.applySecretaryGate()` :384 | 秘书岗条件插节点（贷款 ≥1000万 且 <2.6%），阈值是 Java 常量 |
| `ApprovalServiceImpl.approve()` :168 起 | 手写推进：齐套判定、`nextNode` 沿冻结链、escalate 上送、GROUP 触发建批、特判（存款仅支行行长过手 :179、本支行限制 :183） |
| `frontend/src/utils/dict.ts:116-120` | 节点中文名 `NODE_LABELS` 前端字典 |
| `ApprovalServiceImpl.checkOperatorAndNode()` :1714 | GROUP/PRESIDENT 不属于普通审批通道的字面量判断 |

现状里**已经是配置化、本次直接复用**的：节点审批人指派（`ccr_node_assignee`）、节点调价权限（`ccr_node_permission`）、权限矩阵与产品硬边界（含双人复核发布、缓存、审计）、产品审批链路 `ccr_product_route`（已含 `flow_key` 字段，本方案把它升级为流程定义选择器）、提交时冻结机制（在途兼容的基础）。

## 4. 目标架构

### 4.1 分层

```
配置层   流程定义(Warm-Flow flw_definition/flw_node/flw_skip) + 治理包装(生命周期/审计)
           ↑ 配置页维护,发布新版本
路由层   提交时:权限矩阵 → 终审节点 + 边界 + 部门 + LPR(不再产出"链")
           → 按 业务大类/产品链路flow_key 选定流程定义与版本,冻结到分项
引擎层   Warm-Flow:分项级流程实例,节点推进/条件跳转由引擎执行
           → 引擎监听器(节点进入/完成)触发业务动作
业务层   ccr-approval 退化为"审批动作适配器"(守卫/调价/落库);
           ccr-vote/ccr-resolution 由引擎事件触发;整单聚合保留在业务层
```

### 4.2 流程定义模型

- **一个业务大类一条流程定义**：`rate_approval`（贷款）、`deposit_approval`（存款/保证金）保留；产品链路 `ccr_product_route.flow_key` 可指向自定义定义，实现"某产品走独立流程"。
- **节点类型**（用 Warm-Flow 节点 + 扩展属性 `ccr_node_type` 表达）：
  - `NORMAL` 普通审批节点：通用行为（守卫 → 调价校验 → 同意/否决/上送），行为由类型驱动，新增节点零代码
  - `VOTE` 表决节点：进入时触发 `voteService.createGroupRound`，计票结果驱动流转
  - `PRESIDENT` 行长决策节点：进入时生成行长待办，决策驱动流转
  - `CONDITIONAL` 条件节点（秘书岗）：进入前由条件服务求值，不满足自动跳过
- **跳转线（skip）承载终审截断**：每个普通节点挂两条出线——"继续"（到下一节点）与"终审"（到结束节点）。条件服务求值规则：当前节点 == 分项冻结终审节点 → 走"终审"。**终审判定沿用提交时冻结口径，引擎只负责走线**，这保证了路由语义不变、在途兼容可行。

### 4.3 提交与路由（改动后）

1. `ApplicationSubmitServiceImpl.submit` 的校验链 a–g 不变。
2. 矩阵路由输出精简：`RateMatrixRouterImpl.calcRoute` 仍返回 终审节点、边界值、部门归属、LPR 版本、命中行号，但**删除 buildChain/链构建**（链由流程定义表达）。`applyPresident`/`applySecretaryGate` 改为路由结果上的标记位（必经行长 Y/N、条件节点命中 Y/N），由引擎定义承接。
3. 分项冻结字段调整：`route_code`（终审节点）、`boundary_rate`、`dept_code`、`lpr_version_id` 保留；`route_chain` 改为冻结 **flow_key + 流程定义版本号**（`flow_definition_id` + `version`）。在途单的旧 `route_chain` 字段保留只读。
4. Outbox `FlowStartOutboxHandler` 按冻结的定义版本启动分项级流程实例（businessId 维持分项编号，轨迹不断档）。

### 4.4 审批推进（重写核心）

- 分项在普通节点：审批动作（同意/否决/调价）经 `ApprovalServiceImpl` 守卫与落库后，调引擎 `complete`；引擎按跳转线推进或终审。引擎监听器把节点变化回写分项 `current_node_code`，终态时回调 `itemFinalizationService.afterItemTerminal`。
- **整单口径不变**：齐套判定（同申请分项均在本节点已同意才整单推进）、贷款 sibling 就地终审、存款 sibling 随整单上会，保留为业务层规则——具体做法：审批动作先落 `ccr_approval_action`，齐套判定通过后才对该申请下各分项批量调引擎 `complete`。引擎只推节点，齐套逻辑始终在业务侧，引擎不做它不理解的事。
- **调价重算**：贷款调价后重算矩阵（现有 `recalcRoute` :1842），若终审节点变化，调引擎跳转接口（`skipToNode`）把实例跳到新链位置，并更新冻结字段。POC 需验证 Warm-Flow 任意跳转能力（§9 R1）。
- **特殊节点**：
  - 进入 `VOTE` 节点 → 监听器调 `createGroupRound`，分项置 VOTING；计票通过 → 引擎推进到 PRESIDENT；未过 → 整单否决（现有逻辑整体搬入监听器）。
  - 进入 `PRESIDENT` 节点 → 生成行长待办；`presidentDecision` 决策后调引擎 `complete` 到结束。
  - `CONDITIONAL` 节点（秘书岗）：进入前条件服务求值（金额 ≥1000万 且利率 <2.6%，阈值移入流程定义扩展属性/配置表，不再是 Java 常量），不满足则引擎自动跳过。

### 4.5 状态映射

`ccr_pricing_item.status` 语义不变（DRAFT/ROUTING/VOTING/...），但作为引擎状态投影由监听器统一回写：实例运行中=ROUTING（current_node_code=引擎当前节点）、VOTE 节点=VOTING、PRESIDENT 节点=PRESIDENT_DECISION、正常结束=终审（APPROVED_LEVEL→FINAL 聚合）、否决结束=REJECTED/VETOED。主申请状态聚合逻辑（现有 `itemFinalizationService`）不动。

### 4.6 配置页与治理

- 新增"流程设计"配置页（`系统管理`下）：流程定义列表 + 节点表格化编辑（增删节点、上下调序、选节点类型、配指派入口、配条件节点阈值）+ 流程图预览。第一期不做拖拽设计器，表格 + 预览即可。
- 治理包装表 `ccr_flow_publish`（或复用 `ccr_product_route` 扩展）：记录 草稿/待复核/已发布/已停用 与 Warm-Flow 定义版本的对应关系；发布动作 = 双人复核（创建人≠发布人）→ 生成 Warm-Flow 新版定义并发布 → 写 `ccr_config_change_log`（CONFIG_TYPE 新增 `FLOW_DEF`）→ 清缓存（新增 key `ccr:cfg:flow-def:effective`）。
- 前端 `NODE_LABELS` 改为接口下发（定义里的节点名即显示名），删除前端硬编码字典。

## 5. 数据模型变更

| 变更 | 说明 |
|---|---|
| `ccr_pricing_item` 新增列 | `flow_key` VARCHAR(64)、`flow_version` VARCHAR(16)（冻结定义版本）；`route_chain` 保留只读兼容在途单 |
| 新增 `ccr_flow_publish` | 治理包装：flow_key、definition_id、version、status(DRAFT/REVIEW/EFFECTIVE/INVALID)、publish_by/review_by/publish_time |
| `ccr_config_change_log` | CONFIG_TYPE 枚举加 `FLOW_DEF` |
| `ccr_node_assignee` | 不动（flow_key 字段原生支持流程专属指派） |
| `ccr_product_route` | 语义升级：`flow_key` 正式作为流程定义选择器（现状为空=默认流程，向后兼容） |
| Warm-Flow `flw_*` | 定义/节点/跳转线由发布动作生成，不手工维护 |

增量 SQL 按项目规矩走 `db/incr/` 新编号脚本，登记 `docs/22_版本固化与增量更新台账.md`。

## 6. 模块改造清单

| 模块 | 改动 |
|---|---|
| ccr-workflow | 从"轨迹载体"升级为驱动器：定义版本解析、分项实例启动/完成/跳转、节点监听器（进入/完成）、条件服务（终审截断/秘书岗阈值）。`StandardFlowInitializer` 删除，改由发布动作生成定义 |
| ccr-rule | `calcRoute` 删除 buildChain，输出精简为终审节点+边界+标记位；`RouteChains` 降级为在途单兼容工具（只读旧冻结链），新链路不再使用 |
| ccr-approval | `approve` 重写为适配器：守卫/调价/齐套判定保留，推进改调引擎；删除手写 `nextNode` 推进；escalate 语义由引擎出线承接 |
| ccr-application | submit 冻结 flow_key+flow_version；双轨分流（见 §8） |
| ccr-vote / ccr-resolution | 触发点改为引擎监听器回调，业务逻辑不变 |
| ccr-admin | 流程设计配置 API（定义 CRUD/送审/发布/停用/审计查询） |
| frontend | 流程设计页、节点字典接口化、路由预览改读流程定义 |
| 单测 | 路由/审批/表决测试全量更新；新增引擎驱动回归套件（成功路径/调价重算/齐套/上会/否决/超时计票/双轨混跑） |

## 7. 关键设计取舍

1. **终审判定保留在路由层，引擎只走线**。把"利率 vs 边界"判定也搬进引擎条件表达式会让矩阵语义两处维护，评审口径会乱。冻结终审节点 + 引擎条件跳转是两者职责最清的切法。
2. **整单齐套保留在业务层**。Warm-Flow 不理解"同申请分项齐套"，硬塞进引擎（如每分项独立实例 + 引擎内等待）会把现在的整单否决/分流语义搞复杂。引擎推节点，业务判齐套。
3. **实例粒度维持分项级**。与现状 businessId=分项编号一致，轨迹、表决批次、计票全部不用重建；整单体验靠业务层聚合（现状即如此）。
4. **治理包装独立于 Warm-Flow**。Warm-Flow 只有发布/未发布两态，双人复核/驳回/审计由 `ccr_flow_publish` + 现有配置治理体系承接，与矩阵/LPR 同一玩法。

## 8. 在途兼容与双轨切换

- 分项冻结字段同时存在新旧两套：老单有 `route_chain` 无 `flow_version` → 走旧代码路径（`RouteChains` + 手写推进保留为 legacy 分支）；新单有 `flow_version` → 走引擎路径。
- 分流点收敛在一个工厂方法（按分项冻结字段判定），禁止散落 if-else。
- 双轨期观察指标：在途老单数量。老单清零后，发一个清理包删除 legacy 路径与 `RouteChains`。
- 回退方案：引擎路径出阻断性缺陷时，配置开关（`ccr.flow.engine-enabled=false`）让**新提交**也回退旧路径；已走引擎的在途单继续走引擎（状态已在引擎侧，不可回退）——这是本方案最大的操作风险点，发布前必须演练。

## 9. 风险登记

| # | 风险 | 缓解 |
|---|---|---|
| R1 | Warm-Flow 1.7.4 的任意节点跳转（调价重算后 skipToNode）、跳转线条件表达式能力边界未验证 | P0 做 POC：定义 CRUD、条件出线、skipToNode、监听器回调、版本共存，五项全过才进入 P1 |
| R2 | 双轨期两条推进路径并存，缺陷定位成本翻倍 | 分流收敛单点；双轨混跑纳入回归套件；在途清零前冻结旧路径任何改动 |
| R3 | 表决/行长触发点从业务代码搬到引擎监听器，回调时序（同事务/异步）影响一致性 | 监听器同步同事务执行；表决建批失败必须阻断流转并告警；补中断恢复用例 |
| R4 | 在途引擎单不可回退 | 发布前演练回退开关；灰度策略：先单个支行/单个产品链路 flow_key 试点 |
| R5 | 测试矩阵爆炸（链可任意配置后，组合数失控） | 回归套件按"节点类型 × 动作"参数化，不穷举拓扑；路由试算页作为配置人自检工具 |

## 10. 分期计划

| 期 | 内容 | 出口标准 | 粗估 |
|---|---|---|---|
| P0 POC | §9 R1 五项能力验证 + 一条 demo 定义跑通提交→审批→上会→行长 | POC 报告，Go/No-Go | 1–2 周 |
| P1 | 贷款主链引擎化（NORMAL/VOTE/PRESIDENT 节点 + 终审截断 + 双轨分流）+ 引擎回归套件 | 测试栈全绿 + 双轨混跑用例通过 | 4–6 周 |
| P2 | 存款链 + 秘书岗条件节点（阈值配置化）+ 产品链路 flow_key 自定义流程 | 两条存量业务链全量回归通过 | 2–3 周 |
| P3 | 流程设计配置页 + 治理生命周期（双人复核/审计/缓存）+ 前端节点字典接口化 | 管理员零代码新增一个节点并走完全流程 | 3–4 周 |
| P4 | 在途老单清零后下线 legacy 路径、清理 `RouteChains` 与旧初始化器 | 旧代码删除，单轨运行 | 1 周 |

P1–P3 合计约 9–13 周（含测试），前提是 P0 验证全部通过。

## 11. 验证方案

- 单测：路由精简输出、引擎监听器状态回写、齐套判定、双轨分流工厂。
- 集成（测试栈 `ccrsys-test`）：新链路端到端（提交→逐级→上会→行长→决议）；调价重算跳转；秘书岗条件命中/跳过；超时强制计票；整单否决连带。
- 双轨混跑：同一环境老单走旧链、新单走引擎，互不影响。
- 发布演练：增量 SQL 幂等重跑、回退开关演练、缓存失效确认。
- 按 AGENTS.md 门槛：每期 `./dev verify` + `./dev app-up` + `./dev smoke` 全绿才交付。
