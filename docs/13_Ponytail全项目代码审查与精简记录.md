# Ponytail 全项目代码审查与精简记录

> 日期：2026-08-12
> 分支：`codex/business-system-audit-20260812`
> 范围：后端 287 个生产 Java 文件、30 个测试文件，前端 41 个 Vue/TypeScript 文件，Maven/npm 依赖及项目配置
> 准则：只审查复杂度与过度设计；正确性、安全和性能风险继续由 `docs/12_整体业务系统扫描与优化方案.md` 跟踪。

## 审查结论（按可删规模排序）

1. `delete:` 删除零生产标注的 `@DataScope`、切面、ThreadLocal、SQL handler、注册与专用测试；以已落地的领域服务对象授权和列表过滤为唯一执行路径。[backend/ccr-common/src/main/java/com/ccr/common/datascope] [backend/ccr-admin/src/main/java/com/ccr/admin/config/DataScopeAspect.java]
2. `delete:` 删除零生产标注的缓存注解 AOP 与专用测试；业务代码继续直接调用已使用的 `CcrCacheUtil`。[backend/ccr-common/src/main/java/com/ccr/common/cache/CacheFallbackAdvice.java]
3. `delete:` 删除缓存 v1 废弃的静态匹配/解析、yml 每项配置和兼容属性；DB `ccr_cache_config` 继续作为唯一事实源。[backend/ccr-common/src/main/java/com/ccr/common/cache/CacheItem.java] [backend/ccr-admin/src/main/resources/application.yml]
4. `delete:` 删除未消费的 Actuator starter；项目现有 `/health` 已覆盖容器、冒烟和上线检查入口。[backend/ccr-admin/pom.xml]
5. `delete:` 删除前端四个零引用辅助函数及两组零消费字典。[frontend/src/api/application.ts] [frontend/src/utils/dict.ts]
6. `shrink:` 登录数据范围从未启用的对象/机构计算链缩成角色到 `ALL/DEPT/SELF` 的兼容展示映射；业务授权仍由领域服务执行。[backend/ccr-admin/src/main/java/com/ccr/admin/controller/AuthController.java]
7. `yagni:` 20 个单实现 Service 接口共约 823 行；跨模块端口、策略接口与代理边界应保留，同模块且仅 Controller 消费的接口可在对应领域重构时逐个合并，避免一次性改动全部注入点。[backend/ccr-application/src/main/java/com/ccr/application/service] [backend/ccr-commitment/src/main/java/com/ccr/commitment/service]
8. `shrink:` 启用产品存在管理端与公开端两个等价查询；公开端由申请页消费，管理端端点需完成外部调用审计后合并到目录列表的 `status=ENABLED` 查询。[backend/ccr-admin/src/main/java/com/ccr/admin/system/controller/ProductConfigController.java]
9. `shrink:` `params.vue`、`loan.vue`、`approval/detail.vue` 分别超过 1200 行，包含重复表单、弹窗与状态映射；建议在相关功能下一次变更时提取已有业务块，禁止为拆文件单独发起大改。[frontend/src/views/system/params.vue] [frontend/src/views/application/loan.vue] [frontend/src/views/approval/detail.vue]
10. `yagni:` `CcrNoteGuaranteeRel` 实体与 Mapper 当前没有读写调用；D11 借据担保核验落地时接入，若业务取消该明细则连同表结构和设计条目一起删除。[backend/ccr-application/src/main/java/com/ccr/application/domain/CcrNoteGuaranteeRel.java]

`net: -792 lines, -3 direct dependencies implemented; another -823 interface lines possible after module-boundary review.`

## 保留项说明

- `SnapshotGateway`、`OutboxEventHandler`、`RecipientResolver` 等接口承担跨模块端口或多实现策略职责，继续保留。
- `poi-ooxml` 由 Hutool `ExcelUtil` 间接用于他行融资导入，Maven 字节码分析会将其误报为未使用，继续保留。
- 输入校验、对象权限、状态机、事务、Outbox 幂等、失败关闭和 Redis 降级均不参与本轮删减。

## 验证记录

- `./dev mvn -B -DskipTests compile`：12 个 reactor 项目编译通过。
- `./dev mvn -B clean test`：280 项测试，0 failure、0 error、0 skipped；相较精简前减少的 21 项只覆盖已删除的休眠 SQL 权限链、零使用缓存 AOP 和废弃缓存 API。
- `./dev verify`：后端测试和前端 Vite 生产构建通过，1740 个模块完成转换。
- `./dev app-up && ./dev smoke`：MySQL 97 张表、Redis、后端、登录、前端和 API 代理均通过；测试服务保持运行。
- 真实角色回归：`zhangsan` 读取本人申请业务码 200，`lisi` 跨客户经理读取业务码 403，`wangwu` 同支行读取业务码 200；登录响应范围保持 `admin=ALL/customer_manager=SELF/branch_manager=DEPT`。
- 本轮未修改 DDL，未执行 `./dev reset`，测试数据卷保留。
