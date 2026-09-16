#!/usr/bin/env bash
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"
ccr_prepare_dirs
case "${1:-standalone}" in
  standalone) template="${CCR_PROJECT_ROOT}/frontend/mobile/nginx.conf" ;;
  8090) template="${CCR_PROJECT_ROOT}/docs/deployment/mobile/ccr-mobile-8090.conf" ;;
  *) echo '用法: ./dev mobile-nginx-test [standalone|8090]' >&2; exit 2 ;;
esac

# 只在 ccrsys-test 前端容器内启动临时 Nginx；端口不发布到宿主机，现有站点不重载。
nginx_dir="$(ccr_compose exec -T frontend mktemp -d /tmp/ccr-mobile-check.XXXXXX)"
local_config="$(mktemp "${CCR_CACHE_DIR}/mobile-nginx.XXXXXX")"
cleanup() {
  ccr_compose exec -T frontend sh -c '
    if [ -f "$1/nginx.pid" ]; then nginx -s quit -c "$1/nginx.conf"; fi
    rm -rf -- "$1"
  ' sh "${nginx_dir}" >/dev/null 2>&1 || true
  rm -f -- "${local_config}"
}
trap cleanup EXIT

node - "${template}" "${nginx_dir}" > "${local_config}" <<'NODE'
const fs = require('node:fs');
const dir = process.argv[3];
if (!/^\/tmp\/ccr-mobile-check\.[A-Za-z0-9]+$/.test(dir)) throw Error('临时目录格式异常');
const site = fs.readFileSync(process.argv[2], 'utf8')
  .replace(/listen (80|8090);/, 'listen 127.0.0.1:18089;')
  .replace('root /var/www;', 'root /usr/share/nginx/html;')
  .replace('host.docker.internal:8080', 'backend:8080')
  .replaceAll('/var/log/nginx/mobile-access.log', `${dir}/access.log`)
  .replaceAll('/var/log/nginx/access.log', `${dir}/access.log`);
process.stdout.write(`pid ${dir}/nginx.pid;\nerror_log ${dir}/error.log warn;\nevents {}\nhttp {\ninclude /etc/nginx/mime.types;\n${site}\n}\n`);
NODE
ccr_compose exec -T frontend sh -c 'cat > "$1/nginx.conf"' sh "${nginx_dir}" < "${local_config}"
ccr_compose exec -T frontend nginx -t -c "${nginx_dir}/nginx.conf"
ccr_compose exec -T frontend nginx -c "${nginx_dir}/nginx.conf"
ccr_compose exec -T frontend sh -s -- "${nginx_dir}" <<'CHECK'
set -eu
tmp="$1"
base=http://127.0.0.1:18089
for entry in '/' '/mobile'; do
  curl -fsS -D "$tmp/headers" -o "$tmp/body" "$base$entry?ticket=mobile-nginx-fixture&from=oa"
  grep -q 'Location: .*\/mobile/?ticket=mobile-nginx-fixture&from=oa' "$tmp/headers"
  grep -qi 'Referrer-Policy: no-referrer' "$tmp/headers"
done
curl -fsS -D "$tmp/headers" "$base/mobile/" -o "$tmp/body"
grep -q 'id="root"' "$tmp/body"
grep -qi 'Cache-Control: no-store' "$tmp/headers"
asset=$(sed -n 's/.*src="\([^"]*\.js\)".*/\1/p' "$tmp/body" | head -n 1)
test -n "$asset"
curl -fsS "$base$asset" -o "$tmp/asset"
test -s "$tmp/asset"
curl -fsS "$base/mobile/api/mobile/session" -o "$tmp/body"
grep -q '"code":401' "$tmp/body"
curl -fsS "$base/mobile/api/mobile/oa/login" -H 'Content-Type: application/json' --data '{"username":"fixture"}' -o "$tmp/body"
grep -q '"code":400' "$tmp/body"
status=$(curl -sS -o /dev/null -w '%{http_code}' "$base/mobile/api/auth/login")
test "$status" = 404
if grep -q 'mobile-nginx-fixture' "$tmp/access.log"; then
  echo '失败：验票参数进入移动访问日志' >&2
  exit 1
fi
echo '独立移动 Nginx 验证通过：两种携票跳转、静态资源、API 代理、参数校验、代理边界、日志脱敏'
CHECK
