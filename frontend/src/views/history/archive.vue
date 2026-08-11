<template>
  <div>
    <div class="section-head">
      <div class="section-title">
        申请档案
        <span v-if="archive.application" class="app-no">{{ val(archive.application, 'application_no', 'applicationNo') }}</span>
      </div>
      <div class="section-tip">申请档案(§14.4):仅展示申请过程中填写的材料、审批轨迹与当前审批状态;表决汇总、行长决议、执行核验、承诺履约等审批后产物不在档案展示。</div>
      <div class="head-actions">
        <button class="btn btn--secondary" @click="router.push('/history')">返回列表</button>
        <button v-if="canExport" class="btn btn--primary" :disabled="exporting" @click="doExport">
          {{ exporting ? '导出中…' : '导出档案' }}
        </button>
      </div>
    </div>

    <div v-if="loading" class="card empty-block">加载中…</div>
    <template v-else-if="archive.application">
      <!-- 1. 申请内容 -->
      <div class="card">
        <div class="card__head"><span>申请内容</span><span :class="badgeClass(val(archive.application, 'status'))">{{ appStatusText(val(archive.application, 'status')) }}</span></div>
        <div class="desc-grid">
          <div class="desc-item"><span class="desc-label">申请号</span>{{ val(archive.application, 'application_no', 'applicationNo') }}</div>
          <div class="desc-item"><span class="desc-label">业务类型</span>{{ businessTypeText(val(archive.application, 'business_type', 'businessType')) }}</div>
          <div class="desc-item"><span class="desc-label">客户范围</span>{{ customerScopeText(val(archive.application, 'customer_scope', 'customerScope')) }}</div>
          <div class="desc-item"><span class="desc-label">客户号</span>{{ val(archive.application, 'customer_no', 'customerNo') }}</div>
          <div class="desc-item"><span class="desc-label">集团号</span>{{ val(archive.application, 'group_no', 'groupNo') }}</div>
          <div class="desc-item"><span class="desc-label">提交时间</span>{{ fmtTime(val(archive.application, 'submit_time', 'submitTime')) }}</div>
          <div class="desc-item"><span class="desc-label">终态时间</span>{{ fmtTime(val(archive.application, 'final_time', 'finalTime')) }}</div>
          <div class="desc-item"><span class="desc-label">关联原申请</span>{{ val(archive.application, 'source_application_id', 'sourceApplicationId') }}</div>
          <div class="desc-item desc-item--full"><span class="desc-label">客户经理备注</span>{{ val(archive.application, 'application_remark', 'applicationRemark') }}</div>
        </div>
      </div>

      <!-- 2. 集团与成员 -->
      <div class="card" v-if="isGroup">
        <div class="card__head"><span>集团成员</span></div>
        <table class="table" v-if="archive.members?.length">
          <thead><tr><th>成员客户号</th><th>成员角色</th><th>申请金额(万元)</th></tr></thead>
          <tbody>
            <tr v-for="(m, i) in archive.members" :key="i">
              <td>{{ val(m, 'member_customer_no', 'memberCustomerNo') }}</td>
              <td>{{ memberRoleText(val(m, 'member_role', 'memberRole')) }}</td>
              <td class="num">{{ val(m, 'request_amount', 'requestAmount') }}</td>
            </tr>
          </tbody>
        </table>
        <div v-else class="empty-block">暂无数据</div>
      </div>

      <!-- 3. 贷款合同/存款账户 -->
      <div class="card">
        <div class="card__head"><span>贷款合同 / 存款账户</span></div>
        <table class="table" v-if="archive.contracts?.length">
          <thead><tr><th>分项</th><th>合同业务标识</th><th>正式合同号</th><th>拟签合同</th></tr></thead>
          <tbody>
            <tr v-for="(c, i) in archive.contracts" :key="i">
              <td>{{ val(c, 'pricingItemId', 'pricing_item_id') }}</td>
              <td>{{ val(c, 'contractBusinessKey', 'contract_business_key') }}</td>
              <td>{{ val(c, 'loanContractNo', 'loan_contract_no') }}</td>
              <td>{{ plannedText(val(c, 'plannedContractFlag', 'planned_contract_flag')) }}</td>
            </tr>
          </tbody>
        </table>
        <table class="table" v-if="archive.depositAccounts?.length" :style="archive.contracts?.length ? 'margin-top:8px' : ''">
          <thead><tr><th>分项</th><th>存款账号</th><th>拟开户</th></tr></thead>
          <tbody>
            <tr v-for="(d, i) in archive.depositAccounts" :key="i">
              <td>{{ val(d, 'pricingItemId', 'pricing_item_id') }}</td>
              <td>{{ val(d, 'depositAccountNoCipher', 'deposit_account_no_cipher') }}</td>
              <td>{{ plannedText(val(d, 'plannedAccountFlag', 'planned_account_flag')) }}</td>
            </tr>
          </tbody>
        </table>
        <div v-if="!archive.contracts?.length && !archive.depositAccounts?.length" class="empty-block">暂无数据</div>
      </div>

      <!-- 4. 合同下借据(数仓最新批次) -->
      <div class="card">
        <div class="card__head"><span>合同下借据</span><span v-if="archive.notes?.length" class="badge badge--info">数仓批次 {{ val(archive.notes[0], 'dataDt', 'data_dt') }}</span></div>
        <table class="table" v-if="archive.notes?.length">
          <thead>
            <tr><th>借据号</th><th>合同号</th><th>借据金额</th><th>借据余额</th><th>币种</th><th>执行利率</th><th>起息日</th><th>到期日</th><th>状态</th></tr>
          </thead>
          <tbody>
            <tr v-for="(n, i) in archive.notes" :key="i">
              <td>{{ val(n, 'loanNoteNo', 'loan_note_no') }}</td>
              <td>{{ val(n, 'contractNo', 'contract_no') }}</td>
              <td class="num">{{ val(n, 'loanAmount', 'loan_amount') }}</td>
              <td class="num">{{ val(n, 'loanBalance', 'loan_balance') }}</td>
              <td>{{ val(n, 'currency') }}</td>
              <td class="num">{{ rateText(val(n, 'executionRate', 'execution_rate')) }}</td>
              <td>{{ fmtDate(val(n, 'startDate', 'start_date')) }}</td>
              <td>{{ fmtDate(val(n, 'maturityDate', 'maturity_date')) }}</td>
              <td>{{ val(n, 'noteStatus', 'note_status') }}</td>
            </tr>
          </tbody>
        </table>
        <div v-else class="empty-block">暂无数据</div>
      </div>

      <!-- 5. 定价分项 -->
      <div class="card">
        <div class="card__head"><span>定价分项</span></div>
        <table class="table" v-if="archive.pricingItems?.length">
          <thead>
            <tr>
              <th>分项号</th><th>定价客户</th><th>产品</th><th>金额(万元)</th><th>期限</th>
              <th>申请利率</th><th>审批利率</th><th>最终利率</th><th>当前节点</th><th>终审岗位</th><th>状态</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="p in archive.pricingItems" :key="val(p, 'id')">
              <td>{{ val(p, 'pricing_item_no', 'pricingItemNo') }}</td>
              <td>{{ val(p, 'pricing_customer_no', 'pricingCustomerNo') }}</td>
              <td>{{ productName(val(p, 'product_code', 'productCode')) }}</td>
              <td class="num">{{ val(p, 'pricing_amount', 'pricingAmount') }}</td>
              <td>{{ termText(p) }}</td>
              <td class="num">{{ rateText(val(p, 'requested_rate', 'requestedRate')) }}</td>
              <td class="num">{{ rateText(val(p, 'current_approval_rate', 'currentApprovalRate')) }}</td>
              <td class="num"><b>{{ rateText(val(p, 'final_rate', 'finalRate')) }}</b></td>
              <td>{{ nodeLabel(val(p, 'current_node_code', 'currentNodeCode')) }}</td>
              <td>{{ nodeLabel(val(p, 'route_code', 'routeCode')) }}</td>
              <td><span :class="badgeClass(val(p, 'status'))">{{ itemStatusText(val(p, 'status')) }}</span></td>
            </tr>
          </tbody>
        </table>
        <div v-else class="empty-block">暂无数据</div>
      </div>

      <!-- 5b. 关联人(申请录入,按关联客户号补全基本信息/授信信息) -->
      <div class="card" v-if="archive.relatedPersons?.length">
        <div class="card__head"><span>关联人</span></div>
        <table class="table">
          <thead>
            <tr><th>姓名/名称</th><th>证件号</th><th>关系类型</th><th>行内客户号</th><th>企业性质</th><th>行业</th><th>信用等级</th><th>五级分类</th><th>职业</th><th>年收入</th><th>授信协议数</th><th>本行贷款余额(万元)</th></tr>
          </thead>
          <tbody>
            <tr v-for="(r, i) in archive.relatedPersons" :key="i">
              <td>{{ val(r, 'personName') }}</td>
              <td>{{ val(r, 'certNo') }}</td>
              <td>{{ val(r, 'relationType') }}</td>
              <td>{{ val(r, 'relatedCustomerNo') }}</td>
              <td v-if="r.custType === 'CORP'">{{ val(r, 'entpCharic') }}</td>
              <td v-else>—</td>
              <td v-if="r.custType === 'CORP'">{{ val(r, 'industry') }}</td>
              <td v-else>—</td>
              <td v-if="r.custType === 'CORP'">{{ val(r, 'creditLevel') }}</td>
              <td v-else>—</td>
              <td v-if="r.custType === 'CORP'">{{ val(r, 'fiveLevelClass') }}</td>
              <td v-else>—</td>
              <td v-if="r.custType === 'INDIV'">{{ val(r, 'occupation') }}</td>
              <td v-else>—</td>
              <td v-if="r.custType === 'INDIV'">{{ val(r, 'annualIncome') }}</td>
              <td v-else>—</td>
              <td class="num">{{ val(r, 'creditAgreementCount') }}</td>
              <td class="num">{{ val(r, 'loanBalanceTotal') }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- 5c. 拟达成贡献度(申请承诺指标) -->
      <div class="card" v-if="archive.commitments?.length">
        <div class="card__head"><span>拟达成贡献度</span></div>
        <table class="table">
          <thead>
            <tr><th>指标</th><th>目标类型</th><th>基线值</th><th>目标值</th><th>单位</th><th>范围</th><th>成员客户号</th><th>承诺描述</th></tr>
          </thead>
          <tbody>
            <tr v-for="(c, i) in archive.commitments" :key="i">
              <td>{{ metricName(val(c, 'metricCode')) }}</td>
              <td>{{ val(c, 'targetType') }}</td>
              <td class="num">{{ val(c, 'baselineValue') }}</td>
              <td class="num">{{ val(c, 'targetValue') }}</td>
              <td>{{ val(c, 'unit') }}</td>
              <td>{{ val(c, 'metricScope') }}</td>
              <td>{{ val(c, 'memberCustomerNo') }}</td>
              <td>{{ val(c, 'commitmentDesc') }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- 6. 数据快照日期 -->
      <div class="card">
        <div class="card__head"><span>数据快照</span></div>
        <table class="table" v-if="archive.snapshotBundles?.length">
          <thead><tr><th>快照包号</th><th>状态</th><th>冻结时间</th><th>记录数</th><th>摘要哈希</th></tr></thead>
          <tbody>
            <tr v-for="b in archive.snapshotBundles" :key="val(b, 'id')">
              <td>{{ val(b, 'bundleNo', 'bundle_no') }}</td>
              <td>{{ val(b, 'status') }}</td>
              <td>{{ fmtTime(val(b, 'freezeTime', 'freeze_time')) }}</td>
              <td class="num">{{ val(b, 'recordCount', 'record_count') }}</td>
              <td class="hash-cell">{{ val(b, 'bundleHash', 'bundle_hash') }}</td>
            </tr>
          </tbody>
        </table>
        <div v-else class="empty-block">暂无数据</div>
      </div>
<!-- 8. 审批轨迹 -->
      <div class="card">
        <div class="card__head"><span>审批轨迹</span></div>
        <table class="table" v-if="archive.approvalActions?.length">
          <thead>
            <tr><th>节点</th><th>动作</th><th>操作角色</th><th>调整前利率</th><th>调整后利率</th><th>意见</th><th>时间</th></tr>
          </thead>
          <tbody>
            <tr v-for="(a, i) in archive.approvalActions" :key="i">
              <td>{{ nodeLabel(val(a, 'node_code', 'nodeCode')) }}</td>
              <td><span :class="actionBadge(val(a, 'action_type', 'actionType'))">{{ actionText(val(a, 'action_type', 'actionType')) }}</span></td>
              <td>{{ roleText(val(a, 'operator_role', 'operatorRole')) }}</td>
              <td class="num">{{ rateText(val(a, 'before_rate', 'beforeRate')) }}</td>
              <td class="num">{{ rateText(val(a, 'after_rate', 'afterRate')) }}</td>
              <td>{{ val(a, 'action_comment', 'actionComment') }}</td>
              <td>{{ fmtTime(val(a, 'operation_time', 'operationTime')) }}</td>
            </tr>
          </tbody>
        </table>
        <div v-else class="empty-block">暂无数据</div>
      </div>

    </template>
    <div v-else class="card empty-block">暂无数据</div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/store/user'
import { getArchive, exportArchive } from '@/api/history'
import {
  appStatusText, itemStatusText,
  businessTypeText, customerScopeText, nodeLabel, roleText, actionText,
  productName, metricName, memberRoleText, termUnitText
} from '@/utils/dict'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const applicationId = route.params.id as string
const archive = ref<Record<string, any>>({})
const loading = ref(true)
const exporting = ref(false)

// 导出口径(§15):仅 admin/auditor/president 可见
const canExport = computed(() => {
  const role = userStore.userInfo?.roles?.[0] || ''
  return ['admin', 'auditor', 'president'].includes(role)
})

const isGroup = computed(() => {
  const scope = val(archive.value.application || {}, 'customer_scope', 'customerScope')
  return scope === 'GROUP'
})

const qualityOverall = computed(() => {
  const list: any[] = archive.value.qualityResults || []
  if (list.some((q) => val(q, 'ruleLevel', 'rule_level') === 'BLOCK')) return 'BLOCK'
  if (list.some((q) => val(q, 'ruleLevel', 'rule_level') === 'WARN')) return 'WARN'
  return 'PASS'
})
const qualityBadge = computed(() => ({
  BLOCK: 'badge badge--danger',
  WARN: 'badge badge--warning',
  PASS: 'badge badge--success'
}[qualityOverall.value] || 'badge badge--neutral'))

/** 多键取值(后端档案 Map 混用 snake/camel;空值显示暂无口径) */
function val(row: any, ...keys: string[]): any {
  for (const k of keys) {
    if (row && row[k] !== null && row[k] !== undefined && row[k] !== '') return row[k]
  }
  return '—'
}
function fmtTime(t: any) {
  return t && t !== '—' ? String(t).replace('T', ' ').slice(0, 16) : '—'
}
function fmtDate(t: any) {
  return t && t !== '—' ? String(t).slice(0, 10) : '—'
}
function rateText(r: any) {
  return r !== null && r !== undefined && r !== '' && r !== '—' ? `${r}%` : '—'
}
function termText(p: any) {
  const v = val(p, 'term_value', 'termValue')
  if (v === '—') return '—'
  const unit = val(p, 'term_unit', 'termUnit')
  return `${v}${termUnitText(unit === '—' ? '' : unit)}`
}
function plannedText(f: any) {
  return f === 'Y' ? '是' : f === 'N' ? '否' : f || '—'
}
function badgeClass(s: any) {
  const map: Record<string, string> = {
    APPROVED: 'badge badge--success', PARTIAL_APPROVED: 'badge badge--success', ACHIEVED: 'badge badge--success',
    REJECTED: 'badge badge--danger', EXPIRED_UNMET: 'badge badge--danger', TERMINATED: 'badge badge--neutral',
    PROCESSING: 'badge badge--info', TRACKING: 'badge badge--info', SUBMITTING: 'badge badge--info',
    AT_RISK: 'badge badge--warning', DATA_PENDING: 'badge badge--warning', PENDING: 'badge badge--warning'
  }
  return map[s] || 'badge badge--neutral'
}
function actionBadge(a: any) {
  const map: Record<string, string> = {
    APPROVE: 'badge badge--success', REJECT: 'badge badge--danger',
    ADJUST: 'badge badge--warning', RETURN: 'badge badge--warning'
  }
  return map[a] || 'badge badge--info'
}

async function load() {
  loading.value = true
  try {
    archive.value = await getArchive(applicationId)
  } catch {
    archive.value = {}
  } finally {
    loading.value = false
  }
}

async function doExport() {
  exporting.value = true
  try {
    await exportArchive(applicationId)
    ElMessage.success('导出成功')
  } catch {
    // 错误提示由 download 封装统一处理
  } finally {
    exporting.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.app-no { font-size: 14px; color: var(--color-text-sub); font-weight: 400; margin-left: 8px; }
.head-actions { margin-top: 10px; display: flex; gap: 8px; }
.table { border-radius: var(--radius-sm); overflow-x: auto; }
.desc-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px 16px; font-size: 14px; }
.desc-item { display: flex; flex-direction: column; gap: 2px; }
.desc-item--full { grid-column: 1 / -1; }
.desc-label { font-size: 12px; color: var(--color-text-light); }
.empty-block { text-align: center; color: var(--color-text-light); padding: 20px 0; font-size: 13px; }
.hash-cell { font-size: 12px; color: var(--color-text-sub); word-break: break-all; }
</style>
