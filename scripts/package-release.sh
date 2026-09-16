#!/usr/bin/env bash
# CCR 轻量发布包构建：1 后端、2 电脑端、3 移动端；数字组合选择。
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RELEASE_DIR="${ROOT_DIR}/release"
MODE="${1:-}"

if [[ -z "${MODE}" ]]; then
  printf '请选择打包内容（1=后端，2=电脑端，3=移动端，13=后端+移动，123=全部）: '
  read -r MODE
fi

case "${MODE}" in
  1) LABEL="backend" ;;
  2) LABEL="frontend" ;;
  12) LABEL="full" ;;
  3) LABEL="mobile" ;;
  13) LABEL="backend-mobile" ;;
  23) LABEL="web-mobile" ;;
  123) LABEL="all" ;;
  *)
    echo "用法: ./dev release [1|2|3|12|13|23|123]" >&2
    exit 2
    ;;
esac

for command_name in tar shasum; do
  command -v "${command_name}" >/dev/null 2>&1 || {
    echo "缺少命令: ${command_name}" >&2
    exit 1
  }
done

BUILD_TIME="$(date +%Y%m%d-%H%M%S)"
BUNDLE_NAME="ccr-release-${BUILD_TIME}-${LABEL}"
STAGE_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/ccr-release.XXXXXX")"
BUNDLE_DIR="${STAGE_ROOT}/${BUNDLE_NAME}"
ARCHIVE_PATH="${RELEASE_DIR}/${BUNDLE_NAME}.tar.gz"

cleanup() {
  rm -rf -- "${STAGE_ROOT}"
}
trap cleanup EXIT

mkdir -p "${BUNDLE_DIR}/backend" "${BUNDLE_DIR}/frontend" "${RELEASE_DIR}"

if [[ "${MODE}" == *1* ]]; then
  echo "==> [后端] 构建 ccr-admin.jar"
  "${ROOT_DIR}/dev" mvn -pl ccr-admin -am clean package
  test -s "${ROOT_DIR}/backend/ccr-admin/target/ccr-admin.jar"
  cp "${ROOT_DIR}/backend/ccr-admin/target/ccr-admin.jar" "${BUNDLE_DIR}/backend/ccr-admin.jar"
fi

if [[ "${MODE}" == *2* ]]; then
  echo "==> [前端] 构建生产 dist"
  "${ROOT_DIR}/dev" frontend-test
  test -f "${ROOT_DIR}/frontend/dist/index.html"
  mkdir -p "${BUNDLE_DIR}/frontend/dist"
  cp -R "${ROOT_DIR}/frontend/dist/." "${BUNDLE_DIR}/frontend/dist/"
fi

if [[ "${MODE}" == *3* ]]; then
  # frontend-test 已同时构建移动端，组合模式避免重复安装和构建。
  if [[ "${MODE}" != *2* ]]; then "${ROOT_DIR}/dev" mobile-build; fi
  test -s "${ROOT_DIR}/frontend/dist-mobile/index.html"
  mkdir -p "${BUNDLE_DIR}/frontend/dist-mobile"
  cp -R "${ROOT_DIR}/frontend/dist-mobile/." "${BUNDLE_DIR}/frontend/dist-mobile/"
fi
mkdir -p "${BUNDLE_DIR}/deployment"
cp -R "${ROOT_DIR}/docs/deployment/mobile/." "${BUNDLE_DIR}/deployment/"

cp "${ROOT_DIR}/scripts/deploy-release.sh" "${BUNDLE_DIR}/deploy-release.sh"
chmod 0755 "${BUNDLE_DIR}/deploy-release.sh"

GIT_COMMIT="$(git -C "${ROOT_DIR}" rev-parse --short HEAD 2>/dev/null || echo unknown)"
cat > "${BUNDLE_DIR}/MANIFEST" <<EOF
package=${BUNDLE_NAME}
mode=${MODE}
build_time=${BUILD_TIME}
git_commit=${GIT_COMMIT}
working_tree_dirty=$(test -z "$(git -C "${ROOT_DIR}" status --porcelain)" && echo false || echo true)
EOF

(
  cd "${BUNDLE_DIR}"
  find backend frontend deployment -type f | LC_ALL=C sort | while IFS= read -r file_path; do
    LC_ALL=C shasum -a 256 "${file_path}"
  done > SHA256SUMS
)

# macOS 会给新文件附加 com.apple.* 扩展属性；发布到 Linux 前清理并禁止写入 tar。
if command -v xattr >/dev/null 2>&1; then
  xattr -cr "${BUNDLE_DIR}"
fi
tar_options=()
if [[ "$(uname -s)" == Darwin ]]; then tar_options=(--no-xattrs --no-mac-metadata); fi
COPYFILE_DISABLE=1 LC_ALL=C tar "${tar_options[@]}" \
  -czf "${ARCHIVE_PATH}" -C "${STAGE_ROOT}" "${BUNDLE_NAME}"
cp "${ROOT_DIR}/scripts/deploy-release.sh" "${RELEASE_DIR}/deploy-release.sh"
chmod 0755 "${RELEASE_DIR}/deploy-release.sh"

echo
echo "==> 打包完成"
echo "    发布包: ${ARCHIVE_PATH}"
echo "    服务器脚本: ${RELEASE_DIR}/deploy-release.sh"
echo "    上传这两个文件到服务器 /tmp 后执行:"
echo "    bash /tmp/deploy-release.sh ${MODE} /tmp/${BUNDLE_NAME}.tar.gz"
