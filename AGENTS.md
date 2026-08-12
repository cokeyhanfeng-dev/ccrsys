# CCRSYS 项目开发约束

## 语言与协作

- 使用中文沟通、注释和交付说明，代码标识符遵循现有英文命名。
- 禁止使用“不是 xxx，而是 xxx”句式。
- 开始任务前先阅读本文件、`README.md`、相关 `docs/` 文档和目标模块代码。
- 当前仓库可能存在用户未提交改动；只修改任务范围内文件，保留所有无关改动。

## 技术与业务边界

- 技术基线：JDK 17、Spring Boot 3.3、Maven 3.9、MyBatis-Plus、Sa-Token、Warm-Flow、Vue 3、Vite、Element Plus、MySQL 8、Redis 7。
- 后端是模块化单体，启动模块为 `ccr-admin`。在现有 `ccr-common/application/rule/approval/vote/resolution/snapshot/commitment/message/workflow/admin` 模块内增量修改。
- 业务主链路为：申请 → 快照冻结与规则路由 → 工作流与审批 → 六人表决 → 决议 → 承诺履约 → 通知。
- 需求与契约优先参考 `docs/客户利率审批系统PRD_V2.md`、`docs/客户贡献度与利率决策系统详细设计文档.md`、`docs/05_开发改动记录.md`。
- 接口、表结构、字段语义、状态机、权限口径发生变化时，同步更新相关设计文档、测试方案和开发改动记录。
- 延续统一返回 `R`、统一异常 `ServiceException/ErrorCode`、公共实体字段和现有字典编码。

## 环境隔离

- 所有项目命令优先通过根目录 `./dev` 执行。环境说明见 `docs/11_开发与测试环境.md`。
- 禁止用 Homebrew、SDKMAN、nvm、npm `-g`、系统安装器或管理员权限修改宿主机开发环境。
- 禁止修改全局 `JAVA_HOME`、全局 `PATH`、`~/.m2`、全局 npm 配置或 Docker Desktop 全局设置。
- JDK 等项目工具只能安装到 `.tools/`；Maven/npm 缓存只能写入 `.cache/`；这两个目录禁止提交。
- 本机 Node 版本满足 Node 20+ 时直接复用。本机 Maven 满足 3.9.x 时直接复用，并强制通过 `./dev` 把依赖缓存定向到项目目录。
- MySQL、Redis 必须运行在 `compose.test.yml` 管理的 Docker 容器中。禁止在宿主机安装或启动 MySQL、Redis。
- 完整测试栈固定使用 Compose 项目 `ccrsys-test`，端口只绑定 `127.0.0.1`：前端 13000、后端 18080、MySQL 23306、Redis 26379。
- 测试容器不得设置自动重启策略；通过 `./dev infra-up` 或 `./dev app-up` 显式启动。
- 不直接操作其他 Compose 项目、容器、网络或数据卷。需要清库时只运行 `./dev reset`，并在交付说明中明确测试数据已重建。

## 常用命令

```bash
./dev setup          # 初始化项目内工具链
./dev versions       # 核对实际版本
./dev infra-up       # 只启动 MySQL/Redis
./dev backend-run    # 本地后端连接 Docker 基础设施
./dev frontend-run   # 本地 Vite 开发服务器
./dev backend-test   # 后端全部单测
./dev frontend-test  # npm ci + 前端生产构建
./dev verify         # 后端单测 + 前端构建
./dev app-up         # 构建并启动完整容器测试栈
./dev smoke          # 数据库/Redis/登录/前端冒烟
./dev app-down       # 停止测试栈并保留数据卷
```

## 编码要求

- 先定位并复用现有实体、Mapper、Service、Controller、API 封装和页面组件，禁止建立同义模块或重复数据模型。
- Controller 负责协议、鉴权和参数校验；复杂业务编排放入 Service。新增接口优先使用明确 DTO 与 Bean Validation，避免扩大 `Map<String, Object>` 使用范围。
- 涉及状态变迁、审批、表决、决议、快照和配置发布时，必须处理事务、幂等、并发和失败恢复。
- 对象详情、保存、提交、导出、附件和历史查询必须校验登录人角色及对象级数据权限。前端菜单隐藏不能作为后端授权依据。
- 外部数据只读；提交时继续保持数据批次校验、快照冻结、LPR/规则版本冻结和路由结果留痕。
- 跨事务副作用优先使用现有 Outbox；新增消费者必须具备业务幂等键、有限重试、终态告警和中断恢复策略。
- 缓存不可作为唯一事实来源。Redis 不可用时遵循现有降级策略，锁与并发正确性要有数据库约束或条件更新兜底。
- 避免继续扩大超大 Controller、Service 和 Vue 单文件；修改大文件时优先提取领域服务、组合式函数或子组件，保持行为兼容。
- 禁止提交密码、token、生产连接信息、真实客户数据、构建产物和本地缓存。仓库内 `root123`、`123456` 仅限隔离测试环境。

## 数据库要求

- `db/*.sql` 按文件名顺序初始化。新增结构变更使用新的递增编号 SQL，保持脚本可重复执行和 MySQL 8 兼容。
- 基础结构、基础种子、模拟外部数据和测试数据要保持可区分，禁止把生产数据写入测试种子。
- 修改 DDL 后必须使用专用卷从零验证：`./dev reset && ./dev app-up && ./dev smoke`。
- 对唯一性、幂等和并发有要求的业务规则，应优先补数据库唯一键、版本条件或明确锁策略，并添加回归测试。

## 验证门槛

- Java 代码改动至少运行 `./dev backend-test`。
- Vue/TypeScript/CSS 改动至少运行 `./dev frontend-test`。
- 同时影响前后端时运行 `./dev verify`。
- 涉及数据库、Redis、登录鉴权、数据权限、工作流、Outbox、定时任务、Docker 或配置时，额外运行 `./dev app-up` 和 `./dev smoke`。
- 修复缺陷必须增加能复现缺陷的回归测试；测试应覆盖成功路径、参数边界、权限拒绝、状态冲突和重复请求中的相关场景。
- 禁止通过跳过测试、降低断言、关闭严格校验或吞掉异常来获得绿色结果。
- 后端测试失败时报告具体模块和用例；前端构建失败时报告首个有效错误。环境原因必须附版本、命令和错误证据。
- 交付前执行 `git status --short`，确认没有 `.tools/`、`.cache/`、`node_modules/`、`dist/`、`target/` 或测试凭据进入提交范围。

## 文档与交付

- 新增开发命令、端口、环境变量、容器或验证步骤时，同步更新 `docs/11_开发与测试环境.md` 和相关 README 内容。
- 最终交付说明应包含：改动摘要、实际执行的验证命令与结果、未执行项及原因、数据卷是否重建、剩余风险。
