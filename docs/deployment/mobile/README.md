# 移动审批 8090 发布手册

适用于现有 nginx-nextcloud 容器，电脑端 80、移动端 8090、CCR 后端 8080。
构建在开发机完成；服务器只解包、替换、校验，不联网构建。仅配置移动站点不会启用免密认证。

## 一、打包

模式：1 后端、2 电脑端、3 移动端；12/13/23/123 为对应组合。旧 12 仍为后端+电脑端。

```bash
./dev release 13   # 本次首次发布：后端+移动端
./dev release 3    # 后续只更新移动端
```

Windows 对应 `release.cmd 13` / `release.cmd 3`，可加 `-CheckOnly` 检查工具。
Mac/Linux 后端默认执行测试；Windows 同样默认执行测试。包记录工作区状态，未提交修复也会进入实际构建。
产物在 release/，包括发布包与 deploy-release.sh。包内 frontend/dist-mobile 为移动文件，deployment 为本目录模板。
电脑端 2 不含移动目录；模式 123 才包含三个组件。校验和覆盖 Jar、静态文件与部署模板。

## 二、后端服务器

先独立备份旧 Jar、Compose、外部配置。将示例 mobile.env.example 中实际认证参数合并到现有受控环境文件；不要覆盖原有数据库、Redis或电脑认证参数。
仓库 docker-compose.prod.yml 已补齐七个移动变量，现场 Compose 也需对应 environment 映射。
将两种 ENABLED 按实际接入改为 true，URL 中分别保留一个 {ticket}/{token}，有度成功码需与接口一致。
OA 当前支持 ticket/oaToken，Authing code 尚未支持移动入口。密钥仅存服务器，限制配置文件读取权限。

如果改的是 Compose 环境变量：沿用原项目名、原文件与完整环境文件，仅重新创建 backend（不 build、不 pull、不操作数据库）。
重建会丢容器内旧热更新 Jar，务必先备份，再重建，再发布新 Jar。只 restart 不会加载新的容器环境变量。
如果配置在已经加载的外部 application-prod.yml 中，则合并 ccr.mobile 配置后随 Jar 重启即可。

上传发布包和脚本到 /tmp，使用实际后端容器名：

```bash
CCR_BACKEND_CONTAINER=实际后端容器名 bash /tmp/deploy-release.sh 1 /tmp/实际发布包.tar.gz
```

异机部署时，同一个 13 包在后端机只执行 1，在移动站点机只执行 3；不要在两个服务器都执行 13。
后端脚本保留原备份与就绪失败恢复逻辑。Jar 位于宿主机 bind mount 时应沿用现场的宿主机 Jar 更新流程，不能直接套用容器内 mv 方案。

## 三、首次配置移动站点

1. 备份原 Compose、/data/nginx/config/nginx.conf 和现有移动静态目录。
2. `mkdir -p /data/ccr/mobile`。把包内 deployment/ccr-mobile-8090.conf 放到 /data/nginx/config/ccr-mobile-8090.conf。
3. 若后端与 Nginx 同宿主机且宿主机发布了 8080，模板 host.docker.internal:8080 可用；异机则改成后端实际内网地址。
4. 按 compose.nginx.yml 合并现有 Compose：增加移动目录和配置文件挂载、8090:8090；镜像使用服务器已有完整镜像名。保留其他服务/网络/站点。模板保留用户已有 privileged 设置并去掉重复键。
5. 原 nginx.conf 的 **http 块内部**仅追加一次：

```nginx
include /etc/nginx/ccr-mobile-8090.conf;
```

不要用本片段覆盖主 nginx.conf；未拿到原主配置，不能重建其中其他站点。若已有同一文件的 include，勿重复包含。
新增 include 在旧容器尚无对应挂载，直接 nginx -t 会报文件不存在。先完成下述候选配置检查，再重建。

在原 Compose 所在目录（保持原项目名，若平时用 -p/-f/--env-file 则继续携带相同参数）：

```bash
docker compose config --quiet
# 使用现有本地镜像和新挂载，在一次性容器中检查；不启动站点、不发布端口。
docker compose run --rm --no-deps --pull never nginx nginx -t
# 短暂影响现有容器承载的全部站点，安排维护窗口。
docker compose up -d --no-deps --no-build --pull never --force-recreate nginx
docker exec nginx-nextcloud nginx -t
```

