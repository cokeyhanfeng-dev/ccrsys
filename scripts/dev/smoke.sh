#!/usr/bin/env bash

set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

retry_http() {
  local url="$1"
  local output="$2"
  local attempt
  for attempt in $(seq 1 30); do
    if curl -fsS "${url}" -o "${output}"; then
      return 0
    fi
    sleep 2
  done
  return 1
}

ccr_require_command curl
ccr_compose ps --status running

echo "[1/6] MySQL"
ccr_compose exec -T -e MYSQL_PWD=root123 mysql mysqladmin ping -h 127.0.0.1 -uroot --silent
table_count="$(ccr_compose exec -T -e MYSQL_PWD=root123 mysql mysql -N -uroot -e \
  "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='ccr_rate';" | tr -d '\r')"
[[ "${table_count}" =~ ^[0-9]+$ && "${table_count}" -ge 90 ]] \
  || ccr_die "数据库表数量异常: ${table_count}"
echo "  ccr_rate 表数量: ${table_count}"

echo "[2/6] Redis"
redis_pong="$(ccr_compose exec -T redis redis-cli ping | tr -d '\r')"
[[ "${redis_pong}" == "PONG" ]] || ccr_die "Redis PING 失败"

echo "[3/6] 后端健康检查"
health_file="${CCR_CACHE_DIR}/smoke-health.json"
retry_http "http://127.0.0.1:18080/health" "${health_file}" || ccr_die "后端健康检查失败"
grep -q '"status":"UP"' "${health_file}" || ccr_die "后端健康响应异常"

echo "[4/6] 登录与数据库链路"
login_file="${CCR_CACHE_DIR}/smoke-login.json"
login_password="${CCR_SMOKE_PASSWORD:-Yxnsh@1a3s}"
printf '{"username":"admin","password":"%s"}' "${login_password}" | curl -fsS -X POST "http://127.0.0.1:18080/auth/login" \
  -H 'Content-Type: application/json' \
  --data-binary @- \
  -o "${login_file}"
grep -q '"token"' "${login_file}" || ccr_die "登录冒烟失败"

echo "[5/6] 前端"
frontend_file="${CCR_CACHE_DIR}/smoke-index.html"
retry_http "http://127.0.0.1:13000/" "${frontend_file}" || ccr_die "前端访问失败"
grep -qi '<div id="app"' "${frontend_file}" || ccr_die "前端入口内容异常"

echo "[6/6] 前端 API 代理"
proxy_health_file="${CCR_CACHE_DIR}/smoke-proxy-health.json"
retry_http "http://127.0.0.1:13000/api/health" "${proxy_health_file}" \
  || ccr_die "前端 API 代理失败"
grep -q '"status":"UP"' "${proxy_health_file}" || ccr_die "前端 API 代理响应异常"

echo "冒烟测试通过: MySQL=${table_count} tables, Redis=PONG, backend/login/frontend/proxy=OK"
