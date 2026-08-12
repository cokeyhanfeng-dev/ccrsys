<template>
  <div>
    <div class="section-head">
      <div class="section-title">
        申请档案
        <span v-if="archive.application" class="app-no">{{ val(archive.application, 'application_no', 'applicationNo') }}</span>
      </div>
      <InfoTip content="申请档案(§14.4):展示申请材料、资料校验、审批轨迹、调价记录、表决与行长决策、决议与执行核验等审批全过程完整留痕;承诺履约等后续跟踪不在档案展示。" />
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
              <td>{{ currencyText(val(n, 'currency')) }}</td>
              <td class="num">{{ rateText(val(n, 'executionRate', 'execution_rate')) }}</td>
              <td>{{ fmtDate(val(n, 'startDate', 'start_date')) }}</td>
              <td>{{ fmtDate(val(n, 'maturityDate', 'maturity_date')) }}</td>
              <td><span class="badge" :class="noteStatusBadge(val(n, 'noteStatus', 'note_status'))">{{ noteStatusText(val(n, 'noteStatus', 'note_status')) }}</span></td>
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
              <td>{{ relationTypeText(val(r, 'relationType')) }}</td>
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
            <tr><th>指标</th><th>目标类型</th><th>基线值</th><th>目标值</th><th>单位</th><th>截止日期</th><th>范围</th><th>成员客户号</th><th>承诺描述</th></tr>
          </thead>
          <tbody>
            <tr v-for="(c, i) in archive.commitments" :key="i">
              <td>{{ metricName(val(c, 'metricCode')) }}</td>
              <td>{{ targetTypeText(val(c, 'targetType')) }}</td>
              <td class="num">{{ val(c, 'baselineValue') }}</td>
              <td class="num">{{ val(c, 'targetValue') }}</td>
              <td>{{ commitmentUnitText(val(c, 'unit')) }}</td>
              <td>{{ val(c, 'endDate') ? String(val(c, 'endDate')).slice(0, 10) : '—' }}</td>
              <td>{{ metricScopeText(val(c, 'metricScope')) }}</td>
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

      <!-- 7. 资料校验(提交时质量校验 PASS/WARN/BLOCK 明细) -->
      <div class="card" v-if="archive.qualityResults?.length">
        <div class="card__head">
          <span>资料校验</span>
          <span :class="qualityBadge">{{ ruleLevelText(qualityOverall) }}</span>
        </div>
        <table class="table">
          <thead><tr><th>校验规则</th><th>级别</th><th>对象</th><th>提示</th><th>校验时间</th></tr></thead>
          <tbody>
            <tr v-for="(q, i) in archive.qualityResults" :key="i">
              <td>{{ val(q, 'ruleCode', 'rule_code') }}</td>
              <td><span class="badge" :class="ruleLevelBadge(val(q, 'ruleLevel', 'rule_level'))">{{ ruleLevelText(val(q, 'ruleLevel', 'rule_level')) }}</span></td>
              <td>{{ val(q, 'subjectType', 'subject_type') }} · {{ val(q, 'subjectId', 'subject_id') }}</td>
              <td>{{ val(q, 'message') }}</td>
              <td>{{ fmtTime(val(q, 'checkedTime', 'checked_time')) }}</td>
            </tr>
          </tbody>
        </table>
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

      <!-- 8b. 审批调价记录(利率调整明细,含边界/理由/操作人) -->
      <div class="card" v-if="archive.rateAdjustments?.length">
        <div class="card__head"><span>审批调价记录</span></div>
        <table class="table">
          <thead><tr><th>节点</th><th>调整前利率</th><th>调整后利率</th><th>调价理由</th><th>操作人</th><th>时间</th></tr></thead>
          <tbody>
            <tr v-for="(a, i) in archive.rateAdjustments" :key="i">
              <td>{{ nodeLabel(val(a, 'node_code', 'nodeCode')) }}</td>
              <td class="num">{{ rateText(val(a, 'before_rate', 'beforeRate')) }}</td>
              <td class="num"><b>{{ rateText(val(a, 'after_rate', 'afterRate')) }}</b></td>
              <td>{{ val(a, 'adjust_reason', 'adjustReason') }}</td>
              <td>{{ val(a, 'operatorName', 'operator_name') }}</td>
              <td>{{ fmtTime(val(a, 'operation_time', 'operationTime')) }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- 9. 表决与行长决策(小组表决计票汇总 + 行长决策) -->
      <div class="card" v-if="archive.voteRounds?.length || archive.presidentDecisions?.length">
        <div class="card__head"><span>表决与行长决策</span></div>
        <table class="table" v-if="archive.voteRounds?.length">
          <thead><tr><th>轮次</th><th>状态</th><th>计票(通过/否决)</th><th>开始时间</th><th>结束时间</th></tr></thead>
          <tbody>
            <tr v-for="(v, i) in archive.voteRounds" :key="i">
              <td>{{ val(v, 'roundName', 'round_name') }}</td>
              <td><span class="badge" :class="val(v, 'status') === 'PASSED' ? 'badge--success' : val(v, 'status') === 'FAILED' ? 'badge--danger' : 'badge--warning'">{{ roundStatusText(val(v, 'status')) }}</span></td>
              <td class="num">{{ voteResultOf(val(v, 'id')) }}</td>
              <td>{{ fmtTime(val(v, 'roundStartTime', 'round_start_time')) }}</td>
              <td>{{ fmtTime(val(v, 'roundEndTime', 'round_end_time')) }}</td>
            </tr>
          </tbody>
        </table>
        <table class="table" style="margin-top:8px" v-if="archive.presidentDecisions?.length">
          <thead><tr><th>行长决策</th><th>意见</th><th>决策时间</th></tr></thead>
          <tbody>
            <tr v-for="(d, i) in archive.presidentDecisions" :key="i">
              <td><span class="badge" :class="val(d, 'decision') === 'AGREE' ? 'badge--success' : val(d, 'decision') === 'VETO' ? 'badge--danger' : 'badge--warning'">{{ decisionText(val(d, 'decision')) }}</span></td>
              <td>{{ val(d, 'opinion') }}</td>
              <td>{{ fmtTime(val(d, 'decisionTime', 'decision_time')) }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- 10. 决议与执行核验(§12.7:决议日期=issue_time,无有效期周期) -->
      <div class="card" v-if="archive.resolutions?.length">
        <div class="card__head"><span>决议</span><span class="badge badge--success">已签发</span></div>
        <table class="table">
          <thead><tr><th>决议号</th><th>最终利率</th><th>决策来源</th><th>决议日期</th><th>状态</th></tr></thead>
          <tbody>
            <tr v-for="(r, i) in archive.resolutions" :key="i">
              <td>{{ val(r, 'resolutionNo', 'resolution_no') }}</td>
              <td class="num"><b>{{ rateText(val(r, 'finalRate', 'final_rate')) }}</b></td>
              <td>{{ val(r, 'decisionSource', 'decision_source') }}</td>
              <td>{{ fmtDate(val(r, 'issueTime', 'issue_time')) }}</td>
              <td><span class="badge" :class="resolutionStatusBadge(val(r, 'status'))">{{ execStatusText(val(r, 'status')) }}</span></td>
            </tr>
          </tbody>
        </table>
        <table class="table" style="margin-top:8px" v-if="archive.resolutionExecutions?.length">
          <thead><tr><th>贷款合同号</th><th>补充协议号</th><th>执行利率</th><th>执行状态</th><th>核验结果</th><th>核验时间</th></tr></thead>
          <tbody>
            <tr v-for="(e, i) in archive.resolutionExecutions" :key="i">
              <td>{{ val(e, 'loanContractNo', 'loan_contract_no') }}</td>
              <td>{{ val(e, 'supplementAgreementNo', 'supplement_agreement_no') }}</td>
              <td class="num">{{ rateText(val(e, 'executionRate', 'execution_rate')) }}</td>
              <td>{{ execStatusText(val(e, 'executionStatus', 'execution_status')) }}</td>
              <td>{{ val(e, 'reconcileResult', 'reconcile_result') }}</td>
              <td>{{ fmtTime(val(e, 'reconcileTime', 'reconcile_time')) }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- 11. 历史履约(该申请承诺计划 + 逐期指标完成情况;与贡献度跟踪同源,按申请挂钩) -->
      <div class="card" v-if="commitmentGroups.length">
        <div class="card__head">
          <span>历史履约 <InfoTip content="该申请审批通过后形成的承诺计划履约情况(与贡献度跟踪同一数据源,按申请挂钩;每期 = 每一次申请)。" style="margin-left:6px" /></span>
          <button class="btn btn--text" @click="router.push('/commitment')">查看贡献度跟踪</button>
        </div>
        <div v-for="(plan, pi) in commitmentGroups" :key="pi" class="plan-block" :style="pi ? 'margin-top:12px' : ''">
          <div class="plan-block__head">
            <span class="badge badge--info">{{ plan.planNo }}</span>
            <span>{{ customerScopeText(plan.scopeType) }}</span>
            <span class="badge" :class="planStatusBadge(plan.status)">{{ planStatusText(plan.status) }}</span>
            <span class="section-tip">{{ plan.startDate ? String(plan.startDate).slice(0, 10) : '' }} ~ {{ plan.endDate ? String(plan.endDate).slice(0, 10) : '' }}</span>
          </div>
          <table class="table">
            <thead><tr><th>指标</th><th>目标值</th><th>单位</th><th>评估期</th><th>实际值</th><th>达成率</th><th>结论</th></tr></thead>
            <tbody>
              <template v-for="(m, mi) in plan.metrics" :key="mi">
                <tr v-for="(e, ei) in m.periods" :key="ei">
                  <td>{{ metricName(m.metricCode) }}</td>
                  <td class="num">{{ m.targetValue ?? '—' }}</td>
                  <td>{{ commitmentUnitText(m.unit) }}</td>
                  <td>{{ String(e.dataDt).slice(0, 10) }}</td>
                  <td class="num">{{ e.actualValue ?? '—' }}</td>
                  <td class="num"><span :class="ratioBadge(e.achievementRatio)">{{ e.achievementRatio != null ? `${e.achievementRatio}%` : '—' }}</span></td>
                  <td><span class="badge" :class="evalResultBadge(e.resultStatus)">{{ evalResultText(e.resultStatus) }}</span></td>
                </tr>
                <tr v-if="!m.periods.length">
                  <td>{{ metricName(m.metricCode) }}</td>
                  <td class="num">{{ m.targetValue ?? '—' }}</td>
                  <td>{{ commitmentUnitText(m.unit) }}</td>
                  <td colspan="4" class="empty-cell">暂无履约评估</td>
                </tr>
              </template>
            </tbody>
          </table>
        </div>
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
  appStatusText, itemStatusText, relationTypeText,
  businessTypeText, customerScopeText, nodeLabel, roleText, actionText,
  productName, metricName, memberRoleText, termUnitText, commitmentUnitText,
  targetTypeText, metricScopeText, currencyText, decisionText,
  roundStatusText, execStatusText, ruleLevelText,
  evalResultText, planStatusText
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
// 借据状态(数仓 note_status):正常/逾期/已结清
function noteStatusText(code?: any) {
  return code === 'NORMAL' ? '正常' : code === 'SETTLED' ? '已结清' : code === 'OVERDUE' ? '逾期' : (code || '—')
}
function noteStatusBadge(s?: any) {
  return s === 'NORMAL' ? 'badge--success' : s === 'OVERDUE' ? 'badge--danger' : 'badge--neutral'
}
// 校验级别徽标(简式,配合 <span class="badge">)
function ruleLevelBadge(l?: any) {
  return l === 'BLOCK' ? 'badge--danger' : l === 'WARN' ? 'badge--warning' : 'badge--success'
}
// 表决计票汇总:按轮次从 voteResults 合并 通过/否决
function voteResultOf(roundId: any) {
  const r = (archive.value.voteResults || []).find((x: any) => String(val(x, 'roundId', 'round_id')) === String(roundId))
  return r ? `${val(r, 'approveCount', 'approve_count') ?? 0} / ${val(r, 'rejectCount', 'reject_count') ?? 0}` : '—'
}
// 决议状态徽标(决议日期=issue_time,无有效期周期)
function resolutionStatusBadge(s?: any) {
  const map: Record<string, string> = {
    ISSUED: 'badge--info', CONTRACT_PENDING: 'badge--warning', EXECUTED: 'badge--success', VOID: 'badge--neutral'
  }
  return map[s || ''] || 'badge--neutral'
}
// 历史履约(承诺计划):计划 → 指标 → 逐期评估;与贡献度跟踪同源(ccr_commitment_* + ccr_tracking_evaluation),按申请挂钩
const commitmentGroups = computed(() => {
  const plans: any[] = archive.value.commitmentPlans || []
  const metrics: any[] = archive.value.commitmentMetrics || []
  const evals: any[] = archive.value.commitmentEvaluations || []
  return plans.map((p: any) => {
    const planId = val(p, 'id')
    const planMetrics = metrics
      .filter((m: any) => String(val(m, 'planId', 'plan_id')) === String(planId))
      .map((m: any) => {
        const metricId = val(m, 'metricId', 'metric_id')
        const periods = evals
          .filter((e: any) => String(val(e, 'metricId', 'metric_id')) === String(metricId))
          .map((e: any) => ({
            dataDt: val(e, 'dataDt', 'data_dt'),
            actualValue: val(e, 'actualValue', 'actual_value'),
            achievementRatio: pctOf(val(e, 'achievementRatio', 'achievement_ratio')),
            resultStatus: val(e, 'resultStatus', 'result_status')
          }))
        return {
          metricCode: val(m, 'metricCode', 'metric_code'),
          targetValue: val(m, 'targetValue', 'target_value'),
          unit: val(m, 'unit'),
          periods
        }
      })
    return {
      planNo: val(p, 'planNo', 'plan_no'),
      scopeType: val(p, 'scopeType', 'scope_type'),
      status: val(p, 'status'),
      startDate: val(p, 'startDate', 'start_date'),
      endDate: val(p, 'endDate', 'end_date'),
      metrics: planMetrics
    }
  })
})
function planStatusBadge(s?: any) {
  const map: Record<string, string> = {
    TRACKING: 'badge--info', PENDING: 'badge--warning', AT_RISK: 'badge--warning', ACHIEVED: 'badge--success',
    DATA_PENDING: 'badge--warning', EXPIRED_UNMET: 'badge--danger', TERMINATED: 'badge--neutral'
  }
  return map[s || ''] || 'badge--neutral'
}
// 达成率比率→百分比(库中 achievement_ratio 为比率 0.84,展示统一转 84)
function pctOf(r: any): any {
  const n = Number(r)
  return r != null && Number.isFinite(n) ? Number((n * 100).toFixed(1)) : (r == null ? null : r)
}
// 达成率徽标:≥100%绿 / ≥80%黄 / <80%红
function ratioBadge(r: any) {
  const n = Number(r)
  if (r == null || !Number.isFinite(n)) return 'badge--neutral'
  return n >= 100 ? 'badge--success' : n >= 80 ? 'badge--warning' : 'badge--danger'
}
function evalResultBadge(s?: any) {
  const map: Record<string, string> = {
    ACHIEVED: 'badge--success', ON_TRACK: 'badge--info', AT_RISK: 'badge--warning', DATA_PENDING: 'badge--neutral'
  }
  return map[s || ''] || 'badge--neutral'
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
.head-actions { margin-left: auto; display: flex; gap: 8px; }
.table { border-radius: var(--radius-sm); overflow-x: auto; }
.desc-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px 16px; font-size: 14px; }
.desc-item { display: flex; flex-direction: column; gap: 2px; }
.desc-item--full { grid-column: 1 / -1; }
.desc-label { font-size: 12px; color: var(--color-text-light); }
.empty-block { text-align: center; color: var(--color-text-light); padding: 20px 0; font-size: 13px; }
.hash-cell { font-size: 12px; color: var(--color-text-sub); word-break: break-all; }
.plan-block { border: 1px solid var(--color-border-light); border-radius: var(--radius-sm); padding: 10px 12px; }
.plan-block__head { display: flex; align-items: center; gap: 12px; margin-bottom: 8px; font-size: 13px; flex-wrap: wrap; }
</style>
