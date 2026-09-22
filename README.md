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
├── compose.test.yml          # 隔离测试栈(MySQL + Redis + 后端 + 前端)
├── dev                       # 项目环境、开发、测试统一入口
└── docker-compose.yml        # 原有部署编排
```

## 隔离环境快速开始

Windows 同事可在项目根目录运行 `release.cmd 1`（后端）、`release.cmd 2`（前端）、`release.cmd 12`（后端+电脑端），也可双击后选择。首次使用请按 [Windows 打包说明](docs/31_离线增量部署手册.md#30a-windows-开发机打包) 准备工具；脚本不安装工具或修改系统配置。

```bash
./dev setup
./dev versions
./dev app-up
./dev smoke
```

生产轻量更新包可按需构建：`./dev release 1` 仅后端、`./dev release 2` 仅前端、`./dev release 12` 同时打包；不传数字时可交互选择。产物及服务器部署脚本位于 `release/`，详见 [`docs/31_离线增量部署手册.md`](docs/31_离线增量部署手册.md)。

| 组件 | 地址 |
|---|---|
| 测试前端 | http://127.0.0.1:13000 |
| 测试后端 | http://127.0.0.1:18080 |
| 测试 MySQL | 127.0.0.1:23306(库 ccr_rate,root/root123) |
| 测试 Redis | 127.0.0.1:26379 |

测试栈由 Compose 项目 `ccrsys-test` 独立管理，端口只绑定本机回环地址。首次启动 MySQL 会按文件名顺序执行 `db/*.sql`。完整说明见 [`docs/11_开发与测试环境.md`](docs/11_开发与测试环境.md)。

## 本地开发

只启动容器内 MySQL、Redis：

```bash
./dev infra-up
```

分别启动本地后端和前端：

```bash
./dev backend-run
./dev frontend-run
```

JDK 17 位于项目 `.tools/`；Maven、npm 缓存位于项目 `.cache/`；兼容的宿主机 Node/Maven 会直接复用。MySQL、Redis 只在 Docker 中运行。

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

## 移动审批

正式移动入口位于 `frontend/mobile`，沿用已确认 UI，独立构建、Nginx 直连 CCR，支持 OA 票据与有度免密登录，仅审批人员可访问。`./dev mobile-run` 启动 `http://127.0.0.1:13001/mobile/`；`./dev mobile-build` 输出 `frontend/dist-mobile`。完整测试栈入口为 `http://127.0.0.1:13000/mobile/`。OA/有度默认关闭，需由部署环境分别注入验票配置后联调；普通浏览器不提供密码登录。详见 [移动审批实现与联调](docs/40_移动审批实现与联调.md)。

上线前执行 `./dev mobile-api-test`（真实 HTTP/隔离数据库，外部验票用回环夹具），以及 `./dev app-up && ./dev mobile-nginx-test && ./dev smoke`。两个 Maven 验证命令应顺序执行，避免同时编译同一 `target`。当前结论及生产待验项见 [移动端上线前校验](docs/43_移动端上线前校验_20260912.md)。

## Authing code 单点登录

电脑端支持平台携 `code` 进入，后端兑换令牌并核验本地账号后建立 CCRSYS 会话，原账号密码登录继续可用。新能力默认关闭，需配置两个网关 URL；已按最新网关接入两步 GET：直接读取令牌文本，再按 `loginUserId` 匹配账号；免密独立使用 `CCR_INTEGRATION_AUTH_CODE_APP_CODE=rate-approval`；配置、回归和联调边界见 [Authing code 接入说明](docs/41_Authing_code单点登录.md)。

## 企业微信节点提醒

审批节点到达、六人表决建批/替补及转行长决策已接入异步提醒，保留站内信。部署环境配置 `CCR_INTEGRATION_WECHAT_ENABLED=true` 与 `CCR_INTEGRATION_WECHAT_URL`，网关凭证复用统一认证。默认关闭，接收账号按本地登录名（绩效码）解析。契约、重试及联调边界见 [企业微信节点提醒](docs/42_企业微信节点提醒.md)。

Authing 密码认证、code 登录与企业微信提醒的合并 `.env` / Compose 模板见 [部署模板](docs/deployment/README.md)，填入现有凭证和实际网关地址后合并到服务器配置。

## 2026-09-16 移动端独立发布（8090）

发布模式统一为 1 后端、2 电脑端、3 移动端；支持 12/13/23/123 组合，原 12 含义保持后端+电脑端。
Mac/Linux：`./dev release 13` 首次打包后端+移动端，`./dev release 3` 仅移动端；Windows 对应 `release.cmd 13` / `release.cmd 3`。
后端默认运行测试（macOS 原跳过测试行为已取消）。移动产物进入包内 `frontend/dist-mobile`，配置模板进入 `deployment`。
上传发布包和 `release/deploy-release.sh` 后，后端机运行 `bash /tmp/deploy-release.sh 1 /tmp/实际包.tar.gz`，移动站点机运行 `bash /tmp/deploy-release.sh 3 /tmp/实际包.tar.gz`。
异机部署分别执行，不在两台机器上都执行组合模式。移动默认目录 `/data/ccr/mobile`，默认健康入口 `http://127.0.0.1:8090/mobile/`；可通过 `CCR_MOBILE_DIR` / `CCR_MOBILE_HEALTH_URL` 覆盖。
移动发布要求显式指定包，并校验 HTML/JS 与包一致及匿名 session 401；失败恢复旧移动文件。电脑目录和移动/备份目录禁止重叠；同时发布通过目录锁拒绝。
完整配置、首次上线、后端认证和回退步骤见 [8090 发布手册](docs/deployment/mobile/README.md)。
`./dev release-test` 用 Python 3 标准库与回环 HTTP 验证发布脚本（构建为合成夹具）；`./dev mobile-nginx-test 8090` 在现有 ccrsys-test 前端容器内验证实际8090站点配置，临时监听仅容器内，不新增宿主机端口。

安装脚本显示阶段进度条，明细保留在独立安装日志；完成或失败时均列出安装包全名、完整路径、实际备份文件路径及日志查看命令。详见 `docs/deployment/mobile/README.md`。

### 自动选包与部署确认（2026-09-16）

所有部署模式均可省略包路径，例如 `bash /tmp/deploy-release.sh 3`。脚本从 `/tmp`（可用 `CCR_RELEASE_DIR` 覆盖）按文件修改时间选取包含本次全部所选组件的最新包，以包内实际产物判断，跳过不匹配或不可读的归档；完整性校验失败仍终止，不自动换包继续。显式传入路径则使用该包。

无论自动或显式选包，均先展示包全名、绝对路径、大小、部署组件和目标目录/容器，输入 `y` 或 `yes` 后才开始解压校验、备份和替换；回车、其他输入或 EOF 均取消，不修改服务文件。若需切换旧版包，取消后带指定路径重新执行。新增端口/挂载仍须先配置 Nginx。


## 在线用户清单

管理员可在 PC「在线用户」中查询有效登录会话，支持人员、机构、终端筛选及人数/会话数统计。PC 主动退出同步注销后端当前会话，无需新增 SQL。在线口径、兼容性及验证边界见 [在线用户清单](docs/44_在线用户清单.md)。
