#!/usr/bin/env bash
# CCR 生产轻量部署：从 /tmp 发布包替换容器内 Jar 和/或宿主机前端目录。
set -euo pipefail

MODE="${1:-}"
ARCHIVE_PATH="${2:-}"
PACKAGE_DIR=""
WORK_DIR=""
BACKUP_DIR=""
MOBILE_PENDING=false
MOBILE_LOCK=""
MOBILE_DIR=""
BACKEND_CONTAINER=""
INSTALL_LOG=""
PROGRESS=0
TOTAL_STAGES=0

usage() {
  cat <<'EOF'
用法: bash /tmp/deploy-release.sh [1|2|3|12|13|23|123] [发布包.tar.gz]
  1  部署后端
  2  部署前端
  3  部署移动端
  12 后端+电脑端；13 后端+移动端；23 两端前端；123 全部

省略发布包时，自动选取 /tmp 下包含所选组件且修改时间最新的 ccr-release-*.tar.gz。
所有模式均先展示包信息，输入 y/yes 确认后才部署；回车或输入结束即取消。
可覆盖环境变量:
  CCR_RELEASE_DIR       自动查包目录（默认 /tmp）
  CCR_BACKEND_CONTAINER  后端容器名或 ID（默认自动识别）
  CCR_BACKEND_JAR_PATH   容器内 Jar 路径（默认 /app/app.jar）
  CCR_FRONTEND_DIR       宿主机前端目录（默认 /data/ccr/frontend）
  CCR_MOBILE_DIR        移动目录（默认 /data/ccr/mobile，必须与电脑目录分离）
  CCR_MOBILE_HEALTH_URL 移动入口（默认 http://127.0.0.1:8090/mobile/）
  CCR_BACKUP_DIR         备份根目录（默认 /data/ccr/backup）
  CCR_BACKEND_HEALTH_URL 后端就绪检查地址（默认 http://127.0.0.1:8080/auth/login）
EOF
}

if [[ -z "${MODE}" ]]; then
  printf '请选择部署内容（1=后端，2=电脑端，3=移动端，13=后端+移动，123=全部）: '
  read -r MODE
fi
case "${MODE}" in
  1|2|3|12|13|23|123) ;;
  -h|--help) usage; exit 0 ;;
  *) usage >&2; exit 2 ;;
esac

for command_name in tar find; do
  command -v "$command_name" >/dev/null 2>&1 || { echo "缺少命令: $command_name" >&2; exit 1; }
done

