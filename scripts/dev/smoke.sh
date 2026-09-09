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

# 移动端仅核对公开入口和拒绝路径；真实免密成功需有度客户端/验票服务参与。
echo "[mobile] 独立入口与权限边界"
mobile_file="${CCR_CACHE_DIR}/smoke-mobile.html"
retry_http "http://127.0.0.1:13000/mobile/" "${mobile_file}" || ccr_die "移动入口访问失败"
grep -q 'id="root"' "${mobile_file}" || ccr_die "移动入口未部署"
mobile_auth_file="${CCR_CACHE_DIR}/smoke-mobile-auth.json"
curl -fsS 'http://127.0.0.1:13000/mobile/api/mobile/session' -o "${mobile_auth_file}"
grep -q '"code":401' "${mobile_auth_file}" || ccr_die "移动接口未拒绝匿名访问"
mobile_status="$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:13000/mobile/api/auth/login)"
[[ "${mobile_status}" == "404" ]] || ccr_die "移动代理未封闭电脑端接口"
echo "移动冒烟通过: entry=OK, anonymous=401, non-mobile-proxy=404"
# 电脑端登录令牌即使自报移动渠道，仍不可访问移动接口。
mobile_header_file="${CCR_CACHE_DIR}/smoke-mobile-header.txt"
node - "${login_file}" "${mobile_header_file}" <<'NODE'
const fs = require('node:fs');
const login = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
fs.writeFileSync(process.argv[3], 'Authorization: ' + login.data.token + '\nX-Client-Type: mobile\n', {mode: 0o600});
NODE
curl -fsS 'http://127.0.0.1:13000/mobile/api/mobile/session' -H "@${mobile_header_file}" -o "${mobile_auth_file}"
grep -q '"code":403' "${mobile_auth_file}" || ccr_die "电脑端令牌越过移动渠道校验"
rm -f "${mobile_header_file}"
curl -fsS 'http://127.0.0.1:13000/mobile/api/mobile/login' -H 'Content-Type: application/json' --data '{"username":"admin"}' -o "${mobile_auth_file}"
grep -q '"code":400' "${mobile_auth_file}" || ccr_die "移动登录允许缺少有度凭证"
echo "移动鉴权通过: PC-token=403, username-only=400"
# OA 登录同样必须提供票据，不能用客户端账号替代。
curl -fsS 'http://127.0.0.1:13000/mobile/api/mobile/oa/login' -H 'Content-Type: application/json' --data '{"username":"admin"}' -o "${mobile_auth_file}"
grep -q '"code":400' "${mobile_auth_file}" || ccr_die "OA 登录允许缺少票据"
echo "OA 鉴权参数检查通过: username-only=400"