首次没有页面时 8090 返回 404 是预期，下一步发布静态文件后再开放工作台入口。
配置校验或容器启动失败时恢复备份 Compose/主配置并沿用原项目重建 nginx；部署脚本不会修改它们。

## 四、发布移动文件

上传同一个发布包和 deploy-release.sh 到移动站点服务器 /tmp：

```bash
bash /tmp/deploy-release.sh 3 /tmp/实际发布包.tar.gz
```

默认写 /data/ccr/mobile，原地更新以兼容 Docker 挂载；电脑端目录完全独立。
默认通过 http://127.0.0.1:8090/mobile/ 检查 HTML 与包内容一致、JS 资源与包一致、匿名 session 为业务401。
域名入口可设置 CCR_MOBILE_HEALTH_URL（必须以 /mobile/ 为入口）；目录可设置 CCR_MOBILE_DIR。
移动模式支持自动选择匹配包，部署前必须确认；先验校验和与完整性，再备份。校验/复制失败或可捕获的 INT/TERM 中断会恢复旧移动文件并非零退出。断电或 SIGKILL 后按备份手工恢复，核实没有其他发布进程后清理残留 .release-lock 目录。
组合模式按后端→电脑端→移动端执行；移动失败仅恢复移动文件，已成功发布的其他组件不会整体自动回滚。
发布期间可能短暂遇到资源切换，建议维护窗口更新。

备份位于 /data/ccr/backup/<时间>-<进程号>/mobile.tar.gz，并附 mobile-path.txt。手工回退：确认文件中目标确为移动目录后，清空该目录内容、把备份解压回原目录；保留目录本身以兼容挂载。
后续静态文件更新无需重建 Nginx。配置更新先 nginx -t 再 reload；新增挂载/端口才需要重建。

## 五、验收

入口：http://Nginx服务器:8090/mobile/。生产入口建议使用行内 HTTPS 终止；前置代理也要避免记录票据查询串和 Referer。
OA 从工作台带一次性 ticket/oaToken；有度从原生应用获取 token。测试真实审批账号、客户经理拒绝、各角色待办、1BP调价、否决、表决、行长决策。
手机→8090、Nginx→后端8080、后端→验票服务三段均需通。附件经后端读数据库返回，手机无需访问数据库或存储桶。
Android/iOS 分别验 PDF、图片、Office 下载和20MiB限制。本地回归不替代真实认证/手机附件验收。
本次移动发布无新增 SQL；现有生产库仍需符合当前工程基线。禁止运行测试初始化/reset。

### 安装进度与结果信息（2026-09-16）

安装终端仅显示阶段进度条（按解压、校验、环境检查及所选组件计数，不代表解压字节百分比），逐文件校验与命令明细写入 `/tmp/ccr-install.XXXXXX` 独立日志。开始和结束均显示实际包全名及绝对路径；结束时逐项列出已生成的 `app.jar.bak`、`frontend.tar.gz`、`mobile.tar.gz` 的完整路径，并给出 `tail -n 200 -f <本次安装日志>`、实际后端容器的 `docker logs --tail 200 -f <容器名>` 以及 Nginx/移动访问日志命令。失败时保留日志、显示末尾错误和已生成备份，不显示 100% 完成。日志位于 /tmp，清理前按需归档；Nginx 日志样例沿用本手册的容器名与挂载路径。

### 自动选包与部署确认（2026-09-16）

所有部署模式均可省略包路径，例如 `bash /tmp/deploy-release.sh 3`。脚本从 `/tmp`（可用 `CCR_RELEASE_DIR` 覆盖）按文件修改时间选取包含本次全部所选组件的最新包，以包内实际产物判断，跳过不匹配或不可读的归档；完整性校验失败仍终止，不自动换包继续。显式传入路径则使用该包。

无论自动或显式选包，均先展示包全名、绝对路径、大小、部署组件和目标目录/容器，输入 `y` 或 `yes` 后才开始解压校验、备份和替换；回车、其他输入或 EOF 均取消，不修改服务文件。若需切换旧版包，取消后带指定路径重新执行。新增端口/挂载仍须先配置 Nginx。
