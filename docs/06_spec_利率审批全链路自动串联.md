# Spec · 利率审批全链路自动串联

> 由 `/to-spec` 基于当前 conversation 与 codebase 综合产出,不访谈用户。
> 项目:客户贡献度与利率决策系统(CCR)/ G:\project\ccr
> 日期:2026-08-06

---

## Problem Statement

客户贡献度与利率决策系统核心业务(申请、权限矩阵路由、审批、六人表决、行长决策、决议、快照、承诺)已分别实现并逐环节验证通过,但**业务链路存在断点**:申请提交、快照冻结、流程发起、决议签发、承诺计划生成等环节相互独立,需要人工分别调用对应 API,无法实现"一次提交 → 端到端自动闭环"。

用户(客户经理/审批人/行长/参数管理员)期望:申请一旦提交,后续的路由、冻结、审批、表决、决策、决议、承诺跟踪自动衔接,审批依据始终使用提交时冻结的快照,且任一环节失败整体可回滚、可重试、可追溯。

## Solution

建立"**提交即串联**"的自动化业务链路,由**统一提交事务**与 **Outbox 可靠异步**驱动,将已实现的各模块编排为端到端闭环:

1. **申请提交事务**(原子):锁定申请版本 → 最新数据校验(质量/时效/勾稽)→ 按权限矩阵冻结 LPR/规则版本 → **创建不可变快照包** → 计算**逐担保分项唯一路由** → 绑定快照与版本 → 状态置 `SUBMITTED→ROUTING`。
2. **Outbox 事件**(可靠异步):流程发起、审批任务生成、消息通知通过 `ccr_outbox_event` 异步执行,消费端按事件编号幂等。
3. **审批流转**:普通节点通过/调价/上送、六人表决、行长决策均回写分项状态,推进 Warm-Flow 实例。
4. **决议自动签发**:行长决策同意后,自动生成决议(`ccr_resolution`)并进入 `ISSUED→CONTRACT_PENDING`。
5. **承诺计划自动生成**:决议签发后,依据冻结的承诺指标自动生成 `ccr_commitment_plan`。
6. **决议执行核验**:合同回填后自动执行两级核验(合同利率=决议利率;借据利率=合同利率),异常自动通知(幂等去重)。

## User Stories

1. As a 客户经理, I want 提交申请后自动完成快照冻结与路由计算, so that 审批依据始终是提交时的数据且无需手工触发。
2. As a 客户经理, I want 提交失败时整单回滚且提示具体阻断项, so that 不会出现"申请已建但快照未绑"的半成品。
3. As a 支行行长, I want 待办任务在提交后自动生成, so that 无需等待人工创建流程。
4. As a 支行行长, I want 权限内通过后分项自动终审、超权限自动上送, so that 审批路径正确无需人工判断。
5. As a 六人小组成员, I want 表决通过后自动进入行长决策, so that 无需人工衔接。
6. As a 总行行长, I want 决策同意后自动签发决议, so that 决议即时可用。
7. As a 总行行长, I want 决策后自动生成承诺计划, so that 承诺跟踪自动开始。
8. As a 合同经办岗, I want 合同回填后自动执行两级核验并在异常时收到通知, so that 利率执行一致性有保障且异常不丢失。
9. As a 参数管理员, I want 规则/LPR/矩阵版本发布后新申请自动采用新版本, so that 版本切换无需改代码。
10. As a 审计人员, I want 全链路自动串联的每一步都有留痕与幂等记录, so that 可追溯、可重放、无重复。

## Implementation Decisions

- **编排层**:新建 `ccr-orchestration`(或并入 ccr-application)的 `ApplicationSubmitService`,作为唯一提交入口,编排 校验→冻结→路由→建快照→置状态;事务 `@Transactional` 保证原子。
- **Outbox 模式**:提交事务内写 `ccr_outbox_event`(事件类型:FLOW_START/NOTIFY/RESOLUTION_CREATE/COMMITMENT_CREATE);异步消费线程按 `event_no` 幂等,失败重试(已有表结构)。
- **路由触发**:复用 `RateMatrixRouterImpl.calcRoute`(支持 `lprVersionId`/`asOfDate` 冻结版本),逐担保分项计算 `route_code` 并回写分项;起始节点写入流程参数。
- **快照冻结**:复用 `SnapshotServiceImpl.submitSnapshot`;提交事务内完成 FROZEN 并绑定 `snapshot_bundle_id`。
- **决议/承诺自动生成**:行长决策(`presidentDecision` 同意)事务内写决议,Outbox 触发承诺计划生成;使用现有 `ResolutionServiceImpl.createResolution` 与 `CommitmentServiceImpl.createPlan`。
- **幂等与版本**:所有写接口沿用 `Idempotency-Key` + `version_no` 乐观锁;决议唯一约束 `pricing_item_id` 防重复。
- **状态机**:沿用 PRD §7.6 `DRAFT→SUBMITTED→ROUTING→(APPROVED_LEVEL→FINAL | VOTING→COMMITTEE_PASS→PRESIDENT_DECISION→FINAL/VETOED)`;提交串联不改变状态语义。
- **外部契约**:沿用 `docs/04_数仓推送表结构清单.md`(10 张表);`dw_loan_note_snapshot` 用于借据核验。

## Testing Decisions

- **好测试定义**:只测外部行为——"提交一个合法申请 → 断言快照已冻结、路由已计算、待办已生成、状态为 ROUTING",不测内部实现细节。
- **覆盖模块**:
  - `ccr-rule`:RateMatrixRouterImpl 路由唯一性/边界/上会(已有 3 用例,补充集团金额档定档)。
  - `ccr-orchestration`:提交事务——合法提交、质量阻断回滚、重复提交幂等。
  - `ccr-resolution`:决议签发幂等、回填七项校验、两级核验(合同/借据利率不一致)。
  - `ccr-vote`:并发一人一票、≥4 票计票、行长决策后自动决议。
- **prior art**:`ccr-rule/src/test/java/com/ccr/rule/RateMatrixRouterImplTest.java`(Mockito 纯单元,3 用例)。

## Out of Scope

- 数仓正式数据替换模拟数据(依赖数仓推送,清单已交付 `docs/04`)。
- SSO 统一认证接入(行内内部工作平台,预留适配)。
- 移动审批端(行内 App 容器)。
- 敏感字段正式加密组件(当前演示数据已脱敏)。
- 行内合规制度的安全/审计导出细化。

## Further Notes

- 关联文档:PRD V2(§7.1 申请步骤、§7.6 状态机、§8.7 提交事务)、定稿版 V1.0(§4.3 事务边界、§9.4 快照)、`docs/05_开发改动记录.md`。
- 现有断点与本次串联的对应:探索发现的"路由/快照/决议/承诺需人工触发"由本 spec 的编排层与 Outbox 统一解决。
- 无 project issue tracker 可发布;本 spec 落盘于 `docs/06_spec_利率审批全链路自动串联.md`,`ready-for-agent` label 待接入 tracker 后应用。
