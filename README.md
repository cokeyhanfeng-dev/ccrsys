# 客户贡献度与利率决策系统 · 开发工程

> 依据《客户利率审批系统 PRD V2》《客户贡献度与利率决策系统开发设计文档-定稿版V1.0》《ccrd_dw_schema _修订.sql》
> 技术栈: **Spring Boot 3.3 / JDK 17 / MyBatis-Plus / Sa-Token / Vue 3 + Element Plus + design-system / MySQL 8 / Warm-Flow 1.7.4**
> 当前进度: **按 PRD V2 方向纠偏完成**:权限矩阵 LPR±BP 路由、状态机 §7.6、外部表修订版对齐、design-system 前端、Warm-Flow 引擎接入与矩阵路由衔接
> 业务边界:集团部分按定稿版 V1.0 保留;其余按 PRD V2 执行(逐担保类型独立计票、贡献度双概念)

---

## 目录结构

```
ccr/
├── docs/
│   ├── 01_开发可行性分析与任务拆分.md    # 可行性结论 + WBS(里程碑/工作包)
│   └── 02_外部数据支撑表结构与清单.md    # 数仓对接契约(19+ 数据集)
├── db/
│   ├── 01_init.sql           # 建库
│   ├── 02_external_data.sql  # 外部数据落地表(caps_*/dw_*)——数仓契约
│   ├── 03a~03e_*.sql         # 业务系统自建表(ccr_*:申请/表决/快照/承诺/配置)
│   ├── 04_workflow.sql       # 工作流引擎占位(Warm-Flow 自动建表)
│   └── 05_seed.sql           # 业务字典种子
├── backend/                  # Spring Boot 3 多模块(ccr-common / ccr-admin)
├── frontend/                 # Vue 3 + Vite + Element Plus(参照 v3.3-html-demo 页面)
└── docker-compose.yml        # 一键部署(MySQL + 后端 + 前端)
```

## 一键启动(Docker)

```bash
docker compose up -d --build
```

| 组件 | 地址 |
|---|---|
| 前端 | http://localhost:3000 |
| 后端健康检查 | http://localhost:8080/health |
| MySQL | localhost:3306(库 ccr_rate,root/root123) |

首次启动 MySQL 会自动执行 `db/` 下全部 DDL(69 张表 + 字典种子)。

## 本地开发

**后端**(需 JDK 17 + Maven,或复用 Docker):

```bash
cd backend
mvn -B -DskipTests package
java -jar ccr-admin/target/ccr-admin.jar
```

**前端**:

```bash
cd frontend
npm install
npm run dev        # http://localhost:3000,代理 /api → localhost:8080
```

## 数据库验证结果

- 外部数据表: E01–E19 + 批次表 = **20 张**(caps_* 2 + dw_*_snapshot 12 + 征信/贡献度/机构达成 5 + 批次 1)
- 业务表 ccr_*: **49 张**(申请域 13 / 表决 6 / 快照 4 / 承诺跟踪 10 / 配置元数据 16)
- 全部 DDL 在 MySQL 8.0.44 实测通过,种子字典 17 类 / 77 项

## 已验证的端到端链路(2026-08-06)

> 全部后端业务模块已端到端验证:申请域、规则引擎、普通审批、六人表决、行长决策、决议核验、快照冻结、承诺跟踪(详见下方业务链路)。

| 环节 | 结果 |
|---|---|
| 10 个 Maven 模块编译(common/application/rule/approval/vote/resolution/snapshot/commitment/message/admin) | ✅ BUILD SUCCESS |
| MySQL 8 容器 DDL 全量执行 | ✅ 69 表 + 77 字典项 |
| 后端 jar 容器启动连接 MySQL | ✅ 正常 |
| `POST /auth/login` 开发期登录(Sa-Token) | ✅ 返回 token |
| `POST /ccr/applications` 创建草稿 | ✅ 自动申请号/雪花ID/公共字段填充,落库 |
| `GET /ccr/applications/{id}` 查询 | ✅ 完整返回 |
| 前端 `npm run build` | ✅ 通过

## 缓存配置能力(Redis,详设 §十三)

> 需求:Redis 缓存内容可由管理员**自己增加和配置**,如把数仓数据缓存到 Redis。

- **缓存项 DB 动态管理**:缓存项定义存 `ccr_cache_config`,可增删改(精确 key / key 前缀、TTL、写入开关、描述);内置 3 项(lpr/matrix/rate-limit)为种子受保护(不可删、不可改 key);改配置立即生效不重启。
- **配置化刷新**:缓存项可配置数据加载器(第一版:数仓表最新批次 DW_TABLE),把 dw_/caps_ 表数据写入指定 Redis key;手动刷新 + 每小时定时(`ccr.cache.data.refresh-cron` 可配),单项失败不阻断。
- **验证**:`db/14` 幂等升级;单测 ccr-common 65 / ccr-rule 30 / ccr-application 43 全绿;E2E 22 项通过(新增/刷新写入数仓数据/内置项保护 400/物理删除后同 itemKey 重建/编辑生效)。

## 下一步(按 WBS)

| 里程碑 | 工作包 | 说明 |
|---|---|---|
| M3 核心领域 | T06/T07 | 申请域实体/Mapper/Service + 状态机落地 |
| M4 规则路由 | T08/T09 | 规则版本、路由计算、边界校验 |
| M5 审批表决 | T10–T13 | Warm-Flow 接入、六人表决、行长决策、决议核验 |
| M6 快照提交 | T14/T15 | 质量校验、不可变快照、提交事务 |
| M7 承诺消息 | T16–T18 | 履约定时任务、策略、通知 |
| M8 PC 前端 | T19–T21 | 申请/审批/管理页面接真实接口 |

> 外部数据 `dw_*_snapshot` 由数仓产出,开发期通过 `ccr.external.mock-enabled=true` 走本地 mock,不阻塞业务开发。
