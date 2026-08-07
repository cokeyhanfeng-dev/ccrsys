<template>
  <div v-if="loaded">
    <div class="section-head">
      <div class="section-title">审批详情</div>
      <div class="section-tip">基础信息只读,普通审批人仅可编辑审批利率与审批意见。</div>
    </div>

    <!-- 0. 数据来源与快照信息(§12.16-7) -->
    <div class="card">
      <div class="card__head">
        <span>数据来源</span>
        <span class="badge" :class="source === 'SNAPSHOT' ? 'badge--success' : 'badge--warning'">
          {{ source === 'SNAPSHOT' ? '冻结快照' : '实时取数' }}
        </span>
      </div>
      <div class="detail-grid" v-if="source === 'SNAPSHOT'">
        <div><span class="dg-label">数据日期</span>{{ snapshotInfo.dataDt || '—' }}</div>
        <div><span class="dg-label">冻结时间</span>{{ snapshotInfo.freezeTime || '—' }}</div>
        <div><span class="dg-label">快照批次号</span>{{ snapshotInfo.bundleNo || '—' }}</div>
      </div>
      <div class="section-tip" v-else>未找到提交时冻结快照,客户/融资/贡献度为数仓实时查询结果,可能与提交时点存在差异。</div>
    </div>

    <!-- 1. 流程路由 -->
    <div class="card">
      <div class="card__head"><span>流程路由</span><span class="badge badge--info">{{ businessTypeText }}</span></div>
      <el-steps v-if="routeChain.length" :active="currentNodeIndex" align-center finish-status="success">
        <el-step v-for="node in routeChain" :key="node" :title="nodeLabel(node)" />
      </el-steps>
      <div v-else class="empty">暂无数据</div>
    </div>

    <!-- 2. 资料校验 -->
    <div class="card">
      <div class="card__head">
        <span>资料校验</span>
        <span v-if="qualityOverall" class="badge" :class="qualityBadge">{{ qualityText }}</span>
      </div>
      <template v-if="qualityOverall">
        <table class="table" v-if="qualityResults.length">
          <thead><tr><th>规则</th><th>级别</th><th>对象</th><th>说明</th><th>校验时间</th></tr></thead>
          <tbody>
            <tr v-for="(q, i) in qualityResults" :key="i">
              <td>{{ q.ruleCode }}</td>
              <td>
                <span class="badge" :class="q.ruleLevel === 'BLOCK' ? 'badge--danger' : q.ruleLevel === 'WARN' ? 'badge--warning' : 'badge--success'">
                  {{ q.ruleLevel === 'BLOCK' ? '阻断' : q.ruleLevel === 'WARN' ? '预警' : '通过' }}
                </span>
              </td>
              <td>{{ q.subjectType || '—' }} {{ q.subjectId || '' }}</td>
              <td>{{ q.message || '—' }}</td>
              <td>{{ q.checkedTime || '—' }}</td>
            </tr>
          </tbody>
        </table>
        <div v-else class="empty">校验通过,无异常明细</div>
      </template>
      <div v-else class="empty">暂无数据</div>
    </div>

    <!-- 3. 醒目利率决策区 -->
    <div class="card card--decision">
      <div class="card__head">
        <span>利率决策区</span>
        <span class="badge" :class="actionable ? 'badge--processing' : 'badge--neutral'">{{ actionable ? '待我审批' : statusText }}</span>
      </div>
      <div class="decision-row">
        <div class="decision-item"><span class="dg-label">客户</span><b>{{ customerName }}</b></div>
        <div class="decision-item"><span class="dg-label">申请利率</span><b class="rate">{{ fmtRate(pi.requested_rate) }}</b></div>
        <div class="decision-item">
          <span class="dg-label">当前审批利率</span>
          <b class="rate rate--approval">{{ fmtRate(pi.current_approval_rate ?? pi.requested_rate) }}</b>
        </div>
        <div class="decision-item">
          <span class="dg-label">申请金额</span>
          <b>{{ pi.pricing_amount ?? '—' }} 万元</b>
          <div class="stat-card__sub">{{ isLoan ? '贷款利率越低越优惠' : '存款利率越高越优惠' }}</div>
        </div>
      </div>
    </div>

    <!-- 4. 申请内容 -->
    <div class="card">
      <div class="card__head">
        <span>申请内容</span>
        <span v-if="pi.inherit_flag === 'Y' || pi.inheritFlag === 'Y'" class="badge badge--info">沿用原决议</span>
      </div>
      <div class="detail-grid">
        <div><span class="dg-label">申请号</span>{{ application.applicationNo || '—' }}</div>
        <div><span class="dg-label">业务类型</span>{{ businessTypeText }}</div>
        <div><span class="dg-label">客户号</span>{{ application.customerNo || pi.pricing_customer_no || '—' }}</div>
        <div><span class="dg-label">定价分项</span>{{ pi.pricing_item_no || '—' }}</div>
        <div><span class="dg-label">分项状态</span>{{ statusText }}</div>
        <div><span class="dg-label">产品编码</span>{{ pi.product_code || '—' }}</div>
        <div><span class="dg-label">原执行利率</span>{{ pi.original_rate != null ? fmtRate(pi.original_rate) : '新增业务' }}</div>
        <div><span class="dg-label">期限</span>{{ pi.term_value ? `${pi.term_value}${termUnitText}` : '—' }}</div>
        <div><span class="dg-label">当前节点</span>{{ pi.current_node_code ? nodeLabel(pi.current_node_code) : '—' }}</div>
      </div>
      <div class="remark-text" style="margin-top:12px" v-if="application.applicationRemark">{{ application.applicationRemark }}</div>
    </div>

    <!-- 5. 客户基本信息 -->
    <div class="card">
      <div class="card__head"><span>客户基本信息</span><span class="badge badge--info">数仓</span></div>
      <div class="detail-grid" v-if="hasCustomer">
        <div><span class="dg-label">客户名称</span>{{ customerName }}</div>
        <div v-if="customer.entpCharic"><span class="dg-label">企业性质</span>{{ customer.entpCharic }}</div>
        <div v-if="customer.industry"><span class="dg-label">所属行业</span>{{ customer.industry }}</div>
        <div v-if="customer.creditLevel"><span class="dg-label">内部信用等级</span>{{ customer.creditLevel }}</div>
        <div v-if="customer.fiveLevelClass"><span class="dg-label">五级分类</span>{{ customer.fiveLevelClass }}</div>
        <div v-if="customer.openOrgName"><span class="dg-label">开户机构</span>{{ customer.openOrgName }}</div>
        <div v-if="customer.customerClass"><span class="dg-label">客户分类</span>{{ customer.customerClass }}</div>
      </div>
      <div v-else class="empty">暂无数据</div>
    </div>

    <!-- 6. 集团信息(仅集团场景) -->
    <div class="card" v-if="isGroup">
      <div class="card__head"><span>集团信息</span><span class="badge badge--info">集团客户</span></div>
      <div class="detail-grid">
        <div><span class="dg-label">集团号</span>{{ application.groupNo }}</div>
        <div><span class="dg-label">成员数</span>{{ groupMembers.length }} 户</div>
        <div><span class="dg-label">合计申请金额</span>{{ groupTotalAmount }} 万元</div>
        <div><span class="dg-label">集团贡献度</span>暂无数据</div>
      </div>
      <el-collapse v-if="groupMembers.length" style="margin-top:12px">
        <el-collapse-item v-for="(m, i) in groupMembers" :key="i" :title="`成员 ${m.memberCustomerNo}(${m.memberRole || '成员'})`" :name="i">
          <div class="detail-grid">
            <div><span class="dg-label">成员客户号</span>{{ m.memberCustomerNo }}</div>
            <div><span class="dg-label">成员角色</span>{{ m.memberRole || '—' }}</div>
            <div><span class="dg-label">申请金额(万元)</span>{{ m.requestAmount ?? '—' }}</div>
          </div>
          <table class="table" style="margin-top:8px" v-if="memberCommitments(m.memberCustomerNo).length">
            <thead><tr><th>承诺指标</th><th>基线</th><th>目标</th><th>单位</th></tr></thead>
            <tbody>
              <tr v-for="(c, j) in memberCommitments(m.memberCustomerNo)" :key="j">
                <td>{{ c.metricCode }}</td>
                <td class="num">{{ c.baselineValue ?? '—' }}</td>
                <td class="num">{{ c.targetValue ?? '—' }}</td>
                <td>{{ c.unit || '—' }}</td>
              </tr>
            </tbody>
          </table>
          <div v-else class="empty" style="padding:8px">该成员暂无承诺指标</div>
        </el-collapse-item>
      </el-collapse>
    </div>

    <!-- 7. 授信/账户与本行融资 -->
    <div class="card">
      <div class="card__head"><span>授信/账户与本行融资</span><span class="badge badge--info">数仓</span></div>
      <table class="table" v-if="financing.length">
        <thead><tr><th>合同号</th><th>贷款余额(万元)</th><th>原利率</th><th>担保类型</th></tr></thead>
        <tbody>
          <tr v-for="f in financing" :key="f.contractNo">
            <td>{{ f.contractNo }}</td><td class="num">{{ f.loanBalance ?? '—' }}</td>
            <td class="num">{{ fmtRate(f.contractRate) }}</td><td>{{ f.guaranteeType || '—' }}</td>
          </tr>
        </tbody>
      </table>
      <div v-else class="empty">暂无数据</div>
    </div>

    <!-- 8. 贷款合同/存款账户与担保 -->
    <div class="card">
      <div class="card__head"><span>{{ isLoan ? '贷款合同与担保' : '存款账户' }}</span></div>
      <div class="detail-grid" v-if="isLoan">
        <div><span class="dg-label">定价载体</span>{{ pi.pricing_carrier_type === 'LOAN_CONTRACT' ? '贷款合同' : pi.pricing_carrier_type === 'DEPOSIT_ACCOUNT' ? '存款账户' : (pi.pricing_carrier_type || '—') }}</div>
        <div><span class="dg-label">载体来源</span>{{ pi.credit_tranche_ref || '—' }}</div>
        <div><span class="dg-label">合同下借据</span>暂无数据</div>
      </div>
      <template v-else>
        <table class="table" v-if="depositAccounts.length">
          <thead>
            <tr><th>存款账号</th><th>产品</th><th>余额(万元)</th><th>当前执行利率(%)</th><th>期限</th><th>开户日</th><th>到期日</th><th>标识</th></tr>
          </thead>
          <tbody>
            <tr v-for="(a, i) in depositAccounts" :key="i">
              <td>{{ a.accountNoMasked || '—' }}</td>
              <td>{{ a.productCode || pi.product_code || '—' }}</td>
              <td class="num">{{ a.accountBalance ?? '—' }}</td>
              <td class="num">{{ a.executionRate ?? '—' }}</td>
              <td>{{ a.termValue ? `${a.termValue}${termUnitText}` : '—' }}</td>
              <td>{{ a.openDate || '—' }}</td>
              <td>{{ a.maturityDate || '—' }}</td>
              <td><span class="badge" :class="a.plannedAccountFlag === 'Y' ? 'badge--neutral' : 'badge--success'">{{ a.plannedAccountFlag === 'Y' ? '拟开户' : '存量账户' }}</span></td>
            </tr>
          </tbody>
        </table>
        <div v-else class="empty" style="padding:8px">暂无账户数据</div>
        <div class="section-tip" style="margin-top:8px" v-if="pi.boundary_rate != null">
          本节点权限上限 {{ pi.boundary_rate }}%(存款越高越优惠,超过上限将上送小组表决)
          <span v-if="pi.requested_rate != null"> · 申请利率较上限 {{ ((pi.requested_rate - pi.boundary_rate) * 100).toFixed(0) }} BP</span>
        </div>
      </template>
      <table class="table" style="margin-top:12px" v-if="guarantees.length">
        <thead><tr><th>担保方式</th><th>措施编号</th><th>措施类型</th><th>担保金额(万元)</th></tr></thead>
        <tbody>
          <template v-for="(g, i) in guarantees" :key="i">
            <tr>
              <td>{{ g.guaranteeType || '—' }}</td><td>{{ g.measureNo || '—' }}</td>
              <td>{{ measureTypeText(g.measureType) }}</td><td class="num">{{ g.guaranteeAmount ?? '—' }}</td>
            </tr>
            <!-- 担保措施明细行(抵押物坐落/面积/估值、保证人等,取快照 extJson;无则暂无数据) -->
            <tr class="measure-detail">
              <td colspan="4">
                <template v-if="g.measureType === 'MORTGAGE'">
                  <span class="dg-label">抵押物</span>{{ g.extJson?.name || '暂无数据' }}
                  <span class="dg-label">坐落</span>{{ g.extJson?.address || '暂无数据' }}
                  <span class="dg-label">面积</span>{{ g.extJson?.area ? `${g.extJson.area}㎡` : '暂无数据' }}
                  <span class="dg-label">估值(万元)</span>{{ g.guaranteeAmount ?? '暂无数据' }}
                  <span class="dg-label">权属人</span>{{ g.extJson?.owner || '暂无数据' }}
                </template>
                <template v-else-if="g.measureType === 'GUARANTOR'">
                  <span class="dg-label">保证人名称</span>{{ g.extJson?.name || '暂无数据' }}
                  <span class="dg-label">证件号码</span>{{ g.extJson?.certNo || '暂无数据' }}
                  <span class="dg-label">担保余额(万元)</span>{{ g.extJson?.balance ?? '暂无数据' }}
                </template>
                <span v-else class="dg-label">暂无措施明细数据</span>
              </td>
            </tr>
          </template>
        </tbody>
      </table>
      <div v-else class="empty" style="padding:8px">无担保明细</div>
    </div>

    <!-- 9. 当前与拟达成贡献度(双概念并排,D1 组件化) -->
    <div class="card">
      <div class="card__head"><span>贡献度参考</span><span class="badge badge--info">G3 定价依据</span></div>
      <ContributionPanel :contribution="contribution" :commitments="commitments" />
    </div>

    <!-- 10. 历史履约(tracking:该客户承诺最新评估) -->
    <div class="card">
      <div class="card__head"><span>历史履约</span><span class="badge badge--info">承诺跟踪</span></div>
      <template v-if="tracking.length">
        <div v-if="unmetTracking.length" class="warn-bar">
          {{ unmetTracking.length }} 项承诺指标未达成({{ unmetTracking.map((t) => t.metricCode).join('、') }}),请关注履约风险。
        </div>
        <table class="table">
          <thead><tr><th>计划号</th><th>指标</th><th>目标值</th><th>实际值</th><th>完成率</th><th>评估结论</th><th>数据日期</th></tr></thead>
          <tbody>
            <tr v-for="(t, i) in tracking" :key="i">
              <td>{{ t.planNo || '—' }}</td>
              <td>{{ t.metricCode || '—' }}</td>
              <td class="num">{{ t.targetValue ?? '—' }}</td>
              <td class="num">{{ t.actualValue ?? '暂无数据' }}</td>
              <td class="num">
                <span v-if="t.achievementRatio != null" :class="Number(t.achievementRatio) >= 100 ? 'rate-ok' : 'rate-bad'">
                  {{ t.achievementRatio }}%
                </span>
                <span v-else>暂无数据</span>
              </td>
              <td>
                <span class="badge" :class="t.resultStatus === 'ACHIEVED' ? 'badge--success' : t.resultStatus === 'AT_RISK' ? 'badge--danger' : 'badge--warning'">
                  {{ trackingResultText(t.resultStatus) }}
                </span>
              </td>
              <td>{{ t.dataDt || '—' }}</td>
            </tr>
          </tbody>
        </table>
      </template>
      <div v-else class="empty">暂无数据</div>
    </div>

    <!-- 11. 机构达成(orgPerformance:申请机构最新批次) -->
    <div class="card">
      <div class="card__head"><span>机构达成</span><span class="badge badge--info">数仓</span></div>
      <table class="table" v-if="orgPerformance.length">
        <thead><tr><th>机构</th><th>统计月份</th><th>达成金额</th><th>目标金额</th><th>达成率</th><th>数据日期</th></tr></thead>
        <tbody>
          <tr v-for="(o, i) in orgPerformance" :key="i">
            <td>{{ o.orgCode || '—' }}</td>
            <td>{{ o.statMonth || '—' }}</td>
            <td class="num">{{ o.achievedAmount ?? '—' }}</td>
            <td class="num">{{ o.expectedAmount ?? '—' }}</td>
            <td class="num">
              <span v-if="o.completionRate != null" :class="Number(o.completionRate) >= 100 ? 'rate-ok' : 'rate-bad'">
                {{ o.completionRate }}%
              </span>
              <span v-else>暂无数据</span>
            </td>
            <td>{{ o.dataDt || '—' }}</td>
          </tr>
        </tbody>
      </table>
      <div v-else class="empty">暂无数据</div>
    </div>

    <!-- 11b. 决议与执行核验(§12.7 ⑪:决议日期=issue_time,无有效期周期) -->
    <div class="card" v-if="resolutions.length">
      <div class="card__head"><span>决议</span><span class="badge badge--success">已签发</span></div>
      <table class="table">
        <thead><tr><th>决议号</th><th>最终利率</th><th>决策来源</th><th>决议日期</th><th>状态</th></tr></thead>
        <tbody>
          <tr v-for="(r, i) in resolutions" :key="i">
            <td>{{ r.resolutionNo || '—' }}</td>
            <td class="num">{{ fmtRate(r.finalRate) }}</td>
            <td>{{ r.decisionSource || '—' }}</td>
            <td>{{ fmtDate(r.issueTime) }}</td>
            <td><span class="badge" :class="resolutionStatusBadge(r.status)">{{ resolutionStatusText(r.status) }}</span></td>
          </tr>
        </tbody>
      </table>
      <table class="table" style="margin-top:8px" v-if="resolutionExecutions.length">
        <thead><tr><th>贷款合同号</th><th>补充协议号</th><th>执行利率</th><th>执行状态</th><th>核验结果</th><th>核验时间</th></tr></thead>
        <tbody>
          <tr v-for="(e, i) in resolutionExecutions" :key="i">
            <td>{{ e.loanContractNo || '—' }}</td>
            <td>{{ e.supplementAgreementNo || '—' }}</td>
            <td class="num">{{ fmtRate(e.executionRate) }}</td>
            <td>{{ e.executionStatus || '—' }}</td>
            <td>{{ e.reconcileResult || '—' }}</td>
            <td>{{ fmtDate(e.reconcileTime) }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 11c. 表决与行长决策(§12.7:小组表决计票汇总 + 行长决策) -->
    <div class="card" v-if="voteRounds.length || presidentDecisions.length">
      <div class="card__head"><span>表决与行长决策</span></div>
      <table class="table" v-if="voteRounds.length">
        <thead><tr><th>轮次</th><th>状态</th><th>计票(通过/否决)</th><th>开始时间</th><th>结束时间</th></tr></thead>
        <tbody>
          <tr v-for="(v, i) in voteRounds" :key="i">
            <td>{{ v.roundName || v.roundNo || '—' }}</td>
            <td><span class="badge" :class="v.status === 'PASSED' ? 'badge--success' : v.status === 'FAILED' ? 'badge--danger' : 'badge--warning'">{{ voteRoundStatusText(v.status) }}</span></td>
            <td class="num">{{ voteResultOf(v.id) }}</td>
            <td>{{ fmtDate(v.roundStartTime) }}</td>
            <td>{{ fmtDate(v.roundEndTime) }}</td>
          </tr>
        </tbody>
      </table>
      <table class="table" style="margin-top:8px" v-if="presidentDecisions.length">
        <thead><tr><th>行长决策</th><th>意见</th><th>决策时间</th></tr></thead>
        <tbody>
          <tr v-for="(d, i) in presidentDecisions" :key="i">
            <td><span class="badge" :class="d.decision === 'AGREE' ? 'badge--success' : d.decision === 'VETO' ? 'badge--danger' : 'badge--warning'">{{ d.decision || '—' }}</span></td>
            <td>{{ d.opinion || '—' }}</td>
            <td>{{ fmtDate(d.decisionTime) }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 12. 流程轨迹 -->
    <div class="card">
      <div class="card__head"><span>流程轨迹</span></div>
      <el-timeline v-if="flowTrace.length">
        <el-timeline-item v-for="(t, i) in flowTrace" :key="i" :timestamp="t.operationTime || ''" placement="top">
          <div>
            <span class="badge" :class="t.actionType === 'REJECT' ? 'badge--rejected' : 'badge--approved'">
              {{ t.actionType === 'APPROVE' ? '通过' : t.actionType === 'REJECT' ? '否决' : t.actionType }}
            </span>
            <span class="dg-label" style="margin-left:8px">{{ nodeLabel(t.nodeCode) }}</span>
            <span v-if="t.fromStatus || t.toStatus" class="badge badge--neutral" style="margin-left:8px">
              {{ statusTextOf(t.fromStatus) }} → {{ statusTextOf(t.toStatus) }}
            </span>
            <span v-if="t.beforeRate != null && t.afterRate != null && t.beforeRate !== t.afterRate" style="margin-left:8px">
              利率 {{ fmtRate(t.beforeRate) }} → {{ fmtRate(t.afterRate) }}
            </span>
          </div>
          <div class="stat-card__sub" v-if="t.actionComment">{{ t.actionComment }}</div>
        </el-timeline-item>
      </el-timeline>
      <div v-else class="empty">暂无数据</div>
    </div>

    <!-- 12. 审批操作(仅当前节点待办可操作) -->
    <div class="card" v-if="actionable">
      <div class="card__head"><span>审批操作</span><span class="badge badge--processing">当前节点:{{ nodeLabel(pi.current_node_code) }}</span></div>
      <div class="op-form">
        <div class="op-form__row">
          <label class="op-form__label">审批利率(%)</label>
          <el-input-number v-model="opRate" :min="0" :max="36" :precision="4" :step="0.01" controls-position="right" />
          <div class="stat-card__sub">
            {{ isLoan
              ? '贷款:审批利率不低于本节点下限方可权限内终审,低于下限将保留利率自动上送下一节点;调价不得突破本节点权限边界。'
              : '存款:审批利率不高于本节点上限方可权限内终审,超出将保留利率上送小组表决;调价不得突破本节点权限边界。' }}
          </div>
        </div>
        <div class="op-form__row">
          <label class="op-form__label">审批意见</label>
          <el-input v-model="opComment" type="textarea" :rows="3" maxlength="500" show-word-limit placeholder="请输入审批意见(否决时建议说明原因)" />
        </div>
        <div style="display:flex;gap:12px">
          <button class="btn btn--primary" :disabled="submitting" @click="doApprove">{{ rateAdjusted ? '调价并通过' : '通过' }}</button>
          <button class="btn btn--danger" :disabled="submitting" @click="doReject">否决</button>
          <button class="btn btn--secondary" @click="goBack">返回待办列表</button>
        </div>
      </div>
    </div>
    <div class="card" v-else-if="pi.status === 'ROUTING'">
      <div class="empty">该分项当前节点为「{{ nodeLabel(pi.current_node_code) }}」,不在本人审批范围,仅可查看。</div>
    </div>
  </div>
  <div v-else class="empty">加载中...</div>
</template>

<script setup lang="ts">
import { computed, ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getApprovalDetail, approveTask, rejectTask, newIdempotencyKey } from '@/api/approval'
import { useUserStore } from '@/store/user'
import ContributionPanel from '@/components/ContributionPanel.vue'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const loaded = ref(false)
const submitting = ref(false)
const pricingItemId = computed(() => route.params.id as string)

const pi = ref<any>({})
const application = ref<any>({})
const customer = ref<any>({})
const financing = ref<any[]>([])
const contribution = ref<any[]>([])
const commitments = ref<any[]>([])
const guarantees = ref<any[]>([])
const groupMembers = ref<any[]>([])
const routeChain = ref<string[]>([])
const qualityResults = ref<any[]>([])
const qualityOverall = ref('')
const flowTrace = ref<any[]>([])
const source = ref('')
const snapshotInfo = ref<any>({})
const tracking = ref<any[]>([])
const orgPerformance = ref<any[]>([])
const depositAccounts = ref<any[]>([])
const resolutions = ref<any[]>([])
const resolutionExecutions = ref<any[]>([])
const voteRounds = ref<any[]>([])
const voteResults = ref<any[]>([])
const presidentDecisions = ref<any[]>([])

const opRate = ref<number | undefined>(undefined)
const opComment = ref('')

const NODE_LABELS: Record<string, string> = {
  BRANCH_MANAGER: '支行行长', DEPT_GENERAL_MANAGER: '部门总经理',
  VICE_PRESIDENT: '分管行长', SIX_PEOPLE_GROUP: '六人小组表决', PRESIDENT: '行长决策'
}
const ROLE_NODE: Record<string, string> = {
  branch_manager: 'BRANCH_MANAGER', dept_gm: 'DEPT_GENERAL_MANAGER', vice_president: 'VICE_PRESIDENT'
}
const STATUS_TEXT: Record<string, string> = {
  DRAFT: '草稿', SUBMITTED: '已提交', ROUTING: '路由中', APPROVED_LEVEL: '权限内已批',
  VOTING: '小组表决', COMMITTEE_PASS: '小组通过', PRESIDENT_DECISION: '行长决议',
  FINAL: '终态', VETOED: '一票否决', REJECTED: '已否决', RETURNED: '已退回', CLOSED: '已关闭'
}

function nodeLabel(code?: string) {
  return code ? (NODE_LABELS[code] || code) : '—'
}

const isLoan = computed(() => application.value.businessType !== 'DEPOSIT')
const businessTypeText = computed(() => application.value.businessType === 'DEPOSIT' ? '存款' : '贷款')
const isGroup = computed(() => !!application.value.groupNo)
const hasCustomer = computed(() => !!customer.value.customerName)
const customerName = computed(() => customer.value.customerName || pi.value.pricing_customer_no || '—')
const statusText = computed(() => STATUS_TEXT[pi.value.status] || pi.value.status || '—')

// 当前节点在路由链中的位置(高亮)
const currentNodeIndex = computed(() => {
  const idx = routeChain.value.indexOf(pi.value.current_node_code)
  return idx < 0 ? 0 : idx
})

// 仅当分项在审批中且当前节点与登录人角色节点一致时可操作
const actionable = computed(() => {
  if (pi.value.status !== 'ROUTING') return false
  const role = userStore.userInfo?.roles?.[0] || ''
  return pi.value.current_node_code && ROLE_NODE[role] === pi.value.current_node_code
})

const rateAdjusted = computed(() => {
  const base = pi.value.current_approval_rate ?? pi.value.requested_rate
  return opRate.value != null && base != null && Number(opRate.value) !== Number(base)
})

const qualityBadge = computed(() =>
  qualityOverall.value === 'BLOCK' ? 'badge--danger' : qualityOverall.value === 'WARN' ? 'badge--warning' : 'badge--success')
const qualityText = computed(() =>
  qualityOverall.value === 'BLOCK' ? '阻断' : qualityOverall.value === 'WARN' ? '预警' : '通过')

const groupTotalAmount = computed(() =>
  groupMembers.value.reduce((sum, m) => sum + (Number(m.requestAmount) || 0), 0))

const termUnitText = computed(() => {
  const map: Record<string, string> = { D: '天', M: '个月', Y: '年', DAY: '天', MONTH: '个月', YEAR: '年' }
  return map[pi.value.term_unit] || pi.value.term_unit || ''
})

function fmtRate(v: any) {
  return v == null || v === '' ? '—' : `${v}%`
}

function memberCommitments(memberNo: string) {
  return commitments.value.filter((c) => c.memberCustomerNo === memberNo)
}

// 历史履约:未达成指标(用于警示条)
const unmetTracking = computed(() =>
  tracking.value.filter((t) => t.resultStatus && t.resultStatus !== 'ACHIEVED'))

function measureTypeText(t?: string) {
  const map: Record<string, string> = { MORTGAGE: '抵押物', GUARANTOR: '保证人', PLEDGE: '质押物' }
  return t ? (map[t] || t) : '—'
}

function trackingResultText(s?: string) {
  const map: Record<string, string> = {
    ACHIEVED: '已达成', AT_RISK: '有风险', DATA_PENDING: '数据待齐',
    NO_EVALUATION: '暂无评估', ON_TRACK: '正常'
  }
  return s ? (map[s] || s) : '—'
}

function statusTextOf(s?: string) {
  return s ? (STATUS_TEXT[s] || s) : '—'
}

function fmtDate(v: any) {
  return v ? String(v).replace('T', ' ').slice(0, 16) : '—'
}

// §12.7 ⑪ 决议状态:决议日期=issue_time,无有效期周期
const RESOLUTION_STATUS: Record<string, string> = {
  ISSUED: '已签发', CONTRACT_PENDING: '待签合同', EXECUTED: '已执行', VOID: '已作废'
}
function resolutionStatusText(s?: string) {
  return s ? (RESOLUTION_STATUS[s] || s) : '—'
}
function resolutionStatusBadge(s?: string) {
  const map: Record<string, string> = {
    ISSUED: 'badge--info', CONTRACT_PENDING: 'badge--warning', EXECUTED: 'badge--success', VOID: 'badge--neutral'
  }
  return map[s] || 'badge--neutral'
}

const VOTE_ROUND_STATUS: Record<string, string> = {
  VOTING: '表决中', PASSED: '通过', FAILED: '未通过', CLOSED: '已结束'
}
function voteRoundStatusText(s?: string) {
  return s ? (VOTE_ROUND_STATUS[s] || s) : '—'
}
function voteResultOf(roundId: any) {
  const r = voteResults.value.find((x) => x.roundId === roundId)
  return r ? `${r.approveCount ?? 0} / ${r.rejectCount ?? 0}` : '—'
}

async function load() {
  try {
    const data = await getApprovalDetail(pricingItemId.value)
    pi.value = data.pricingItem || {}
    application.value = data.application?.[0] || {}
    customer.value = data.customer?.[0] || {}
    financing.value = data.financing || []
    contribution.value = data.contribution || []
    commitments.value = data.commitments || []
    guarantees.value = data.guarantees || []
    groupMembers.value = data.groupMembers || []
    routeChain.value = data.routeChain || []
    qualityResults.value = data.qualityResults || []
    qualityOverall.value = data.qualityOverall || ''
    flowTrace.value = data.flowTrace || []
    source.value = data.source || ''
    snapshotInfo.value = data.snapshotInfo || {}
    tracking.value = data.tracking || []
    orgPerformance.value = data.orgPerformance || []
    depositAccounts.value = data.depositAccounts || []
    resolutions.value = data.resolutions || []
    resolutionExecutions.value = data.resolutionExecutions || []
    voteRounds.value = data.voteRounds || []
    voteResults.value = data.voteResults || []
    presidentDecisions.value = data.presidentDecisions || []
    const base = pi.value.current_approval_rate ?? pi.value.requested_rate
    opRate.value = base != null ? Number(base) : undefined
    loaded.value = true
  } catch {
    ElMessage.error('审批详情加载失败')
  }
}

function goBack() {
  router.push('/approval')
}

async function doApprove() {
  if (opRate.value == null) {
    ElMessage.warning('请填写审批利率')
    return
  }
  submitting.value = true
  try {
    await approveTask({
      pricingItemId: Number(pricingItemId.value),
      nodeCode: pi.value.current_node_code,
      adjustRate: rateAdjusted.value ? opRate.value : null,
      comment: opComment.value || undefined,
      versionNo: Number(pi.value.version_no)
    }, newIdempotencyKey())
    ElMessage.success(rateAdjusted.value ? '已调价并通过' : '已通过')
    goBack()
  } catch {
    load() // 版本冲突/已处理等:刷新最新状态
  } finally {
    submitting.value = false
  }
}

function doReject() {
  ElMessageBox.confirm('确认否决该定价分项?否决后为终态。', '否决', { type: 'warning', confirmButtonText: '确认否决', cancelButtonText: '取消' })
    .then(async () => {
      submitting.value = true
      try {
        await rejectTask({
          pricingItemId: Number(pricingItemId.value),
          nodeCode: pi.value.current_node_code,
          comment: opComment.value || undefined,
          versionNo: Number(pi.value.version_no)
        }, newIdempotencyKey())
        ElMessage.warning('已否决')
        goBack()
      } catch {
        load()
      } finally {
        submitting.value = false
      }
    })
    .catch(() => undefined)
}

onMounted(load)
</script>

<style scoped>
.section-head { margin-bottom: 16px; }
.section-title { font-size: var(--fs-h2); font-weight: 600; margin-bottom: 6px; }
.section-tip { font-size: 13px; color: var(--color-text-sub); }
.card { background: var(--color-surface); border: 1px solid var(--color-border); border-radius: var(--radius); padding: 16px; margin-bottom: 16px; box-shadow: var(--shadow-sm); }
.card__head { display: flex; align-items: center; justify-content: space-between; font-weight: 600; margin-bottom: 12px; }
.card--decision { border-color: var(--color-primary); }
.decision-row { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; }
.decision-item { font-size: 14px; }
.decision-item .rate { font-size: 22px; color: var(--color-primary); }
.decision-item .rate--approval { color: var(--color-warning); }
.dg-label { color: var(--color-text-sub); margin-right: 6px; }
.detail-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px 16px; font-size: 14px; }
.table { border-radius: var(--radius); overflow: hidden; }
.remark-text { font-size: 14px; background: var(--color-bg); border-radius: 6px; padding: 12px; line-height: 1.6; }
.op-form__row { margin-bottom: 12px; }
.op-form__label { display: block; font-size: 13px; color: var(--color-text-sub); margin-bottom: 6px; }
.stat-card__sub { font-size: 12px; color: var(--color-text-light); margin-top: 4px; }
.empty { text-align: center; padding: 16px; color: var(--color-text-light); }
.warn-bar { background: var(--color-warning-light, #fef3c7); color: var(--color-warning); border-radius: 6px; padding: 8px 12px; font-size: 13px; margin-bottom: 10px; }
.rate-ok { color: var(--color-success); font-weight: 600; }
.rate-bad { color: var(--color-danger); font-weight: 600; }
.measure-detail > td { background: #fafbfc; font-size: 13px; color: var(--color-text-sub); }
.measure-detail .dg-label { margin: 0 4px 0 12px; }
.measure-detail .dg-label:first-child { margin-left: 0; }
</style>
