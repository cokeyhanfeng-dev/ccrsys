#!/usr/bin/env bash

# 该脚本由 MySQL 官方 entrypoint 以 source 方式加载，可直接复用 docker_process_sql。
# 初始化顺序：冻结基线结构/基础种子 -> 全部增量 -> 模拟与测试数据。
ccr_db_root="${CCR_DB_ROOT:-/opt/ccrsys/db}"

if [[ ! -d "${ccr_db_root}" ]]; then
  echo "CCRSYS 数据库初始化目录不存在: ${ccr_db_root}" >&2
  return 1
fi

shopt -s nullglob
ccr_baseline_files=(
  "${ccr_db_root}/01_init.sql"
  "${ccr_db_root}/02_external_data.sql"
  "${ccr_db_root}/03a_business.sql"
  "${ccr_db_root}/03b_vote.sql"
  "${ccr_db_root}/03c_snapshot.sql"
  "${ccr_db_root}/03d_commitment.sql"
  "${ccr_db_root}/03e_config.sql"
  "${ccr_db_root}/03f_node_assignee.sql"
  "${ccr_db_root}/04_workflow.sql"
  "${ccr_db_root}/05_seed.sql"
  "${ccr_db_root}/06_rule_seed.sql"
  "${ccr_db_root}/07_rate_matrix.sql"
  "${ccr_db_root}/08_system.sql"
  "${ccr_db_root}/09_system_dept.sql"
  "${ccr_db_root}/11_system_increment.sql"
  "${ccr_db_root}/12_notification_seed.sql"
  "${ccr_db_root}/13_cache_config.sql"
  "${ccr_db_root}/14_cache_config_upgrade.sql"
  "${ccr_db_root}/16_credit_info.sql"
  "${ccr_db_root}/18_contract_operator.sql"
  "${ccr_db_root}/19_application_commitment_end_date.sql"
  "${ccr_db_root}/25_metric_definition_seed.sql"
)
ccr_increment_files=("${ccr_db_root}"/incr/*.sql)
ccr_test_data_files=(
  "${ccr_db_root}/10_mock_external.sql"
  "${ccr_db_root}/15_test_data.sql"
  "${ccr_db_root}/17_test_data_latest.sql"
  "${ccr_db_root}/20_clean_biz.sql"
  "${ccr_db_root}/22_test_data_multi_item.sql"
  "${ccr_db_root}/26_clear_test_data.sql"
)

ccr_require_sql_files() {
  local sql_file
  for sql_file in "$@"; do
    if [[ ! -f "${sql_file}" ]]; then
      echo "CCRSYS 数据库初始化文件不存在: ${sql_file}" >&2
      return 1
    fi
  done
}

ccr_run_sql_files() {
  local phase="$1"
  shift

  local sql_file
  for sql_file in "$@"; do
    mysql_note "CCRSYS ${phase}: ${sql_file}"
    docker_process_sql < "${sql_file}"
  done
}

ccr_require_sql_files "${ccr_baseline_files[@]}" "${ccr_test_data_files[@]}"

if (( ${#ccr_increment_files[@]} == 0 )); then
  echo "CCRSYS 数据库增量目录中没有 SQL 文件: ${ccr_db_root}/incr" >&2
  return 1
fi

ccr_run_sql_files "baseline" "${ccr_baseline_files[@]}"
ccr_run_sql_files "increment" "${ccr_increment_files[@]}"
ccr_run_sql_files "test-data" "${ccr_test_data_files[@]}"