# 按包内实际产物判断组件，不能仅凭文件名后缀选包。
package_matches() {
  local archive="$1" entries root
  entries="$(LC_ALL=C tar -tzf "$archive" 2>/dev/null)" || return 1
  if printf '%s\n' "$entries" | grep -Eq '(^/|(^|/)\.\.(/|$))'; then return 1; fi
  root="${entries%%$'\n'*}"
  root="${root%%/*}"
  [[ "$root" == ccr-release-* ]] || return 1
  if [[ "$MODE" == *1* ]]; then
    printf '%s\n' "$entries" | grep -Fx "$root/backend/ccr-admin.jar" >/dev/null || return 1
  fi
  if [[ "$MODE" == *2* ]]; then
    printf '%s\n' "$entries" | grep -Fx "$root/frontend/dist/index.html" >/dev/null || return 1
  fi
  if [[ "$MODE" == *3* ]]; then
    printf '%s\n' "$entries" | grep -Fx "$root/frontend/dist-mobile/index.html" >/dev/null || return 1
  fi
}
if [[ -z "$ARCHIVE_PATH" ]]; then
  RELEASE_ROOT="${CCR_RELEASE_DIR:-/tmp}"
  echo "正在 $RELEASE_ROOT 查找包含所选组件的最新发布包…"
  shopt -s nullglob
  release_archives=("${RELEASE_ROOT%/}"/ccr-release-*.tar.gz)
  shopt -u nullglob
  # 先仅比较文件时间排序，避免按文件名从旧到新反复解压历史包。
  sorted_archives=()
  for candidate in "${release_archives[@]}"; do
    [[ -f "$candidate" ]] || continue
    index=${#sorted_archives[@]}
    while (( index > 0 )); do
      previous=$((index - 1))
      [[ "$candidate" -nt "${sorted_archives[$previous]}" ]] || break
      sorted_archives[$index]="${sorted_archives[$previous]}"
      index=$previous
    done
    sorted_archives[$index]="$candidate"
  done
  checked=0
  for candidate in "${sorted_archives[@]}"; do
    checked=$((checked + 1))
    printf '检查候选包 [%s/%s]: %s\n' "$checked" "${#sorted_archives[@]}" "${candidate##*/}"
    if package_matches "$candidate"; then
      ARCHIVE_PATH="$candidate"
      break
    fi
  done
else
  [[ -f "$ARCHIVE_PATH" ]] && package_matches "$ARCHIVE_PATH" || {
    echo '指定发布包不存在、无法读取或未包含所选组件。' >&2; exit 1;
  }
fi
[[ -n "$ARCHIVE_PATH" ]] || { echo '未找到包含所选组件的可读发布包，请检查上传目录。' >&2; exit 1; }
ARCHIVE_PATH="$(cd "$(dirname "$ARCHIVE_PATH")" && pwd -P)/$(basename "$ARCHIVE_PATH")"
contents=""
[[ "$MODE" != *1* ]] || contents+=' 后端'
[[ "$MODE" != *2* ]] || contents+=' 电脑端'
[[ "$MODE" != *3* ]] || contents+=' 移动端'
printf '\n待部署包: %s\n完整路径: %s\n包大小: %s 字节\n本次部署:%s\n' \
  "${ARCHIVE_PATH##*/}" "$ARCHIVE_PATH" "$(wc -c < "$ARCHIVE_PATH" | tr -d ' ')" "$contents"
[[ "$MODE" != *1* ]] || printf '后端容器: %s\n' "${CCR_BACKEND_CONTAINER:-自动识别}"
[[ "$MODE" != *2* ]] || printf '电脑端目录: %s\n' "${CCR_FRONTEND_DIR:-/data/ccr/frontend}"
[[ "$MODE" != *3* ]] || printf '移动端目录: %s\n' "${CCR_MOBILE_DIR:-/data/ccr/mobile}"
printf '确认使用以上发布包部署？[y/N]: '
confirmation=""
if ! read -r confirmation; then confirmation=""; fi
case "$confirmation" in
  y|Y|yes|YES|Yes) ;;
  *) echo '已取消部署，未备份或替换任何服务文件。'; exit 0 ;;
esac

