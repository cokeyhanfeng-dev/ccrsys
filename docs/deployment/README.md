# Authing 与企业微信合并部署模板

本目录只提供配置模板，不会自动修改运行环境或发送消息。

| 文件 | 用途 |
|---|---|
| `authing-wechat.env.example` | 合并到部署目录 `.env` 的实际值模板，包含密码认证、code 登录及企业微信提醒 |
| `docker-compose.authing-wechat.yml` | 现有 `services.backend.environment` 的配置片段；也可作为 Compose 覆盖文件 |

## 填写方式

1. 已有 `.env` 时，将模板变量合并进去，保留数据库、Redis、决议书网关等原有配置；首次配置可将示例复制到部署目录并命名为 `.env`。
2. 将四个 URL 中的 `https://gateway.example.invalid` 替换为相应实际网关地址。如果不同接口使用不同网关，分别填写各自完整 URL。
3. `APP_ID`、`API_KEY`、`APP_CODE` 沿用服务器上已可用的账号密码 Authing 配置。模板中的 `10111` 和应用编码取自当前工程默认值；若服务器现有值不同，以现有可用值为准。API Key 必须替换占位文本；包含 `$`、`#` 等字符时保留单引号。
4. code 登录响应路径使用 `data` 和 `data.userBasicInfo.username`。企业微信只增加开关和发送 URL，共用 Authing 的 APP_ID/API_KEY 与头名称，发送体为系统生成的 toUser/text。
5. 模板按生产启用两项能力填写；隔离测试时将 `CCR_INTEGRATION_WECHAT_ENABLED=false`。当前通过行内网关发送，无需额外配置 CorpID、AgentID 或企微应用 Secret，也无需填写接收人、正文、code、token。

当前根 `docker-compose.yml` 已含全部变量透传。服务器使用旧文件时，将 YAML 模板中的 environment 项合并至现有 backend 服务，保留原有镜像、挂载、数据库、Redis 等配置。`.env` 中的值通过这些条目传入容器，无需再重复配置 `env_file`。

## 加载配置

在服务器现有 Compose 所在目录执行。以下命令会重新创建后端容器，但保留数据卷：

```bash
chmod 600 .env
docker compose --env-file .env config --quiet
docker compose --env-file .env up -d --force-recreate backend
```

后端镜像或持久挂载的 Jar 必须包含 Authing code 和企业微信提醒两项最新代码。如果之前用 `docker cp` 将新版 Jar 放入旧容器，重建容器会丢失该临时替换；需按原发布流程在新容器重新部署新版 Jar，或先更新镜像。单纯 `restart` 不加载修改后的容器环境变量。

模板只含占位地址和凭证，可在开发机执行以下只读检查，不启动容器、不连接网关：

```bash
docker compose --env-file docs/deployment/authing-wechat.env.example \
  -f docker-compose.yml -f docs/deployment/docker-compose.authing-wechat.yml \
  config --quiet
```

参数契约和联调要求分别见 [Authing code 登录](../41_Authing_code单点登录.md)、[企业微信节点提醒](../42_企业微信节点提醒.md)。真实 `.env` 留在部署环境，不提交 Git。
