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

assert_mysql_duplicate_rejected() {
  local label="$1"
  local sql="$2"
  local output
  local status
  set +e
  output="$(ccr_compose exec -T mysql mysql -uroot -proot123 ccr_rate -e "${sql}" 2>&1)"
  status=$?
  set -e
  [[ "${status}" -ne 0 && "${output}" == *"Duplicate entry"* ]] \
    || ccr_die "${label} 活动行唯一约束未拒绝重复数据"
}

ccr_require_command curl
ccr_compose ps --status running

echo "[1/6] MySQL"
ccr_compose exec -T mysql mysqladmin ping -h 127.0.0.1 -uroot -proot123 --silent
table_count="$(ccr_compose exec -T mysql mysql -N -uroot -proot123 -e \
  "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='ccr_rate';" | tr -d '\r')"
[[ "${table_count}" =~ ^[0-9]+$ && "${table_count}" -ge 90 ]] \
  || ccr_die "数据库表数量异常: ${table_count}"
echo "  ccr_rate 表数量: ${table_count}"

snapshot_unique_count="$(ccr_compose exec -T mysql mysql -N -uroot -proot123 -e \
  "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='ccr_rate' AND table_name='ccr_snapshot_bundle' AND index_name='uk_snapshot_application' AND non_unique=0;" | tr -d '\r')"
[[ "${snapshot_unique_count}" == "1" ]] \
  || ccr_die "快照申请唯一约束缺失: uk_snapshot_application"

# 在同一事务内写两个 application_id 相同的快照包，第二次 INSERT 必须命中唯一键；
# mysql 客户端因错误退出后未提交事务自动回滚，不保留冒烟数据。
set +e
snapshot_duplicate_output="$(ccr_compose exec -T mysql mysql -uroot -proot123 ccr_rate -e \
  "START TRANSACTION;
   INSERT INTO ccr_snapshot_bundle
     (id,tenant_id,business_no,org_id,status,version_no,create_by,bundle_no,application_id,record_count,del_flag)
   VALUES
     (-9000001,'000000','SMOKE-SNAPSHOT-1',0,'FREEZING',1,0,'SMOKE-SNAPSHOT-1',-9000001,0,'0');
   INSERT INTO ccr_snapshot_bundle
     (id,tenant_id,business_no,org_id,status,version_no,create_by,bundle_no,application_id,record_count,del_flag)
   VALUES
     (-9000002,'000000','SMOKE-SNAPSHOT-2',0,'FREEZING',1,0,'SMOKE-SNAPSHOT-2',-9000001,0,'0');
   ROLLBACK;" 2>&1)"
snapshot_duplicate_status=$?
set -e
[[ "${snapshot_duplicate_status}" -ne 0 && "${snapshot_duplicate_output}" == *"Duplicate entry"* ]] \
  || ccr_die "同申请重复快照包未被数据库唯一键拒绝"
echo "  快照申请唯一约束: OK"

draft_active_key_count="$(ccr_compose exec -T mysql mysql -N -uroot -proot123 -e \
  "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='ccr_rate' AND index_name IN ('uk_active_app_member','uk_active_app_contract','uk_active_app_account') AND non_unique=0;" | tr -d '\r')"
[[ "${draft_active_key_count}" == "6" ]] \
  || ccr_die "草稿子表活动行唯一索引结构异常: ${draft_active_key_count} 个索引列"

assert_mysql_duplicate_rejected "集团成员" \
  "START TRANSACTION;
   INSERT INTO ccr_application_member
     (id,tenant_id,business_no,org_id,status,version_no,create_by,del_flag,application_id,member_customer_no,request_amount,currency)
   VALUES (-9100001,'000000','SMOKE-MEMBER-1',0,'ACTIVE',1,0,'0',-9100001,'SMOKE-MEMBER',1,'CNY');
   UPDATE ccr_application_member SET del_flag='1' WHERE id=-9100001;
   INSERT INTO ccr_application_member
     (id,tenant_id,business_no,org_id,status,version_no,create_by,del_flag,application_id,member_customer_no,request_amount,currency)
   VALUES (-9100002,'000000','SMOKE-MEMBER-2',0,'ACTIVE',1,0,'0',-9100001,'SMOKE-MEMBER',1,'CNY');
   UPDATE ccr_application_member SET del_flag='1' WHERE id=-9100002;
   INSERT INTO ccr_application_member
     (id,tenant_id,business_no,org_id,status,version_no,create_by,del_flag,application_id,member_customer_no,request_amount,currency)
   VALUES (-9100003,'000000','SMOKE-MEMBER-3',0,'ACTIVE',1,0,'0',-9100001,'SMOKE-MEMBER',1,'CNY');
   INSERT INTO ccr_application_member
     (id,tenant_id,business_no,org_id,status,version_no,create_by,del_flag,application_id,member_customer_no,request_amount,currency)
   VALUES (-9100004,'000000','SMOKE-MEMBER-4',0,'ACTIVE',1,0,'0',-9100001,'SMOKE-MEMBER',1,'CNY');
   ROLLBACK;"