# 标准输出保留给简洁进度；命令明细写入独立日志，失败时仍保留。
ARCHIVE_PATH="$(cd "$(dirname "$ARCHIVE_PATH")" && pwd -P)/$(basename "$ARCHIVE_PATH")"
INSTALL_LOG="$(umask 077; mktemp /tmp/ccr-install.XXXXXX)"
TOTAL_STAGES=$((3 + ${#MODE}))
exec 3>&1
printf '本次安装包: %s\n安装包完整路径: %s\n安装日志: %s\n' "${ARCHIVE_PATH##*/}" "$ARCHIVE_PATH" "$INSTALL_LOG" >&3
exec >>"$INSTALL_LOG" 2>&1
printf '安装包: %s\n部署模式: %s\n开始时间: %s\n' "$ARCHIVE_PATH" "$MODE" "$(date '+%Y-%m-%d %H:%M:%S')"

progress() {
  local label="$1" filled=$((PROGRESS * 20 / TOTAL_STAGES)) bar="" i
  for ((i=0; i<20; i++)); do
    if ((i < filled)); then bar+='#'; else bar+='-'; fi
  done
  printf '阶段进度 [%s] %3d%% %s\n' "$bar" "$((PROGRESS * 100 / TOTAL_STAGES))" "$label" >&3
  printf '阶段: %s\n' "$label"
}
report_paths() {
  local name found=false
  printf '\n安装包全名: %s\n安装包完整路径: %s\n' "${ARCHIVE_PATH##*/}" "$ARCHIVE_PATH"
  for name in app.jar.bak frontend.tar.gz mobile.tar.gz; do
    if [[ -n "$BACKUP_DIR" && -f "$BACKUP_DIR/$name" ]]; then
      printf '备份文件完整路径: %s\n' "$BACKUP_DIR/$name"
      found=true
    fi
  done
  if [[ "$found" == false ]]; then echo '本次尚未生成备份文件。'; fi
  printf '安装日志完整路径: %s\n查看安装日志: tail -n 200 -f %q\n' "$INSTALL_LOG" "$INSTALL_LOG"
  if [[ -n "$BACKEND_CONTAINER" ]]; then
    printf '查看后端日志: docker logs --tail 200 -f %q\n' "$BACKEND_CONTAINER"
  fi
  if [[ "$MODE" == *2* || "$MODE" == *3* ]]; then
    echo '查看 Nginx 容器日志（沿用现有容器名）: docker logs --tail 200 -f nginx-nextcloud'
  fi
  if [[ "$MODE" == *3* ]]; then
    echo '查看移动访问日志: tail -n 200 -f /data/nginx/log/mobile-access.log'
  fi
}
cleanup() {
  local status=$?
  trap - EXIT
  if ((status == 0 && PROGRESS < TOTAL_STAGES)); then status=1; fi
  if [[ "$MOBILE_PENDING" == true ]]; then
    echo '移动发布未完成，恢复旧静态文件。'
    if restore_mobile; then
      echo '移动端旧文件已恢复。' >&3
    else
      echo "自动恢复失败，请使用 ${BACKUP_DIR}/mobile.tar.gz 手工恢复。" >&3
    fi
  fi
  if [[ -n "$MOBILE_LOCK" ]]; then rmdir "$MOBILE_LOCK" || true; fi
  if [[ -n "$WORK_DIR" && -d "$WORK_DIR" ]]; then rm -rf -- "$WORK_DIR"; fi
  if ((status != 0)); then
    printf '\n安装失败（退出码 %s），日志末尾：\n' "$status" >&3
    tail -n 12 "$INSTALL_LOG" >&3
  else
    echo '安装完成，临时解压目录已清理，上传包保留。' >&3
  fi
  report_paths >&3
  report_paths
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

progress '检查并解压安装包'
# 解压前拒绝绝对路径和上级目录，避免异常压缩包越界写入。
LC_ALL=C tar -tzf "$ARCHIVE_PATH" > "${INSTALL_LOG}.entries"
if grep -Eq '(^/|(^|/)\.\.(/|$))' "${INSTALL_LOG}.entries"; then
  rm -f "${INSTALL_LOG}.entries"
  echo '发布包包含不安全路径，已拒绝解压。'; exit 1
fi
rm -f "${INSTALL_LOG}.entries"
WORK_DIR="$(mktemp -d /tmp/ccr-deploy.XXXXXX)"
LC_ALL=C tar -xzf "$ARCHIVE_PATH" -C "$WORK_DIR"
PACKAGE_DIR="$(find "$WORK_DIR" -mindepth 1 -maxdepth 1 -type d -name 'ccr-release-*' | head -n 1)"
[[ -n "$PACKAGE_DIR" ]] || { echo '发布包目录结构无效。'; exit 1; }
PROGRESS=$((PROGRESS + 1))

verify_checksums() {
  [[ -s "${PACKAGE_DIR}/SHA256SUMS" ]] || { echo "发布包缺少 SHA256SUMS。" >&2; exit 1; }
  if command -v sha256sum >/dev/null 2>&1; then
    (cd "${PACKAGE_DIR}" && sha256sum -c SHA256SUMS)
  elif command -v shasum >/dev/null 2>&1; then
    (cd "${PACKAGE_DIR}" && shasum -a 256 -c SHA256SUMS)
  else
    echo "服务器缺少 sha256sum/shasum，无法校验发布包。" >&2
    exit 1
  fi
}

validate_payload() {
  if [[ "${MODE}" == *1* ]]; then
    [[ -s "${PACKAGE_DIR}/backend/ccr-admin.jar" ]] || {
      echo "所选模式需要 backend/ccr-admin.jar，当前发布包未包含。" >&2
      exit 1
    }
  fi
  if [[ "${MODE}" == *2* ]]; then
    [[ -f "${PACKAGE_DIR}/frontend/dist/index.html" ]] || {
      echo "所选模式需要 frontend/dist/index.html，当前发布包未包含。" >&2
      exit 1
    }
  fi
}

container_running() {
  [[ "$(docker inspect -f '{{.State.Running}}' "$1" 2>/dev/null || true)" == "true" ]]
}

resolve_backend_container() {
  if [[ -n "${CCR_BACKEND_CONTAINER:-}" ]]; then
    printf '%s\n' "${CCR_BACKEND_CONTAINER}"
    return
  fi
  local candidate
  for candidate in ccr-prod-backend ccr-backend; do
    if container_running "${candidate}"; then
      printf '%s\n' "${candidate}"
      return
    fi
  done
  local matches=()
  while IFS=' ' read -r container_id container_name; do
    if docker exec "${container_id}" sh -c "test -f '${CCR_BACKEND_JAR_PATH:-/app/app.jar}'" >/dev/null 2>&1; then
      matches+=("${container_name}")
    fi
  done < <(docker ps --format '{{.ID}} {{.Names}}')
  if [[ "${#matches[@]}" -eq 1 ]]; then
    printf '%s\n' "${matches[0]}"
    return
  fi
  echo "无法自动识别后端容器，请设置 CCR_BACKEND_CONTAINER。" >&2
  exit 1
}

wait_backend() {
  local container_name="$1"
  local health_url="${CCR_BACKEND_HEALTH_URL:-http://127.0.0.1:8080/auth/login}"
  local http_code="" i
  for i in $(seq 1 30); do
    if ! container_running "${container_name}"; then
      sleep 2
      continue
    fi
    if command -v curl >/dev/null 2>&1; then
      http_code="$(curl --noproxy '*' -sS -o /dev/null -w '%{http_code}' --max-time 3 \
        -X POST "${health_url}" -H 'Content-Type: application/json' --data '{}' || true)"
      case "${http_code}" in
        200|400|401|405) echo "    后端已就绪，HTTP ${http_code}"; return ;;
      esac
    else
      sleep 8
      echo "    未安装 curl，已确认容器运行。"
      return
    fi
    sleep 2
  done
  echo "后端 60 秒内未通过就绪检查。" >&2
  return 1
}

deploy_backend() {
  local jar_file="${PACKAGE_DIR}/backend/ccr-admin.jar"
  local container_name jar_path
  jar_path="${CCR_BACKEND_JAR_PATH:-/app/app.jar}"
  [[ -s "${jar_file}" ]] || { echo "发布包缺少 backend/ccr-admin.jar。" >&2; exit 1; }
  container_name="$(resolve_backend_container)"
  BACKEND_CONTAINER="$container_name"
  container_running "${container_name}" || {
    echo "后端容器未运行: ${container_name}" >&2
    exit 1
  }

  echo "==> 部署后端到 ${container_name}:${jar_path}"
  if command -v unzip >/dev/null 2>&1; then
    unzip -tq "${jar_file}" >/dev/null
  fi
  docker cp "${container_name}:${jar_path}" "${BACKUP_DIR}/app.jar.bak"
  docker cp "${jar_file}" "${container_name}:${jar_path}.new"
  docker exec "${container_name}" mv "${jar_path}.new" "${jar_path}"
  docker restart "${container_name}" >/dev/null
  if ! wait_backend "${container_name}"; then
    echo "    就绪失败，自动恢复旧 Jar。" >&2
    docker cp "${BACKUP_DIR}/app.jar.bak" "${container_name}:${jar_path}"
    docker restart "${container_name}" >/dev/null
    exit 1
  fi
}

restore_frontend() {
  local frontend_dir="$1"
  find "${frontend_dir}" -mindepth 1 -maxdepth 1 -exec rm -rf -- {} +
  LC_ALL=C tar -xzf "${BACKUP_DIR}/frontend.tar.gz" -C "${frontend_dir}"
}

deploy_frontend() {
  local source_dir="${PACKAGE_DIR}/frontend/dist"
  local frontend_dir="${CCR_FRONTEND_DIR:-/data/ccr/frontend}"
  [[ -f "${source_dir}/index.html" ]] || { echo "发布包缺少 frontend/dist/index.html。" >&2; exit 1; }
  [[ -n "${frontend_dir}" && "${frontend_dir}" != "/" && "${frontend_dir}" != "/data" && "${frontend_dir}" != "/data/ccr" ]] || {
    echo "前端目录范围过大，已拒绝部署: ${frontend_dir}" >&2
    exit 1
  }
  mkdir -p "${frontend_dir}"

  echo "==> 部署前端到 ${frontend_dir}"
  LC_ALL=C tar -czf "${BACKUP_DIR}/frontend.tar.gz" -C "${frontend_dir}" .
  find "${frontend_dir}" -mindepth 1 -maxdepth 1 -exec rm -rf -- {} +
  if ! cp -a "${source_dir}/." "${frontend_dir}/"; then
    echo "    前端复制失败，自动恢复旧文件。" >&2
    restore_frontend "${frontend_dir}"
    exit 1
  fi
}

# 目录原地更新以兼容 Docker bind mount；拒绝与电脑端、备份目录重叠。
canonical_dir() {
  local path="$1" tail="" leaf
  [[ "$path" == /* && "$path" != *'/../'* && "$path" != */.. && "$path" != *'/./'* ]] || return 1
  path="${path%/}"; path="${path:-/}"
  while [[ ! -d "$path" ]]; do
    [[ ! -e "$path" && ! -L "$path" ]] || return 1
    leaf="${path##*/}"; tail="/$leaf$tail"; path="${path%/*}"; path="${path:-/}"
  done
  printf '%s%s\n' "$(cd "$path" && pwd -P)" "$tail"
}
validate_mobile_dirs() {
  local mobile pc backup other
  mobile="$(canonical_dir "$MOBILE_DIR")" || { echo '移动目录必须为有效绝对路径' >&2; exit 1; }
  pc="$(canonical_dir "${CCR_FRONTEND_DIR:-/data/ccr/frontend}")" || exit 1
  backup="$(canonical_dir "${CCR_BACKUP_DIR:-/data/ccr/backup}")" || exit 1
  case "$mobile" in /|/data|/data/ccr|/tmp|/var|/usr|/home|/root) echo '移动目录范围过大' >&2; exit 1;; esac
  for other in "$pc" "$backup" "$PACKAGE_DIR"; do
    if [[ "$mobile" == "$other" || "$mobile" == "$other/"* || "$other" == "$mobile/"* ]]; then
      echo '移动目录与电脑端、备份或发布包目录重叠，拒绝更新' >&2; exit 1
    fi
  done
  MOBILE_DIR="$mobile"
  mkdir -p "$(dirname "$MOBILE_DIR")"
  if ! mkdir "${MOBILE_DIR}.release-lock"; then echo "移动发布锁已存在，请确认其他发布已结束" >&2; exit 1; fi
  MOBILE_LOCK="${MOBILE_DIR}.release-lock"
}
restore_mobile() {
  find "$MOBILE_DIR" -mindepth 1 -maxdepth 1 -exec rm -rf -- {} + &&
    tar -xzf "${BACKUP_DIR}/mobile.tar.gz" -C "$MOBILE_DIR"
}
mobile_health() {
  local url="${CCR_MOBILE_HEALTH_URL:-http://127.0.0.1:8090/mobile/}" asset
  [[ "$url" == http://* || "$url" == https://* ]] || return 1
  url="${url%/}/"
  curl --noproxy '*' -fsS --max-time 15 "$url" -o "${WORK_DIR}/mobile-index" || return 1
  cmp -s "${WORK_DIR}/mobile-index" "${PACKAGE_DIR}/frontend/dist-mobile/index.html" || return 1
  asset="$(sed -n 's/.*src="\(\/mobile\/assets\/[^" ]*\.js\)".*/\1/p' "${WORK_DIR}/mobile-index" | head -n 1)"
  [[ -n "$asset" ]] || return 1
  curl --noproxy '*' -fsS --max-time 15 "${url%/mobile/}$asset" -o "${WORK_DIR}/mobile-asset" || return 1
  cmp -s "${WORK_DIR}/mobile-asset" "${PACKAGE_DIR}/frontend/dist-mobile/${asset#/mobile/}" || return 1
  curl --noproxy '*' -fsS --max-time 15 "${url}api/mobile/session" -o "${WORK_DIR}/mobile-session" || return 1
  grep -Eq '"code"[[:space:]]*:[[:space:]]*401([,}]|[[:space:]])' "${WORK_DIR}/mobile-session"
}
deploy_mobile() {
  mkdir -p "$MOBILE_DIR"
  tar -czf "${BACKUP_DIR}/mobile.tar.gz" -C "$MOBILE_DIR" .
  printf '%s\n' "$MOBILE_DIR" > "${BACKUP_DIR}/mobile-path.txt"
  MOBILE_PENDING=true
  find "$MOBILE_DIR" -mindepth 1 -maxdepth 1 -exec rm -rf -- {} +
  cp -a "${PACKAGE_DIR}/frontend/dist-mobile/." "$MOBILE_DIR/"
  if ! mobile_health; then
    echo '移动页面、资源或匿名会话校验失败，本次移动发布将恢复。请检查挂载和代理。' >&2
    exit 1
  fi
  MOBILE_PENDING=false
  echo "移动发布通过: ${MOBILE_DIR}（页面、资源一致，匿名会话 401）"
}

progress '校验安装包完整性'
verify_checksums
PROGRESS=$((PROGRESS + 1))
progress '检查部署环境与目标目录'
validate_payload
if [[ "${MODE}" == *1* ]]; then command -v docker >/dev/null; fi
if [[ "${MODE}" == *3* ]]; then
  command -v curl >/dev/null || { echo '移动发布需要 curl' >&2; exit 1; }
  [[ -s "${PACKAGE_DIR}/frontend/dist-mobile/index.html" ]] || { echo '发布包未包含移动页面' >&2; exit 1; }
  MOBILE_DIR="${CCR_MOBILE_DIR:-/data/ccr/mobile}"
  validate_mobile_dirs
fi
BACKUP_ROOT="${CCR_BACKUP_DIR:-/data/ccr/backup}"
[[ -n "${BACKUP_ROOT}" && "${BACKUP_ROOT}" != "/" ]] || { echo "备份目录无效。" >&2; exit 1; }
BACKUP_DIR="${BACKUP_ROOT}/$(date +%Y%m%d-%H%M%S)-$$"
mkdir -p "${BACKUP_DIR}"
BACKUP_DIR="$(cd "$BACKUP_DIR" && pwd -P)"
PROGRESS=$((PROGRESS + 1))

if [[ "$MODE" == *1* ]]; then
  progress '备份、安装后端并检查服务'
  deploy_backend
  PROGRESS=$((PROGRESS + 1))
fi
if [[ "$MODE" == *2* ]]; then
  progress '备份并安装电脑端'
  deploy_frontend
  PROGRESS=$((PROGRESS + 1))
fi
if [[ "$MODE" == *3* ]]; then
  progress '备份、安装移动端并检查页面与接口'
  deploy_mobile
  PROGRESS=$((PROGRESS + 1))
fi
progress '全部部署检查完成'
