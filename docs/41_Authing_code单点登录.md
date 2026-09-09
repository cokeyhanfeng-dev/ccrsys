# Authing code 单点登录接入

## 流程与本地权限

电脑端入口支持 `https://ccr.example/?code=<一次性授权码>`、`/login?code=...` 和业务深链携码；兼容读取 hash 查询中的 code。页面在创建路由前提取并删除 code，清除旧身份，等待 `POST /auth/code-login` 完成后再挂载业务页面。重复、空白、超长及冲突 code 直接拒绝，失败展示登录页固定提示，不自动重试或复用旧账号。

后端依次执行：code 兑换平台 token → token 查询平台用户信息 → 按账号精确匹配本地 `ccr_sys_user.username`（保留工号前导零）→ 检查未删除且 ENABLE → 复用现有 Sa-Token 会话建立与登录审计。返回沿用 `R<{token,userInfo}>`，角色、机构、兼岗和数据权限取本地配置，不自动建用户或导入平台权限。平台 token 仅用于服务端身份查询，浏览器仅保存 CCRSYS token。

code 登录保存 `ccr_token`、`ccr_user_info` 到 localStorage；密码登录继续使用 sessionStorage。切换登录清理两种旧缓存；退出及 401 清理两种缓存。接口、导出、附件预览统一读取当前令牌。会话仍由后端控制，继续遵循 2 小时无操作失效。新入口为电脑端能力，移动 OA/有度入口保持原有流程。

## 请求契约（2026-09-09 最新网关）

两步上游调用均为 **GET，无请求体**，均携带已有 `X-App-Id`、`apikey`（头名称可配置）以及独立生成的 `X-Sequence-No`、`X-Timestamp`。免密专用 `CCR_INTEGRATION_AUTH_CODE_APP_CODE` 默认 `rate-approval`；密码登录继续使用原 `CCR_INTEGRATION_AUTH_APP_CODE`。

| 调用 | 方法与地址 | 响应 |
|---|---|---|
| code 换令牌 | GET `/authing/getAccessTokenByCode?code=<编码后的授权码>&appCode=rate-approval` | 直接返回 JWT 文本，兼容 JSON 字符串，无 code/data 外层 |
| 令牌查用户 | GET `/authing/getLoginUser/rate-approval/<编码后的平台令牌>` | 直接返回包含 loginUserId 的 JSON 对象 |

用户响应示例（全部为虚构数据）：

```json
{
  "loginUserId": "001234",
  "loginUserName": "测试用户",
  "loginRoleId": null,
  "loginDeptId": null,
  "loginDeptName": "测试部门",
  "dataLevel": null,
  "extra": null
}
```

后端只将顶层字符串 `loginUserId` 与本地 username 精确匹配，保留工号前导零；`loginUserName`、`loginDeptName` 和平台角色不参与本地授权。HTTP 200 且有效 JWT 文本兑换成功后，必须继续完成用户查询和本地准入检查；不解析或信任 JWT 中的身份声明。网关不再要求返回 code/data 包装，若用户响应显式携带失败 code、success=false 或非空 error，仍拒绝登录。

浏览器调用本系统的 `POST /auth/code-login` 保持不变，只提交 code，不接受客户端指定网关地址、应用编码或账号。超时、重定向、非 HTTP 200、空令牌、非 JWT 文本、用户响应无效及字段错误均拒绝登录；响应上限 256 KiB，令牌上限 16384 字符，账号上限 100 字符。无兑换重试、无 code/token 缓存。平台须保证 code 的应用绑定、有效期及并发一次性消费。

两个完整 URL 由部署环境提供，代码无默认内网地址。用户地址采用 `{appCode}`、`{token}` 独立路径段模板，后端编码后替换；启用前校验模板，缺失或错误时在兑换 code 前拒绝。平台 token 只用于后端查询，应用不记录完整请求地址、响应或异常，不下发浏览器。前置网关应对查询串中的 code 及路径中的 token 做日志脱敏。

## 部署配置与旧版迁移

沿用现有 `CCR_INTEGRATION_AUTH_ENABLED=true`、`APP_ID`、`API_KEY` 和认证头；原密码登录 `AUTH_URL`、`AUTH_APP_CODE`、企业微信与决议书参数保留。在 `.env` 中配置：

