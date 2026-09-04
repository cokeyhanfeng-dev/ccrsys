# Mini-App-Plus 授信决议 API 网关集成

## 1. 调用链路

贷款申请在“利率申请”环节展示客户或集团的最新有效授信决议，在“承诺与材料”环节自动把决议文件转存为 CCRSYS 申请附件。

```text
CCRSYS 后端
  ├─ POST API网关令牌接口（apikey + secret + 当前登录人绩效码）→ 获取该人员的 Mini-App-Plus Sa-Token
  ├─ GET  API网关最新决议路由（Bearer Token）→ Mini-App-Plus
  └─ POST API网关文件兑换路由（Bearer Token）→ Mini-App-Plus → 私有 MinIO 300秒短链
                                                      ↓
                                 CCRSYS 服务端下载并保存附件内容
```

- Magic API 不再参与查询、鉴权或文件兑换。
- CCRSYS 不直连 Mini-App-Plus 数据库，也不保存 MinIO AccessKey/SecretKey。
- API 网关令牌路由转发到 Mini-App-Plus `POST /miniapp/creditResolution/ccr/token`。Mini-App 应用侧再次校验同一组 API Key、Secret、时间戳和流水号，再按绩效码查找启用用户并校验 `ccr_service` 角色。
- 浏览器只接收决议和文件元数据；API Key、Secret、Bearer Token、对象键和短期签名 URL均留在服务端。
- 集团查询使用 `customerType=3` 和 CCRSYS `groupNo`，该值对应 Mini-App-Plus `customer_id/groupId`。

## 2. API 网关注册清单

以下为网关对 CCRSYS 暴露的路径。网关内部目标地址按实际部署服务名配置。

### 2.1 服务令牌 `/auth/token`

- **方法**: POST
- **接口名**: CCRSYS-Mini-App-Plus服务令牌
- **路径**: `/auth/token`
- **完整路径**: `<API网关根地址>/auth/token`
- **网关目标**: `POST <Mini-App-Plus>/miniapp/creditResolution/ccr/token`
- **请求头**: `apikey`, `secret`, `X-Sequence-No`, `X-Timestamp`
- **请求体**: `{"performanceCode":"<CCRSYS当前登录用户名/绩效码>"}`

成功响应需要由网关规范化为：

```json
{
  "code": 200,
  "data": {
    "token": "<Mini-App-Plus可识别的Sa-Token>",
    "expiresIn": 18000
  }
}
```

CCRSYS 同时兼容 `accessToken`、`access_token` 和 `expires_in` 字段。令牌接口未提供有效期时，使用配置项 `token-fallback-ttl-seconds`；默认 14400 秒。CCR 按绩效码分别缓存令牌，到期前 60 秒主动刷新，不将令牌写入 Redis 或数据库，不同人员不会共用 Token。

Mini-App-Plus 中新建角色标识 `ccr_service`，将允许从 CCRSYS 查询授信决议的人员加入该角色。绩效码对应 `sys_user.user_name`，用户必须启用；Token 兑换不传人员密码和验证码。API Key/Secret 同时配置在 CCRSYS、API 网关和 Mini-App-Plus，网关必须透传四个鉴权头；随机 Secret 至少 16 位，生产建议 32 字节以上，并支持双凭证轮换。

### 2.2 最新有效授信决议 `/miniapp/creditResolution/ccr/latest`

- **方法**: GET
- **接口名**: CCRSYS-查询客户最新有效授信决议
- **完整路径**: `<API网关根地址>/miniapp/creditResolution/ccr/latest`
- **网关目标**: `GET <Mini-App-Plus>/miniapp/creditResolution/ccr/latest`
- **鉴权**: `Authorization: Bearer <服务令牌>`，Mini-App-Plus 校验 `ccr_service` 角色
- **入参**: `customerType`, `customerId`
- **Java 门面**: `GET /ccr/external-credit-resolutions/latest`

Mini-App-Plus 按以下条件精确查询一条：

```sql
WHERE customer_type = :customerType
  AND customer_id = :customerId
  AND status = 1
ORDER BY upload_time DESC, id DESC
LIMIT 1
```

成功响应：

```json
{
  "code": 200,
  "data": {
    "resolutionId": 91,
    "resolutionNo": "SX-2026-0091",
    "customerType": 3,
    "customerId": "GROUP001",
    "customerName": "示例集团",
    "versionNo": 2,
    "uploadTime": "2026-09-02 09:30:00",
    "files": [
      {
        "fileId": "801",
        "fileName": "授信决议.pdf",
        "contentType": "application/pdf"
      }
    ]
  }
}
```

只返回 `service=private` 且属于该决议的文件元数据，不返回 `sys_oss.url`、对象键或签名 URL。未查到决议时 `data=null`。

### 2.3 私有文件兑换 `/miniapp/creditResolution/ccr/files/exchange`

- **方法**: POST
- **接口名**: CCRSYS-兑换授信决议私有文件地址
- **完整路径**: `<API网关根地址>/miniapp/creditResolution/ccr/files/exchange`
- **网关目标**: `POST <Mini-App-Plus>/miniapp/creditResolution/ccr/files/exchange`
- **鉴权**: `Authorization: Bearer <服务令牌>`，Mini-App-Plus 校验 `ccr_service` 角色
- **Java 门面**: `POST /ccr/external-credit-resolutions/applications/{applicationId}/import-latest`

