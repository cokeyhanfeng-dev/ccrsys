#!/usr/bin/env bash
# ══════════════════════════════════════════════════════════════
# CCR 离线增量部署脚本(生产侧,scripts/incr-release.sh 的配套)
# 用法: ./scripts/deploy-incr.sh <增量包目录或.tar.gz> [--skip-sql] [--skip-backend] [--skip-frontend]
#   例: ./scripts/deploy-incr.sh release/incr_20260829_026
#       ./scripts/deploy-incr.sh /tmp/ccr-incr-20260829_026.tar.gz
# 约定(环境变量可覆盖):
#   CCR_MYSQL_CONTAINER=ccr-mysql  CCR_MYSQL_PWD=root123  CCR_MYSQL_DB=ccr_rate
#   CCR_BACKEND_CONTAINER=ccr-backend(镜像内 jar 路径 /app/app.jar)
#   CCR_FRONTEND_CONTAINER=ccr-frontend(nginx 静态目录 /usr/share/nginx/html)
# 全程离线:不拉镜像、不构建,只做 执行SQL/替换jar/替换dist/冒烟。
# ══════════════════════════════════════════════════════════════
set -euo pipefail

PKG_INPUT="${1:?用法: ./scripts/deploy-incr.sh <增量包目录或.tar.gz> [--skip-sql] [--skip-backend] [--skip-frontend]}"
shift || true
SKIP_SQL=0; SKIP_BACKEND=0; SKIP_FRONTEND=0
for arg in "$@"; do
  case "$arg" in
    --skip-sql) SKIP_SQL=1 ;;
    --skip-backend) SKIP_BACKEND=1 ;;
    --skip-frontend) SKIP_FRONTEND=1 ;;
    *) echo "未知参数: $arg"; exit 1 ;;
  esac
done

MYSQL_C="${CCR_MYSQL_CONTAINER:-ccr-mysql}"
MYSQL_P="${CCR_MYSQL_PWD:-root123}"
MYSQL_DB="${CCR_MYSQL_DB:-ccr_rate}"
BACKEND_C="${CCR_BACKEND_CONTAINER:-ccr-backend}"
FRONTEND_C="${CCR_FRONTEND_CONTAINER:-ccr-frontend}"

TS="$(date +%Y%m%d%H%M%S)"
WORKDIR=""

# ── 包解析:目录直接用,tar.gz 先解压 ──
if [ -f "$PKG_INPUT" ] && [[ "$PKG_INPUT" == *.tar.gz || "$PKG_INPUT" == *.tgz ]]; then
  WORKDIR="$(mktemp -d)"
  echo "==> 解压增量包到 $WORKDIR"
  tar xzf "$PKG_INPUT" -C "$WORKDIR"
  # 包内可能带一层顶层目录
  PKG_DIR="$(find "$WORKDIR" -mindepth 1 -maxdepth 1 -type d | head -1)"
  [ -z "$PKG_DIR" ] && PKG_DIR="$WORKDIR"
elif [ -d "$PKG_INPUT" ]; then
  PKG_DIR="$PKG_INPUT"
else
  echo "✗ 增量包不存在: $PKG_INPUT"; exit 1
fi
echo "==> 增量包: $PKG_DIR"

# ── 前置检查 ──
docker version >/dev/null 2>&1 || { echo "✗ docker 不可用"; exit 1; }
need_running() { docker ps --format '{{.Names}}' | grep -qx "$1" || { echo "✗ 容器 $1 未运行"; exit 1; }; }

BACKUP_DIR="release/rollback/$TS"
mkdir -p "$BACKUP_DIR"

