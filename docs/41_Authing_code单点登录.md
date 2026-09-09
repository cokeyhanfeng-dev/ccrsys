# Authing code 单点登录接入

## 流程与本地权限

电脑端入口支持 `https://ccr.example/?code=<一次性授权码>`、`/login?code=...` 和业务深链携码；兼容读取 hash 查询中的 code。页面在创建路由前提取并删除 code，清除旧身份，等待 `POST /auth/code-login` 完成后再挂载业务页面。重复、空白、超长及冲突 code 直接拒绝，失败展示登录页固定提示，不自动重试或复用旧账号。

后端依次执行：code 兑换平台 token → token 查询平台用户信息 → 按账号精确匹配本地 `ccr_sys_user.username`（保留工号前导零）→ 检查未删除且 ENABLE → 复用现有 Sa-Token 会话建立与登录审计。返回沿用 `R<{token,userInfo}>`，角色、机构、兼岗和数据权限取本地配置，不自动建用户或导入平台权限。平台 token 仅用于服务端身份查询，浏览器仅保存 CCRSYS token。

code 登录保存 `ccr_token`、`ccr_user_info` 到 localStorage；密码登录继续使用 sessionStorage。切换登录清理两种旧缓存；退出及 401 清理两种缓存。接口、导出、附件预览统一读取当前令牌。会话仍由后端控制，继续遵循 2 小时无操作失效。新入口为电脑端能力，移动 OA/有度入口保持原有流程。

## 请求契约（依据网关记录校正）

依据补充的网关查询串与请求体记录：兑换令牌使用 **GET 查询参数**，查询用户信息使用 **POST JSON**；两步均携带已有 `X-App-Id`、`apikey`（头名称可配置）以及独立生成的 `X-Sequence-No`、`X-Timestamp`。`appCode` 按用户要求复用账号密码 `/authing/login` 的同一个 `AuthIntegrationProperties.appCode`（环境变量 `CCR_INTEGRATION_AUTH_APP_CODE`），不采用参考记录中其他业务系统的应用编码。

| 调用 | 方法 | 参数 |
|---|---|---|
| `CCR_INTEGRATION_AUTH_CODE_TOKEN_URL` | GET | `?code=<URL 编码后的授权码>&appCode=<URL 编码后的本应用编码>`，无请求体 |
| `CCR_INTEGRATION_AUTH_USER_INFO_URL` | POST JSON | `{ "appCode": "<本应用编码>", "token": "<上一步平台令牌>" }` |

前端只向本系统提交 `{ "code": "..." }`，不接受客户端指定网关地址、应用编码或账号。超时、重定向、非 HTTP 200、业务失败码、非 JSON、字段缺失或字段类型错误均拒绝登录；响应读取上限 256 KiB。无兑换重试、无 code/token 缓存。平台必须保证 code 绑定本应用、有效期及一次性消费，并在并发兑换时只允许一次成功；本地不将缓存作为验票事实来源。

**响应结构已依据用户提供的网关记录确认，以下为无真实凭证的示例；尚未进行真实平台在线联调：**

```json
{ "msg": "交易成功", "code": "200", "data": "<平台令牌>" }
```

```json
{ "msg": "交易成功", "code": "200", "data": { "userBasicInfo": { "username": "<本地账号或工号>" } } }
```

默认从 `data` 读取平台 token，从 `data.userBasicInfo.username` 读取本地账号，成功码为字符串 `"200"`，同时兼容数值 `200`。`name`、`userId`、`customData.cmisUserId` 不作为登录账号；网关返回的部门与角色不覆盖本地授权。成功码与数据字段路径仍可配置，但默认值已与本次记录一致。两步均要求存在配置的成功码，禁止只凭 HTTP 200 放行。

两个新接口地址均要求部署侧填入最终完整 URL，代码无默认内网地址。启用时配置实际完整 URL，并在真实平台验收 code 的有效期、应用绑定与一次性消费约束。未配置或未启用时 code 登录拒绝，不影响原账号密码入口。

## 部署配置

沿用 `CCR_INTEGRATION_AUTH_ENABLED=true`、`APP_ID`、`API_KEY`、`APP_CODE` 及认证头配置，新增：

```dotenv
CCR_INTEGRATION_AUTH_CODE_ENABLED=true
CCR_INTEGRATION_AUTH_CODE_TOKEN_URL=https://auth.internal.example/authing/auth/getAccessTokenByCode
CCR_INTEGRATION_AUTH_USER_INFO_URL=https://auth.internal.example/authing/auth/getUserBasicAndPolicyInfo
CCR_INTEGRATION_AUTH_CODE_TOKEN_PATH=data
CCR_INTEGRATION_AUTH_USER_NAME_PATH=data.userBasicInfo.username
CCR_INTEGRATION_AUTH_RESPONSE_CODE_PATH=code
CCR_INTEGRATION_AUTH_RESPONSE_SUCCESS_CODE=200
```

新能力默认关闭，地址默认空。若部署环境文件已配置上一版字段路径，需要同步更新为 `CODE_TOKEN_PATH=data` 和 `USER_NAME_PATH=data.userBasicInfo.username`，或删除这两个覆盖值以使用新默认值。生产和测试 Compose 均透传；凭证仅存部署环境文件，不进入浏览器、日志、仓库或镜像。前端 HTML 设置 no-referrer，仓库 Nginx 的 PC 访问日志去掉查询串与 Referer；前置网关也需按同样原则处理回调日志。发布需同时更新前后端及 Nginx 配置。无需 SQL、端口调整或重建数据卷。

## 回归与联调验收

- 后端 `AuthingCodeIdentityServiceTest`：真实本机 HTTP 模拟网关验证 GET 查询编码和空请求体、POST JSON、共享 appCode、网关头与两步顺序，覆盖字符串及数值成功码；覆盖成功、字段配置、状态失败、空/重复/过期 code、非 JSON、重定向、过大响应、本地未开户/停用/删除/账号不一致，不透传平台角色。
- 后端 `CodeLoginControllerTest`：只在身份验证成功后签发 CCRSYS 令牌，维持用户信息契约，失败不建会话。
- 前端 `tests/auth.test.mjs`：8 项行为回归，覆盖 URL 解析/清理、重复参数、等待认证、刷新不重兑、失败清理、两种存储兼容和缓存异常。`./dev frontend-test` / `./dev verify` 自动包含。
- 实机联调：用平台新 code 携码打开根入口和审批深链，确认账号、机构、权限；刷新、下载及附件预览正常；已消费/过期 code 再进应失败且不保留旧身份；无 code 时密码登录正常；退出及超时后需重新登录。
- 使用两个浏览器并发消费同一 code，验收平台仅允许一次兑换成功；使用未开户、停用账号和无权限业务深链验证拒绝访问。不得将真实 code/token 写入验收截图或工单。

实际本地验证记录见开发改动记录本日期条目；模拟网关测试与隔离测试栈冒烟不能替代内网 Authing 联调。
