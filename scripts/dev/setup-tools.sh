#!/usr/bin/env bash

set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

readonly JDK_VERSION="17.0.20_8"
readonly JDK_ARCHIVE="OpenJDK17U-jdk_aarch64_mac_hotspot_${JDK_VERSION}.tar.gz"
readonly JDK_URL="https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20%2B8/${JDK_ARCHIVE}"
readonly JDK_SHA256="524850138c742324fb21fca4ff6ef68ea25f25bf59366a864e45b4a0c45ed0df"
readonly MAVEN_VERSION="3.9.16"
readonly MAVEN_ARCHIVE="apache-maven-${MAVEN_VERSION}-bin.tar.gz"
readonly MAVEN_URL="https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/${MAVEN_ARCHIVE}"
readonly MAVEN_SHA512="831a8591fe20c8243b1dbe7d71e3244f31d1665b0804b2e825e38cbbe5ce0cafb8338851f90780735568773e0a6cd07bbec107cda0b896b008b861075358b6f6"

verify_sha256() {
  local expected="$1"
  local file="$2"
  local actual
  actual="$(shasum -a 256 "${file}" | awk '{print $1}')"
  [[ "${actual}" == "${expected}" ]] || ccr_die "SHA-256 校验失败: ${file}"
}

verify_sha512() {
  local expected="$1"
  local file="$2"
  local actual
  actual="$(shasum -a 512 "${file}" | awk '{print $1}')"
  [[ "${actual}" == "${expected}" ]] || ccr_die "SHA-512 校验失败: ${file}"
}

install_jdk() {
  if [[ -x "${CCR_JDK_HOME}/bin/java" ]]; then
    echo "JDK 17 已存在: ${CCR_JDK_HOME}"
    return
  fi
  [[ "$(uname -s)" == "Darwin" && "$(uname -m)" == "arm64" ]] \
    || ccr_die "当前引导脚本固定支持 macOS arm64；其他平台请使用 Docker 构建或补充已校验的 JDK 包"

  local downloads_dir="${CCR_TOOLS_DIR}/downloads"
  local archive_path="${downloads_dir}/${JDK_ARCHIVE}"
  local extract_dir="${CCR_TOOLS_DIR}/jdk-extract-${JDK_VERSION}"
  mkdir -p "${downloads_dir}"
  if [[ ! -f "${archive_path}" ]]; then
    echo "下载 Temurin JDK 17.0.20+8..."
    curl -fL --retry 3 --retry-delay 2 "${JDK_URL}" -o "${archive_path}"
  fi
  verify_sha256 "${JDK_SHA256}" "${archive_path}"
  rm -rf "${extract_dir}"
  mkdir -p "${extract_dir}"
  tar -xzf "${archive_path}" -C "${extract_dir}"
  local extracted_jdk
  extracted_jdk="$(find "${extract_dir}" -mindepth 1 -maxdepth 1 -type d -exec test -x '{}/Contents/Home/bin/java' ';' -print -quit)"
  [[ -n "${extracted_jdk}" ]] || ccr_die "JDK 解压目录异常"
  mv "${extracted_jdk}" "${CCR_TOOLS_DIR}/jdk-17"
  rm -rf "${extract_dir}"
  echo "JDK 已安装到 ${CCR_TOOLS_DIR}/jdk-17"
}

system_maven_compatible() {
  command -v mvn >/dev/null 2>&1 || return 1
  local version
  version="$(mvn -version 2>/dev/null | sed -n '1s/Apache Maven \([0-9][0-9.]*\).*/\1/p')"
  [[ "${version}" =~ ^3\.9\. ]]
}

install_maven_if_needed() {
  if [[ -x "${CCR_MAVEN_HOME}/bin/mvn" ]]; then
    echo "项目 Maven 已存在: ${CCR_MAVEN_HOME}"
    return
  fi
  if system_maven_compatible; then
    echo "复用兼容的系统 Maven: $(command -v mvn)"
    return
  fi

  local downloads_dir="${CCR_TOOLS_DIR}/downloads"
  local archive_path="${downloads_dir}/${MAVEN_ARCHIVE}"
  local extract_dir="${CCR_TOOLS_DIR}/maven-extract-${MAVEN_VERSION}"
  mkdir -p "${downloads_dir}"
  if [[ ! -f "${archive_path}" ]]; then
    echo "下载 Apache Maven ${MAVEN_VERSION}..."
    curl -fL --retry 3 --retry-delay 2 "${MAVEN_URL}" -o "${archive_path}"
  fi
  verify_sha512 "${MAVEN_SHA512}" "${archive_path}"
  rm -rf "${extract_dir}"
  mkdir -p "${extract_dir}"
  tar -xzf "${archive_path}" -C "${extract_dir}" --strip-components=1
  mv "${extract_dir}" "${CCR_MAVEN_HOME}"
  echo "Maven 已安装到 ${CCR_MAVEN_HOME}"
}

check_node() {
  ccr_require_command node
  ccr_require_command npm
  local node_major
  node_major="$(node -p 'Number(process.versions.node.split(".")[0])')"
  (( node_major >= 20 )) || ccr_die "Node.js 版本过低，需要 20 或更高版本"
  echo "复用兼容的系统 Node.js: $(command -v node) ($(node --version))"
}

ccr_require_command curl
ccr_require_command shasum
ccr_require_command tar
ccr_prepare_dirs
install_jdk
install_maven_if_needed
check_node

ccr_activate_java
echo
echo "项目工具链就绪:"
java -version 2>&1 | sed -n '1p'
"$(ccr_maven_bin)" -version | sed -n '1p'
node --version
npm --version
