#!/usr/bin/env bash

set -euo pipefail

CCR_PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CCR_TOOLS_DIR="${CCR_PROJECT_ROOT}/.tools"
CCR_CACHE_DIR="${CCR_PROJECT_ROOT}/.cache"
CCR_JDK_HOME="${CCR_TOOLS_DIR}/jdk-17/Contents/Home"
CCR_MAVEN_HOME="${CCR_TOOLS_DIR}/maven"
CCR_COMPOSE_FILE="${CCR_PROJECT_ROOT}/compose.test.yml"

export CCR_PROJECT_ROOT CCR_TOOLS_DIR CCR_CACHE_DIR CCR_JDK_HOME CCR_MAVEN_HOME CCR_COMPOSE_FILE

ccr_die() {
  echo "error: $*" >&2
  exit 1
}

ccr_require_command() {
  command -v "$1" >/dev/null 2>&1 || ccr_die "缺少命令: $1"
}

ccr_prepare_dirs() {
  mkdir -p "${CCR_TOOLS_DIR}" "${CCR_CACHE_DIR}/m2/repository" "${CCR_CACHE_DIR}/npm"
}

ccr_activate_java() {
  [[ -x "${CCR_JDK_HOME}/bin/java" ]] || ccr_die "项目 JDK 17 尚未安装，请先执行 ./dev setup"
  export JAVA_HOME="${CCR_JDK_HOME}"
  export PATH="${JAVA_HOME}/bin:${PATH}"
}

ccr_maven_bin() {
  if [[ -x "${CCR_MAVEN_HOME}/bin/mvn" ]]; then
    printf '%s\n' "${CCR_MAVEN_HOME}/bin/mvn"
    return
  fi
  command -v mvn >/dev/null 2>&1 || ccr_die "Maven 不可用，请重新执行 ./dev setup"
  command -v mvn
}

ccr_mvn() {
  ccr_prepare_dirs
  ccr_activate_java
  local maven_bin
  maven_bin="$(ccr_maven_bin)"
  "${maven_bin}" -Dmaven.repo.local="${CCR_CACHE_DIR}/m2/repository" "$@"
}

ccr_npm() {
  ccr_prepare_dirs
  ccr_require_command node
  ccr_require_command npm
  npm --cache "${CCR_CACHE_DIR}/npm" "$@"
}

ccr_compose() {
  ccr_require_command docker
  docker info >/dev/null 2>&1 || ccr_die "Docker Desktop 未运行，请先启动 Docker Desktop"
  docker compose -f "${CCR_COMPOSE_FILE}" "$@"
}
