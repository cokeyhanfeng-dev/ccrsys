#!/usr/bin/env bash
# CCR 轻量发布包构建：1 后端、2 前端、12 全部。
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RELEASE_DIR="${ROOT_DIR}/release"
MODE="${1:-}"

if [[ -z "${MODE}" ]]; then
  printf '请选择打包内容（1=后端，2=前端，12=全部）: '
  read -r MODE
fi

case "${MODE}" in
  1) LABEL="backend" ;;
  2) LABEL="frontend" ;;
  12) LABEL="full" ;;
  *)
    echo "用法: ./dev release [1|2|12]" >&2
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

if [[ "${MODE}" == "1" || "${MODE}" == "12" ]]; then
  echo "==> [后端] 构建 ccr-admin.jar"
  "${ROOT_DIR}/dev" mvn -pl ccr-admin -am -DskipTests clean package
  test -s "${ROOT_DIR}/backend/ccr-admin/target/ccr-admin.jar"
  cp "${ROOT_DIR}/backend/ccr-admin/target/ccr-admin.jar" "${BUNDLE_DIR}/backend/ccr-admin.jar"
fi

if [[ "${MODE}" == "2" || "${MODE}" == "12" ]]; then
  echo "==> [前端] 构建生产 dist"
  "${ROOT_DIR}/dev" frontend-test
  test -f "${ROOT_DIR}/frontend/dist/index.html"
  mkdir -p "${BUNDLE_DIR}/frontend/dist"
  cp -R "${ROOT_DIR}/frontend/dist/." "${BUNDLE_DIR}/frontend/dist/"
fi

cp "${ROOT_DIR}/scripts/deploy-release.sh" "${BUNDLE_DIR}/deploy-release.sh"
chmod 0755 "${BUNDLE_DIR}/deploy-release.sh"

GIT_COMMIT="$(git -C "${ROOT_DIR}" rev-parse --short HEAD 2>/dev/null || echo unknown)"
cat > "${BUNDLE_DIR}/MANIFEST" <<EOF
package=${BUNDLE_NAME}
mode=${MODE}
build_time=${BUILD_TIME}
git_commit=${GIT_COMMIT}
EOF

(
  cd "${BUNDLE_DIR}"
  find backend frontend -type f | LC_ALL=C sort | while IFS= read -r file_path; do
    LC_ALL=C shasum -a 256 "${file_path}"
  done > SHA256SUMS
)

# macOS 会给新文件附加 com.apple.* 扩展属性；发布到 Linux 前清理并禁止写入 tar。
if command -v xattr >/dev/null 2>&1; then
  xattr -cr "${BUNDLE_DIR}"
fi
COPYFILE_DISABLE=1 LC_ALL=C tar --no-xattrs --no-mac-metadata \
  -czf "${ARCHIVE_PATH}" -C "${STAGE_ROOT}" "${BUNDLE_NAME}"
cp "${ROOT_DIR}/scripts/deploy-release.sh" "${RELEASE_DIR}/deploy-release.sh"
chmod 0755 "${RELEASE_DIR}/deploy-release.sh"

echo
echo "==> 打包完成"
echo "    发布包: ${ARCHIVE_PATH}"
echo "    服务器脚本: ${RELEASE_DIR}/deploy-release.sh"
echo "    上传这两个文件到服务器 /tmp 后执行:"
echo "    bash /tmp/deploy-release.sh ${MODE}"