```dotenv
CCR_INTEGRATION_AUTH_CODE_ENABLED=true
CCR_INTEGRATION_AUTH_CODE_APP_CODE=rate-approval
CCR_INTEGRATION_AUTH_CODE_TOKEN_URL=http://gateway.example.invalid:8000/authing/getAccessTokenByCode
CCR_INTEGRATION_AUTH_USER_INFO_URL='http://gateway.example.invalid:8000/authing/getLoginUser/{appCode}/{token}'
```

将示例主机替换为实际网关主机，保留 `{appCode}` 和 `{token}` 原样，无需填写真实 code/token。对应 Compose 的 `services.backend.environment`：

```yaml
CCR_INTEGRATION_AUTH_CODE_ENABLED: "${CCR_INTEGRATION_AUTH_CODE_ENABLED:-false}"
CCR_INTEGRATION_AUTH_CODE_APP_CODE: "${CCR_INTEGRATION_AUTH_CODE_APP_CODE:-rate-approval}"
CCR_INTEGRATION_AUTH_CODE_TOKEN_URL: "${CCR_INTEGRATION_AUTH_CODE_TOKEN_URL:-}"
CCR_INTEGRATION_AUTH_USER_INFO_URL: "${CCR_INTEGRATION_AUTH_USER_INFO_URL:-}"
```

删除旧 `.env` 和 Compose 中的 `CCR_INTEGRATION_AUTH_CODE_TOKEN_PATH`、`CCR_INTEGRATION_AUTH_USER_NAME_PATH`、`CCR_INTEGRATION_AUTH_RESPONSE_CODE_PATH`、`CCR_INTEGRATION_AUTH_RESPONSE_SUCCESS_CODE`，当前实现已移除这些字段。旧版 `/authing/auth/getAccessTokenByCode`、`getUserBasicAndPolicyInfo` 地址不再使用。

根 `docker-compose.yml`、隔离测试 `compose.test.yml` 和 [合并部署模板](deployment/README.md) 已同步。若服务器使用自行维护的 `docker-compose.backend.yml`，将上述条目合并到实际后端服务的 environment，保留其他配置。先发布新版后端镜像或持久挂载 Jar，再按部署说明重新创建后端容器加载环境变量；仅 restart 不会加载。无需 SQL、端口变更或重建数据卷。本次协议修正无需修改前端，沿用已发布的 code 回调入口。

## 回归与联调验收

- 后端 `AuthingCodeIdentityServiceTest`：本机 HTTP 模拟网关验证两次 GET、无请求体、新路径与独立 appCode、查询参数及路径编码、认证头、纯文本/JSON 字符串令牌和顶层 loginUserId；覆盖无效模板、异常响应、重定向、过大响应、超时、重复 code，以及本地未开户/停用/删除/账号不一致，不透传平台角色。
- 后端 `CodeLoginControllerTest`：只在身份验证成功后签发 CCRSYS 令牌，维持用户信息契约，失败不建会话。
- 前端 `tests/auth.test.mjs`：8 项行为回归，覆盖 URL 解析/清理、重复参数、等待认证、刷新不重兑、失败清理、两种存储兼容和缓存异常。`./dev frontend-test` / `./dev verify` 自动包含。
- 实机联调：用平台新 code 携码打开根入口和审批深链，确认账号、机构、权限；刷新、下载及附件预览正常；已消费/过期 code 再进应失败且不保留旧身份；无 code 时密码登录正常；退出及超时后需重新登录。
- 使用两个浏览器并发消费同一 code，验收平台仅允许一次兑换成功；使用未开户、停用账号和无权限业务深链验证拒绝访问。不得将真实 code/token 写入验收截图或工单。

实际本地验证记录见开发改动记录本日期条目；模拟网关测试与隔离测试栈冒烟不能替代内网 Authing 联调。

2026-09-09 部署侧反馈：用户按上述新路径、两步 GET 和 `rate-approval` 配置调整后确认登录成功。此反馈确认成功登录路径；过期/重复 code、并发一次性消费与业务权限等场景仍按上述清单分别验收。