# ── 1) 增量 SQL ──
if [ "$SKIP_SQL" = "0" ] && compgen -G "$PKG_DIR/db/*.sql" > /dev/null; then
  need_running "$MYSQL_C"
  echo "==> [1/4] 执行增量 SQL(按文件名升序,脚本幂等可重复)..."
  for f in "$PKG_DIR"/db/*.sql; do
    echo "    -> $(basename "$f")"
    docker exec -i "$MYSQL_C" mysql -uroot -p"$MYSQL_P" --default-character-set=utf8mb4 < "$f"
  done
else
  echo "==> [1/4] 跳过 SQL(--skip-sql 或包内无 db/*.sql)"
fi

# ── 2) 后端 jar ──
if [ "$SKIP_BACKEND" = "0" ] && [ -f "$PKG_DIR/backend/ccr-admin.jar" ]; then
  need_running "$BACKEND_C"
  echo "==> [2/4] 替换后端 jar 并重启 $BACKEND_C ..."
  docker cp "$BACKEND_C:/app/app.jar" "$BACKUP_DIR/ccr-admin.jar.bak" && echo "    旧 jar 备份: $BACKUP_DIR/ccr-admin.jar.bak"
  docker cp "$PKG_DIR/backend/ccr-admin.jar" "$BACKEND_C:/app/app.jar"
  docker restart "$BACKEND_C" >/dev/null
  echo "    等待后端就绪..."
  for i in $(seq 1 30); do
    sleep 2
    code="$(curl -s -o /dev/null -w '%{http_code}' -X POST http://127.0.0.1:8080/auth/login -H 'Content-Type: application/json' -d '{}' || true)"
    if [ "$code" = "200" ] || [ "$code" = "400" ] || [ "$code" = "401" ]; then echo "    后端就绪(${i}0 秒内,HTTP $code)"; break; fi
    [ "$i" = "30" ] && { echo "✗ 后端 60 秒未就绪,请查 docker logs $BACKEND_C"; exit 1; }
  done
else
  echo "==> [2/4] 跳过后端(--skip-backend 或包内无 backend/ccr-admin.jar)"
fi

# ── 3) 前端 dist ──
if [ "$SKIP_FRONTEND" = "0" ] && [ -d "$PKG_DIR/frontend/dist" ]; then
  need_running "$FRONTEND_C"
  echo "==> [3/4] 替换前端 dist ..."
  docker exec "$FRONTEND_C" sh -c "rm -rf /tmp/html.bak && cp -r /usr/share/nginx/html /tmp/html.bak"
  docker exec "$FRONTEND_C" sh -c "tar czf - -C /tmp html.bak" > "$BACKUP_DIR/frontend-html.tar.gz"
  echo "    旧静态目录备份: $BACKUP_DIR/frontend-html.tar.gz"
  (cd "$PKG_DIR/frontend/dist" && tar cf - .) | docker exec -i "$FRONTEND_C" sh -c \
    "rm -rf /usr/share/nginx/html/assets /usr/share/nginx/html/templates && tar xf - -C /usr/share/nginx/html"
else
  echo "==> [3/4] 跳过前端(--skip-frontend 或包内无 frontend/dist/)"
fi

# ── 4) 冒烟 ──
echo "==> [4/4] 冒烟检查..."
FAIL=0
code="$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:3000/ || true)"
echo "    前端首页 http://127.0.0.1:3000/ -> HTTP $code"; [ "$code" = "200" ] || FAIL=1
code="$(curl -s -o /dev/null -w '%{http_code}' -X POST http://127.0.0.1:3000/api/auth/login -H 'Content-Type: application/json' -d '{}' || true)"
echo "    登录接口(经 nginx 代理) -> HTTP $code"; [ "$code" = "200" ] || FAIL=1

echo ""
if [ "$FAIL" = "0" ]; then
  echo "==> 部署完成,冒烟通过。回滚备份在 $BACKUP_DIR"
  echo "    注意:若增量 SQL 尾部有 Redis key 清理提示,请按包内 README 执行对应 DEL"
else
  echo "✗ 部署完成但冒烟未通过,请检查容器日志;回滚备份在 $BACKUP_DIR"
  exit 1
fi

[ -n "$WORKDIR" ] && rm -rf "$WORKDIR" || true
