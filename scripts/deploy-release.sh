#!/usr/bin/env bash
# CCR 生产轻量部署：从 /tmp 发布包替换容器内 Jar 和/或宿主机前端目录。
set -euo pipefail

MODE="${1:-}"
ARCHIVE_PATH="${2:-}"
PACKAGE_DIR=""
WORK_DIR=""
BACKUP_DIR=""

usage() {
  cat <<'EOF'
用法: bash /tmp/deploy-release.sh [1|2|12] [发布包.tar.gz]
  1  部署后端
  2  部署前端
  12 部署后端和前端

省略发布包时，自动选取 /tmp 下修改时间最新的 ccr-release-*.tar.gz。
可覆盖环境变量:
  CCR_BACKEND_CONTAINER  后端容器名或 ID（默认自动识别）
  CCR_BACKEND_JAR_PATH   容器内 Jar 路径（默认 /app/app.jar）
  CCR_FRONTEND_DIR       宿主机前端目录（默认 /data/ccr/frontend）
  CCR_BACKUP_DIR         备份根目录（默认 /data/ccr/backup）
  CCR_BACKEND_HEALTH_URL 后端就绪检查地址（默认 http://127.0.0.1:8080/auth/login）
EOF
}

if [[ -z "${MODE}" ]]; then
  printf '请选择部署内容（1=后端，2=前端，12=全部）: '
  read -r MODE
fi
case "${MODE}" in
  1|2|12) ;;
  -h|--help) usage; exit 0 ;;
  *) usage >&2; exit 2 ;;
esac

if [[ -z "${ARCHIVE_PATH}" ]]; then
  shopt -s nullglob
  release_archives=(/tmp/ccr-release-*.tar.gz)
  shopt -u nullglob
  if [[ "${#release_archives[@]}" -gt 0 ]]; then
    ARCHIVE_PATH="$(ls -1t "${release_archives[@]}" | head -n 1)"
  fi
fi
[[ -n "${ARCHIVE_PATH}" && -f "${ARCHIVE_PATH}" ]] || {
  echo "未找到发布包，请把 ccr-release-*.tar.gz 上传到 /tmp。" >&2
  exit 1
}

for command_name in docker tar find; do
  command -v "${command_name}" >/dev/null 2>&1 || {
    echo "缺少命令: ${command_name}" >&2
    exit 1
  }
done

# 解压前拒绝绝对路径和上级目录，避免异常压缩包越界写入。
if LC_ALL=C tar -tzf "${ARCHIVE_PATH}" | grep -Eq '(^/|(^|/)\.\.(/|$))'; then
  echo "发布包包含不安全路径，已拒绝解压。" >&2
  exit 1
fi

WORK_DIR="$(mktemp -d /tmp/ccr-deploy.XXXXXX)"
cleanup() {
  [[ -n "${WORK_DIR}" && -d "${WORK_DIR}" ]] && rm -rf -- "${WORK_DIR}"
}
trap cleanup EXIT

LC_ALL=C tar -xzf "${ARCHIVE_PATH}" -C "${WORK_DIR}"
PACKAGE_DIR="$(find "${WORK_DIR}" -mindepth 1 -maxdepth 1 -type d -name 'ccr-release-*' | head -n 1)"
[[ -n "${PACKAGE_DIR}" ]] || { echo "发布包目录结构无效。" >&2; exit 1; }

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
  if [[ "${MODE}" == "1" || "${MODE}" == "12" ]]; then
    [[ -s "${PACKAGE_DIR}/backend/ccr-admin.jar" ]] || {
      echo "所选模式需要 backend/ccr-admin.jar，当前发布包未包含。" >&2
      exit 1
    }
  fi
  if [[ "${MODE}" == "2" || "${MODE}" == "12" ]]; then
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

verify_checksums
validate_payload
BACKUP_ROOT="${CCR_BACKUP_DIR:-/data/ccr/backup}"
[[ -n "${BACKUP_ROOT}" && "${BACKUP_ROOT}" != "/" ]] || { echo "备份目录无效。" >&2; exit 1; }
BACKUP_DIR="${BACKUP_ROOT}/$(date +%Y%m%d-%H%M%S)"
mkdir -p "${BACKUP_DIR}"

case "${MODE}" in
  1) deploy_backend ;;
  2) deploy_frontend ;;
  12) deploy_backend; deploy_frontend ;;
esac

echo
echo "==> 部署完成"
echo "    发布包: ${ARCHIVE_PATH}"
echo "    回滚备份: ${BACKUP_DIR}"
echo "    临时解压目录已自动清理；上传包保留在 /tmp。"
