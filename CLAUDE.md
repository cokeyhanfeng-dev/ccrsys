# CCR 工程开发约定（Claude Code 每个会话自动加载）

## 项目定位

在现有 `G:\project\ccr` 工程上**依据已有模块修订**（Spring Boot 3.3 / JDK 17 / MyBatis-Plus / Sa-Token / Warm-Flow / Vue 3 + Element Plus / MySQL 8）。**已有大量实现（10 模块 + 前端已验证构建）**：

- 任何改动**先 Grep/Glob 定位现有类/表/页面**，在现有模块上扩展/修订。
- **禁止**新建同义模块、**禁止**重写已验证功能、**禁止**抛开现有实现按文档从头写。
- 新增表/字段/接口优先考虑在现有表/模块上增量扩展（如 `ccr_rate_matrix` 增 `enterprise_type` 维度，而非另建路由表）。

## 核心文档（唯一契约源 —— 改设计先改这里，再改代码）

| 文档 | 路径 | 作用 |
|---|---|---|
| 详设（为准） | `G:\project\客户贡献度与利率决策系统详细设计文档.md` | 表结构/API/规则/UI 全部设计 |
| PRD | `G:\project\客户利率审批系统PRD_V2.md` | 业务需求/决议 D1-D21 |
| 任务拆分 | `G:\project\客户贡献度与利率决策系统_开发任务拆分.md` | T0.x-T9.x 全集 |
| 存量盘点 | `G:\project\tasks\00b_存量代码盘点_增量任务清单.md` | 已有/补齐/缺失状态 |
| 本泳道任务 | `G:\project\tasks\lane-XX_*.md` | 你的泳道任务清单 |
| 协作日志 | `G:\project\tasks\_变更日志.md` | **开工前必读**：别人改了什么/需对齐什么 |

## 分支与工作区

- 每个泳道独立分支 `lane-XX`（从 `main` 切出）；建议用 git worktree 物理隔离工作区（`git worktree add ../ccr-laneXX lane-XX`）。
- 只提交自己泳道的文件；合入顺序 **lane-01 → lane-02 → lane-03 → lane-04/05 → lane-06**。

## 文件所有权（防冲突 —— 谁的领域谁改）

| 文件 / 目录 | 唯一 Owner | 说明 |
|---|---|---|
| `db/*.sql` | **后端 B** | 增量 DDL 集中出；别人加表 → 提需求给 B |
| `backend/pom.xml` + `backend/ccr-common/**` | **后端 A** | 公共基础设施，改一处影响全工程 |
| `backend/ccr-rule/**`、申请提交、贡献度服务 | **后端 C** | 路由/提交/Outbox/贡献度 |
| `backend/ccr-admin/system/**` | **后端 B** | 机构/数据权限/人员指派 |
| `frontend/src/views/application`、`approval` | **前端 D/E 按页面分区** | 两人不碰同一 .vue |
| `backend/*/src/test/**` | **测试 F** | 改被测逻辑时与 F 协商 |

**需要别人的文件 → 不在本地改，提需求单给 owner**，并在 `_变更日志.md` 登记"待对齐"。

## 契约约定

- 新接口/DTO：先在详设 §11 定签名，依赖方先用 mock，提交标注"依赖未就绪"。
- 表结构/新表：统一由后端 B 出 DDL，其他泳道**读 DDL 写代码**，不自行加列。
- 契约变更（接口签名/表结构/字段语义）必须回写详设与任务拆分。

## 提交规范

- 每任务完成：`git commit` + 勾选任务 `[ ]` + 在 `_变更日志.md` **追加一行**。
- 提交信息标注泳道与任务（如 `lane-03: T2.3 路由分档`）。

## 技术栈要点

- 公共返回 `R / BaseEntity / ErrorCode`；错误码分段（§11.1）。
- Sa-Token 会话**活跃超时 2 小时**（`activeTimeout=7200`，无操作自动退出，§5.1）。
- 外部数据走落地表直接读表（数仓推送），不经接口。