请求体：

```json
{"resolutionId": 91, "fileId": "801"}
```

Mini-App-Plus 依次校验决议存在且有效、`fileId` 精确包含在该决议 `file_ids` 中、`sys_oss.service=private`，随后调用 `ISysOssService.getPrivateById(fileId)` 生成 300 秒短链。

```json
{
  "code": 200,
  "data": {
    "downloadUrl": "https://minio.internal.example/...签名参数...",
    "fileName": "授信决议.pdf",
    "contentType": "application/pdf",
    "expiresAt": "2026-09-02 15:35:45"
  }
}
```

CCRSYS 只在服务端使用短链，校验 HTTP(S) 协议和主机允许清单，拒绝重定向，下载后立即将文件内容写入现有申请附件。短链不进入浏览器或数据库。

## 3. 网关策略

三个路由均限制来源为 CCRSYS，并校验 API Key、时间戳窗口和流水号防重放。后两个路由透传 `Authorization` 给 Mini-App-Plus。建议策略：

- `X-Timestamp` 只接受网关当前时间前后 5 分钟。
- `X-Sequence-No` 在时间窗口内只能使用一次。
- 每个 API Key 设置限流、来源 IP 白名单和独立审计字段。
- 日志只记录应用标识、路由、状态码、耗时和脱敏流水号，不记录 Secret、Token、请求签名或下载 URL。
- Token 失效时返回 HTTP 401 或业务码 401；CCRSYS 会重新登录并仅重试一次。

## 4. CCRSYS 部署配置

```bash
CCR_INTEGRATION_CREDIT_RESOLUTION_ENABLED=true
CCR_INTEGRATION_CREDIT_RESOLUTION_GATEWAY_BASE_URL=https://api-gateway.internal.example
CCR_INTEGRATION_CREDIT_RESOLUTION_TOKEN_PATH=/auth/token
CCR_INTEGRATION_CREDIT_RESOLUTION_LATEST_PATH=/miniapp/creditResolution/ccr/latest
CCR_INTEGRATION_CREDIT_RESOLUTION_EXCHANGE_PATH=/miniapp/creditResolution/ccr/files/exchange
CCR_INTEGRATION_CREDIT_RESOLUTION_API_KEY='<网关分配的API Key>'
CCR_INTEGRATION_CREDIT_RESOLUTION_SECRET='<至少16位，生产建议32字节以上随机Secret>'
CCR_INTEGRATION_CREDIT_RESOLUTION_ALLOWED_DOWNLOAD_HOSTS=minio.internal.example
```

Mini-App-Plus 部署环境还需配置同一组网关凭证：

```bash
MINIAPP_CCR_GATEWAY_AUTH_ENABLED=true
MINIAPP_CCR_GATEWAY_API_KEY='<与CCRSYS及网关登记值一致>'
MINIAPP_CCR_GATEWAY_SECRET='<与CCRSYS及网关登记值一致>'
```

如网关使用不同的凭证头名称，可配置：

```bash
CCR_INTEGRATION_CREDIT_RESOLUTION_API_KEY_HEADER=apikey
CCR_INTEGRATION_CREDIT_RESOLUTION_SECRET_HEADER=secret
```

真实配置放入部署平台的 Secret 管理能力，禁止写入 Git、镜像、Compose 文件、数据库或命令历史。`123456`、`root123`、应用名称、手机号和连续字符均不得作为网关 Secret。

## 5. 失败与幂等

- 集成默认关闭，缺少配置时原申请流程仍可使用，页面显示集成未配置。
- 单文件上限 10MB、单决议最多 10 个文件、总大小上限 30MB。
- 全部文件先下载成功再开启数据库事务；任一文件失败时本次不写入。
- 唯一键 `(application_id, source_type, source_business_id, source_file_id)` 防止重复进入、重复点击和并发导入产生重复附件。
- CCRSYS 对短链执行主机允许清单检查，防止 SSRF；Mini-App-Plus 在每次兑换时重新校验有效决议和文件归属。

## 6. 联调检查

1. 使用已绑定 `ccr_service` 角色的人员绩效码获取令牌；未绑定角色、停用用户、错误凭证和过期时间戳必须被拒绝。
2. 个人、企业、集团各选 3 组，核对客户编号和集团编号映射。
3. 验证最新口径为 `upload_time DESC, id DESC`，只取 `status=1`。
4. 验证伪造 `resolutionId/fileId` 组合、公开桶文件和无效决议均无法兑换。
5. 令牌过期后确认 CCR 自动换取新令牌并成功重试一次；持续 401 时停止重试。
6. 验证签名 URL 在 300 秒后失效，非白名单主机、重定向、空文件和超限文件均被 CCR 拒绝。
7. 验证日志中没有 API Key、Secret、Token、登录请求参数和签名 URL。
8. 重复进入“承诺与材料”时只保留一份来源附件，审批详情和历史档案可正常下载。
9. 使用两个不同绩效码连续查询，确认 Mini-App 登录日志分别记录实际人员，且 CCR Token 缓存互不串用。