assert_mysql_duplicate_rejected "贷款合同关系" \
  "START TRANSACTION;
   INSERT INTO ccr_pricing_item_contract_rel
     (id,tenant_id,business_no,org_id,status,version_no,create_by,del_flag,application_id,pricing_item_id,contract_business_key,planned_contract_flag)
   VALUES (-9200001,'000000','SMOKE-CONTRACT-1',0,'ACTIVE',1,0,'0',-9200001,-9200011,'SMOKE-CONTRACT','N');
   UPDATE ccr_pricing_item_contract_rel SET del_flag='1' WHERE id=-9200001;
   INSERT INTO ccr_pricing_item_contract_rel
     (id,tenant_id,business_no,org_id,status,version_no,create_by,del_flag,application_id,pricing_item_id,contract_business_key,planned_contract_flag)
   VALUES (-9200002,'000000','SMOKE-CONTRACT-2',0,'ACTIVE',1,0,'0',-9200001,-9200012,'SMOKE-CONTRACT','N');
   UPDATE ccr_pricing_item_contract_rel SET del_flag='1' WHERE id=-9200002;
   INSERT INTO ccr_pricing_item_contract_rel
     (id,tenant_id,business_no,org_id,status,version_no,create_by,del_flag,application_id,pricing_item_id,contract_business_key,planned_contract_flag)
   VALUES (-9200003,'000000','SMOKE-CONTRACT-3',0,'ACTIVE',1,0,'0',-9200001,-9200013,'SMOKE-CONTRACT','N');
   INSERT INTO ccr_pricing_item_contract_rel
     (id,tenant_id,business_no,org_id,status,version_no,create_by,del_flag,application_id,pricing_item_id,contract_business_key,planned_contract_flag)
   VALUES (-9200004,'000000','SMOKE-CONTRACT-4',0,'ACTIVE',1,0,'0',-9200001,-9200014,'SMOKE-CONTRACT','N');
   ROLLBACK;"

assert_mysql_duplicate_rejected "存款账户关系" \
  "START TRANSACTION;
   INSERT INTO ccr_pricing_item_deposit_rel
     (id,tenant_id,business_no,org_id,status,version_no,create_by,del_flag,application_id,pricing_item_id,deposit_account_hash,planned_account_flag)
   VALUES (-9300001,'000000','SMOKE-ACCOUNT-1',0,'ACTIVE',1,0,'0',-9300001,-9300011,REPEAT('a',64),'N');
   UPDATE ccr_pricing_item_deposit_rel SET del_flag='1' WHERE id=-9300001;
   INSERT INTO ccr_pricing_item_deposit_rel
     (id,tenant_id,business_no,org_id,status,version_no,create_by,del_flag,application_id,pricing_item_id,deposit_account_hash,planned_account_flag)
   VALUES (-9300002,'000000','SMOKE-ACCOUNT-2',0,'ACTIVE',1,0,'0',-9300001,-9300012,REPEAT('a',64),'N');
   UPDATE ccr_pricing_item_deposit_rel SET del_flag='1' WHERE id=-9300002;
   INSERT INTO ccr_pricing_item_deposit_rel
     (id,tenant_id,business_no,org_id,status,version_no,create_by,del_flag,application_id,pricing_item_id,deposit_account_hash,planned_account_flag)
   VALUES (-9300003,'000000','SMOKE-ACCOUNT-3',0,'ACTIVE',1,0,'0',-9300001,-9300013,REPEAT('a',64),'N');
   INSERT INTO ccr_pricing_item_deposit_rel
     (id,tenant_id,business_no,org_id,status,version_no,create_by,del_flag,application_id,pricing_item_id,deposit_account_hash,planned_account_flag)
   VALUES (-9300004,'000000','SMOKE-ACCOUNT-4',0,'ACTIVE',1,0,'0',-9300001,-9300014,REPEAT('a',64),'N');
   ROLLBACK;"
echo "  草稿子表历史重建与活动唯一约束: OK"

frozen_route_column_count="$(ccr_compose exec -T mysql mysql -N -uroot -proot123 -e \
  "SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema='ccr_rate' AND table_name='ccr_pricing_item'
     AND column_name IN ('hard_boundary_rate','product_route_id','product_route_version','route_mode',
                         'route_chain_json','node_permission_json','president_required','flow_key');" | tr -d '\r')"
[[ "${frozen_route_column_count}" == "8" ]] \
  || ccr_die "分项冻结路由计划字段不完整: ${frozen_route_column_count}/8"

legacy_route_missing_count="$(ccr_compose exec -T mysql mysql -N -uroot -proot123 -e \
  "SELECT COUNT(*) FROM ccr_rate.ccr_pricing_item
   WHERE route_chain_json IS NULL OR route_mode IS NULL
      OR president_required IS NULL OR flow_key IS NULL;" | tr -d '\r')"
[[ "${legacy_route_missing_count}" == "0" ]] \
  || ccr_die "历史分项冻结路由兼容回填不完整: ${legacy_route_missing_count} 行"
echo "  分项完整路由计划字段与历史兼容回填: OK"

echo "[2/6] Redis"
redis_pong="$(ccr_compose exec -T redis redis-cli ping | tr -d '\r')"
[[ "${redis_pong}" == "PONG" ]] || ccr_die "Redis PING 失败"

echo "[3/6] 后端健康检查"
health_file="${CCR_CACHE_DIR}/smoke-health.json"
retry_http "http://127.0.0.1:18080/health" "${health_file}" || ccr_die "后端健康检查失败"
grep -q '"status":"UP"' "${health_file}" || ccr_die "后端健康响应异常"

echo "[4/6] 登录与数据库链路"
login_file="${CCR_CACHE_DIR}/smoke-login.json"
curl -fsS -X POST "http://127.0.0.1:18080/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"123456"}' \
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
