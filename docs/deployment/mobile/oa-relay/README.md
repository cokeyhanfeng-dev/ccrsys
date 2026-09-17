# OA 中转服务

链路：OA 携 ticket 打开 `/oa/approval/identify` → 本服务 POST CCR `/mobile/oa/login` → CCR 验 OA 身份、校验本地审批权限并签发移动 token → 302 到移动前端 `/mobile/?token=...&source=oa` → 前端清理地址并 GET `/mobile/session` 验证会话。

8882/9100 属于信贷看板，不用于本中转示例。CCR 的 OA 验票地址应配置为返回绩效码的 OA 原始验票接口，不能指向信贷看板 `/oa/login`。OA 验票响应为绩效码；CCR `/mobile/oa/login` 响应为 `{"code":200,"data":{"token":"..."}}`。

## 部署

可把 app.py 中路由合并到已有 Flask 服务；合并时复用原 app，不要再次创建 Flask 实例。独立部署时在服务器虚拟环境中安装 requirements.txt，并注入：

```bash
export CCR_BACKEND='http://审批后端服务器:8080'
export CCR_MOBILE_ENTRY='http://移动审批服务器:8090/mobile/'
gunicorn --bind 127.0.0.1:8000 --workers 2 --timeout 60 app:app
```

8000 是本示例中转进程的本机监听端口，可由现有入口代理。OA 配置可访问的中转 URL `/oa/approval/identify`，由 OA 附加 ticket；重定向地址必须是手机网络实际可达的移动前端地址，也可使用已有 80 端口入口。

CCR 后端继续配置 `CCR_MOBILE_OA_ENABLED`、`CCR_MOBILE_OA_VERIFY_URL`、`CCR_MOBILE_OA_APP_ID`、`CCR_MOBILE_OA_API_KEY`。本次无需修改 CCR 后端；需要更新完整移动前端包及中转服务。

中转及前置代理不得记录 ticket、系统 token、完整查询串或 Location 响应头；访问日志只记录路径。示例不启用 Gunicorn access log；已有 Flask/代理日志需要检查，勿将 token 传给 record_visit_history。使用 HTTPS 入口（已有内网 HTTP 示例仅用于对应现网地址格式）。错误返回保留 401/403 业务区分，网络与无效响应返回 502。

## 验证

项目隔离虚拟环境安装 Flask/requests 后运行：

```bash
.tools/oa-relay-venv/bin/python scripts/tests/oa-relay-test.py
./dev frontend-test
```

真实 OA 验票配置和移动网络仍需现场验证。已登录浏览器收到新的 ticket/token 时，会替换旧会话；失效 token 或无权限不能回退旧身份。ticket/oaToken 原入口继续支持；系统 token 参数为 token，禁止混用。
