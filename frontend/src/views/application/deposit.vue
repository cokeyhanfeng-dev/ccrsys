<template>
  <div>
    <div class="section-head">
      <div class="eyebrow">DEPOSIT APPLICATION · 存款利率申请</div>
      <div class="section-title">存款利率提升申请</div>
      <div class="section-tip">存款申请必须经过支行行长节点;贷款控制最低利率、存款控制最高利率,比较方向相反(越高越优惠,HIGHER_BETTER)。</div>
    </div>

    <!-- 关联重提/草稿提示 -->
    <div v-if="draft.id" class="form-card draft-banner">
      <span class="badge badge--info">草稿</span>
      申请号 {{ draft.applicationNo }}(版本 v{{ draft.versionNo }})
      <span class="section-tip">· 草稿保存仅更新主单信息,存款分项以创建时内容为准</span>
    </div>

    <!-- 1. 客户信息(复用贷款申请的客户查询带出逻辑) -->
    <div class="form-card">
      <div class="form-card__title">客户信息</div>
      <div class="section-tip" style="margin-bottom:12px">客户基本信息由数仓统一提供,按客户姓名模糊查询带出后只读展示。</div>
      <div class="form-grid">
        <div class="form-field">
          <label class="form-field__label">客户主体 <span class="req">*</span></label>
          <select class="form-select" v-model="form.customerScope">
            <option value="CORPORATE">企业单户</option>
            <option value="INDIVIDUAL">个人</option>
          </select>
        </div>
        <div class="form-field" style="grid-column: span 2">
          <label class="form-field__label">客户名称 <span class="req">*</span></label>
          <div style="display:flex;gap:8px">
            <input class="form-input" v-model="form.customerName" placeholder="输入客户名称模糊查询" @keyup.enter="searchCustomers" />
            <button class="btn btn--secondary" @click="searchCustomers">查询</button>
          </div>
          <div class="customer-cands" v-if="customerCands.length">
            <div v-for="c in customerCands" :key="c.customerNo" class="customer-cand" @click="selectCustomer(c)">
              {{ c.customerName }} · {{ c.customerNo }} · {{ c.custType === 'CORP' ? '对公' : '个人' }}
            </div>
          </div>
        </div>
        <div class="form-field">
          <label class="form-field__label">客户号</label>
          <input class="form-input" v-model="form.customerNo" disabled placeholder="查询带出" />
        </div>
        <div class="form-field">
          <label class="form-field__label">客户性质</label>
          <select class="form-select" v-model="form.customerNature">
            <option value="EXISTING">存量客户</option>
            <option value="NEW">新增客户</option>
          </select>
        </div>

        <!-- 对公字段(数仓带出,只读) -->
        <template v-if="form.customerScope !== 'INDIVIDUAL'">
          <div class="form-field">
            <label class="form-field__label">企业性质</label>
            <select class="form-select" v-model="form.customerType">
              <option value="NON_SOE">非国企</option>
              <option value="SOE">国企</option>
            </select>
          </div>
          <div class="form-field">
            <label class="form-field__label">统一社会信用代码</label>
            <input class="form-input" v-model="form.ucrCode" disabled placeholder="数仓带出" />
          </div>
          <div class="form-field">
            <label class="form-field__label">五级分类</label>
            <input class="form-input" v-model="form.fiveLevelClass" disabled placeholder="数仓带出" />
          </div>
          <div class="form-field">
            <label class="form-field__label">内部信用等级</label>
            <input class="form-input" v-model="form.creditLevel" disabled placeholder="数仓带出" />
          </div>
        </template>
        <!-- 对私字段(数仓带出,只读) -->
        <template v-else>
          <div class="form-field">
            <label class="form-field__label">证件类型</label>
            <input class="form-input" v-model="form.idType" disabled placeholder="数仓带出" />
          </div>
          <div class="form-field">
            <label class="form-field__label">证件号码</label>
            <input class="form-input" v-model="form.idNo" disabled placeholder="数仓带出" />
          </div>
          <div class="form-field">
            <label class="form-field__label">职业</label>
            <input class="form-input" v-model="form.occupation" disabled placeholder="数仓带出" />
          </div>
          <div class="form-field">
            <label class="form-field__label">联系电话</label>
            <input class="form-input" v-model="form.phone" disabled placeholder="数仓带出" />
          </div>
        </template>
        <div class="form-field">
          <label class="form-field__label">开户机构</label>
          <input class="form-input" v-model="form.openOrg" disabled placeholder="数仓带出" />
        </div>
        <div class="form-field">
          <label class="form-field__label">开户日期</label>
          <input class="form-input" v-model="form.openDate" disabled placeholder="数仓带出" />
        </div>
        <div class="form-field">
          <label class="form-field__label">申请机构</label>
          <input class="form-input" :value="applyOrgText" disabled />
        </div>
      </div>
    </div>

    <!-- 2. 存款分项(结构化 depositItems,不再拼 remark) -->
    <div class="form-card">
      <div class="form-card__title">
        存款分项
        <span class="badge badge--warning">存款越高越优惠(HIGHER_BETTER)</span>
      </div>
      <div class="section-tip" style="margin-bottom:12px">
        每条分项按“产品/期限/金额/申请利率/账号”结构化提交;选择数仓存款账户自动带出产品与当前执行利率,也可录入拟开户方案。数仓账号为密文存储,选择存量账户时请补录真实账号(可选,后端加密落库)。
      </div>
      <table class="table" v-if="items.length">
        <thead>
          <tr>
            <th>#</th>
            <th>账户方式 <span class="req">*</span></th>
            <th>存款账户</th>
            <th>产品 <span class="req">*</span></th>
            <th>期限</th>
            <th>金额(万元) <span class="req">*</span></th>
            <th>币种</th>
            <th>当前执行利率(%)</th>
            <th>申请利率(%) <span class="req">*</span></th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(d, i) in items" :key="i">
            <td>{{ i + 1 }}</td>
            <td>
              <select class="form-select" v-model="d.accountMode" @change="onAccountModeChange(d)">
                <option value="EXISTING">存量账户调价</option>
                <option value="PLANNED">拟开户方案</option>
              </select>
            </td>
            <td>
              <template v-if="d.accountMode === 'EXISTING'">
                <select class="form-select" v-model="d.accountRef" @change="onAccountSelect(d)">
                  <option value="">不关联数仓账户</option>
                  <option v-for="(a, ai) in depositAccounts" :key="ai" :value="String(ai)">
                    {{ productName(a.productCode) }} · 余额 {{ a.accountBalance ?? '-' }} 万 · {{ a.executionRate }}%
                  </option>
                </select>
                <input class="form-input" v-model="d.depositAccountNo" placeholder="存款账号(可选)" style="margin-top:4px" />
              </template>
              <span v-else class="badge badge--neutral">拟开户</span>
            </td>
            <td>
              <select class="form-select" v-model="d.productCode">
                <option value="" disabled>选择产品</option>
                <option v-for="p in depositProducts" :key="p.code" :value="p.code">{{ p.name }}</option>
              </select>
            </td>
            <td>
              <div style="display:flex;gap:4px">
                <input class="form-input form-input--amount" v-model="d.termValue" placeholder="数值" style="width:80px" />
                <select class="form-select" v-model="d.termUnit" style="width:80px">
                  <option value="DAY">天</option><option value="MONTH">月</option><option value="YEAR">年</option>
                </select>
              </div>
            </td>
            <td><input class="form-input form-input--amount" v-model="d.amount" /></td>
            <td>
              <select class="form-select" v-model="d.currency">
                <option v-for="c in currencies" :key="c" :value="c">{{ c }}</option>
              </select>
            </td>
            <td><input class="form-input form-input--amount" v-model="d.originalRate" disabled placeholder="账户带出" /></td>
            <td><input class="form-input form-input--amount" v-model="d.requestedRate" placeholder="高于当前执行利率" /></td>
            <td><button class="btn btn--text" @click="items.splice(i, 1)" v-if="items.length > 1">删除</button></td>
          </tr>
        </tbody>
      </table>
      <button class="btn btn--secondary" style="margin-top:12px" @click="addItem">＋ 添加存款分项</button>
    </div>

    <!-- 3. 提交预览(路由预览 + 提交校验 + 正式提交) -->
    <div class="form-card">
      <div class="form-card__title">提交预览</div>
      <div class="section-tip" style="margin-bottom:12px">提交前先生成/保存草稿,再预览审批路由;正式提交需通过数据批次差异与质量预校验确认。</div>
      <div class="form-field">
        <label class="form-field__label">申请备注(客户经理手工描述,展示在审批界面)</label>
        <textarea class="form-input" v-model="form.applicationRemark" rows="3" placeholder="可描述申请背景、特殊情况等" style="width:100%;resize:vertical"></textarea>
      </div>

      <template v-if="routeResult">
        <div class="sub-title">
          审批路由预览
          <span class="badge badge--info">LPR 版本:{{ routeResult.lprVersionCode || '暂无数据' }}</span>
        </div>
        <table class="table" v-if="routeResult.items?.length">
          <thead>
            <tr><th>分项编号</th><th>产品</th><th>申请利率</th><th>比较方向</th><th>路由链路</th><th>终审岗位</th><th>硬边界</th></tr>
          </thead>
          <tbody>
            <tr v-for="it in routeResult.items" :key="it.pricingItemId">
              <td>{{ it.pricingItemNo }}</td>
              <td>{{ productName(it.productCode || '') }}</td>
              <td class="num">{{ it.requestedRate != null ? it.requestedRate + '%' : '—' }}</td>
              <td>{{ directionName(it.rateDirection) }}</td>
              <td>
                <template v-if="it.errorCode">
                  <span class="badge badge--danger">路由失败:{{ it.errorMessage || it.errorCode }}</span>
                </template>
                <template v-else-if="it.routeChain?.length">
                  <span v-for="(n, ni) in it.routeChain" :key="ni">
                    <span class="route-node">{{ nodeName(n) }}</span><span v-if="ni < it.routeChain.length - 1"> → </span>
                  </span>
                </template>
                <span v-else>暂无数据</span>
              </td>
              <td>{{ nodeName(it.finalNodeCode) }}</td>
              <td>
                <span v-if="it.hardBoundaryPass === true" class="badge badge--success">通过({{ it.hardBoundaryRate }}%)</span>
                <span v-else-if="it.hardBoundaryPass === false" class="badge badge--danger">突破({{ it.hardBoundaryRate }}%)</span>
                <span v-else class="badge badge--neutral">暂无数据</span>
              </td>
            </tr>
          </tbody>
        </table>
        <div class="empty" v-else>暂无存款分项,无法预览路由</div>
      </template>

      <div style="display:flex;gap:12px;margin-top:16px">
        <button class="btn btn--secondary" :disabled="saving" @click="onSaveDraft">存草稿</button>
        <button class="btn btn--secondary" :disabled="saving" @click="onRoutePreview">路由预览</button>
        <button class="btn btn--primary" :disabled="saving" @click="onSubmit">提交申请</button>
      </div>
    </div>

    <!-- 提交前校验确认弹窗 -->
    <SubmitCheckDialog v-model="checkDialogVisible" :check="checkResult" :submitting="submitting" @confirm="onConfirmSubmit" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@/store/user'
import {
  searchCustomers as apiSearchCustomers,
  getCustomerDetail,
  getCustomerBusinessView,
  createApplication,
  saveApplication,
  getApplicationDetail,
  routePreview,
  submitCheck,
  submitApplication,
  reapplyApplication,
  nodeName,
  directionName,
  type ApplicationPayload,
  type RoutePreview,
  type SubmitCheck
} from '@/api/application'
import SubmitCheckDialog from './SubmitCheckDialog.vue'

const userStore = useUserStore()
const route = useRoute()

const currencies = ['CNY', 'USD', 'EUR', 'HKD', 'JPY']
// 存款产品(product_code 与产品边界/数仓账户口径对齐)
const depositProducts = [
  { code: 'CORP_TIME_DEPOSIT', name: '对公定期存款' },
  { code: 'AGREEMENT_DEPOSIT', name: '协定存款' },
  { code: 'NOTICE_DEPOSIT', name: '通知存款' },
  { code: 'BANK_ACCEPTANCE_MARGIN', name: '银票保证金' },
  { code: 'LC_MARGIN', name: '信用证保证金' }
]
function productName(code: string) {
  return depositProducts.find((p) => p.code === code)?.name || code || '暂无数据'
}

interface DepositItemRow {
  accountMode: 'EXISTING' | 'PLANNED'
  accountRef: string // depositAccounts 下标,空=不关联
  depositAccountNo: string
  productCode: string
  termValue: string
  termUnit: string
  amount: string
  currency: string
  originalRate: string
  requestedRate: string
}
function newItem(): DepositItemRow {
  return {
    accountMode: 'EXISTING', accountRef: '', depositAccountNo: '',
    productCode: '', termValue: '', termUnit: 'MONTH',
    amount: '', currency: 'CNY', originalRate: '', requestedRate: ''
  }
}

const form = reactive({
  customerScope: 'CORPORATE',
  customerName: '',
  customerNo: '',
  customerNature: 'EXISTING',
  customerType: 'NON_SOE',
  // 对公(数仓带出,只读)
  ucrCode: '', fiveLevelClass: '', creditLevel: '',
  // 对私(数仓带出,只读)
  idType: '', idNo: '', occupation: '', phone: '',
  openOrg: '', openDate: '',
  applicationRemark: ''
})
const items = ref<DepositItemRow[]>([newItem()])
const depositAccounts = ref<any[]>([])

const applyOrgText = computed(() => userStore.userInfo?.orgId ? `机构 #${userStore.userInfo.orgId}` : '暂无数据')

// 草稿与提交闭环状态
const draft = reactive<{ id: number | null; versionNo: number | null; applicationNo: string }>({ id: null, versionNo: null, applicationNo: '' })
const saving = ref(false)
const submitting = ref(false)
const routeResult = ref<RoutePreview | null>(null)
const checkResult = ref<SubmitCheck | null>(null)
const checkDialogVisible = ref(false)

// ---------- 客户查询带出(与贷款申请同一逻辑) ----------
const customerCands = ref<any[]>([])
async function searchCustomers() {
  if (!form.customerName || !form.customerName.trim()) return
  try {
    customerCands.value = await apiSearchCustomers(form.customerName.trim())
    if (!customerCands.value.length) ElMessage.info('未查询到匹配客户')
  } catch {
    customerCands.value = []
  }
}

async function selectCustomer(c: any) {
  form.customerNo = c.customerNo
  form.customerName = c.customerName
  form.customerScope = c.custType === 'INDV' ? 'INDIVIDUAL' : 'CORPORATE'
  customerCands.value = []
  await loadCustomerDetail()
}

async function loadCustomerDetail() {
  if (!form.customerNo) return
  try {
    const detail = await getCustomerDetail(form.customerNo)
    const basic = detail.basic || {}
    form.ucrCode = basic.certNo || ''
    form.fiveLevelClass = basic.fiveLevelClass || ''
    form.creditLevel = basic.creditLevel || ''
    form.openOrg = basic.openOrgName || ''
    form.openDate = basic.openDate || ''
    form.idType = basic.certType || ''
    form.idNo = basic.certNo || ''
    form.occupation = basic.occupation || ''
    form.phone = basic.phone || ''
    form.customerNature = basic.customerClass === 'NEW' ? 'NEW' : 'EXISTING'
  } catch {
    // 拦截器已提示
  }
  // 存款账户(业务视图,最新批次)
  try {
    const view = await getCustomerBusinessView(form.customerNo)
    depositAccounts.value = view.depositAccounts || []
  } catch {
    depositAccounts.value = []
  }
}

// ---------- 存款分项 ----------
function addItem() {
  items.value.push(newItem())
}
function onAccountModeChange(d: DepositItemRow) {
  d.accountRef = ''
  d.depositAccountNo = ''
  d.originalRate = ''
}
function onAccountSelect(d: DepositItemRow) {
  if (d.accountRef === '') {
    d.originalRate = ''
    return
  }
  const a = depositAccounts.value[Number(d.accountRef)]
  if (a) {
    d.productCode = a.productCode || d.productCode
    d.termValue = a.termValue != null ? String(a.termValue) : d.termValue
    d.termUnit = a.termUnit || d.termUnit
    d.currency = a.currency || d.currency
    d.originalRate = a.executionRate != null ? String(a.executionRate) : ''
  }
}

// ---------- 提交闭环:创建/保存草稿 → submit-check → 确认 → submit ----------
function isBlank(v: any) {
  return v === undefined || v === null || String(v).trim() === ''
}

function validateForDraft(): string | null {
  if (isBlank(form.customerNo)) return '请先查询并选择客户'
  if (!items.value.length) return '请至少录入一条存款分项'
  for (let i = 0; i < items.value.length; i++) {
    const d = items.value[i]
    if (isBlank(d.productCode)) return `第 ${i + 1} 条存款分项未选择产品`
    if (isBlank(d.termValue)) return `第 ${i + 1} 条存款分项未录入期限`
    if (isBlank(d.amount)) return `第 ${i + 1} 条存款分项未录入金额`
    if (isBlank(d.requestedRate)) return `第 ${i + 1} 条存款分项未录入申请利率`
  }
  return null
}

function buildPayload(): ApplicationPayload {
  const scopeMap: Record<string, ApplicationPayload['customerScope']> = {
    CORPORATE: 'CORPORATE_SINGLE', INDIVIDUAL: 'INDIVIDUAL'
  }
  return {
    businessType: 'DEPOSIT',
    customerScope: scopeMap[form.customerScope] || 'CORPORATE_SINGLE',
    customerNo: form.customerNo,
    depositItems: items.value.map((d) => ({
      productCode: d.productCode,
      termValue: d.termValue,
      termUnit: d.termUnit,
      amount: d.amount,
      currency: d.currency || 'CNY',
      requestedRate: d.requestedRate,
      originalRate: isBlank(d.originalRate) ? undefined : d.originalRate,
      depositAccountNo: d.accountMode === 'EXISTING' && !isBlank(d.depositAccountNo) ? d.depositAccountNo : undefined,
      plannedAccountFlag: d.accountMode === 'PLANNED' ? 'Y' : 'N'
    })),
    applicantUserId: userStore.userInfo?.userId,
    applicantOrgId: userStore.userInfo?.orgId,
    orgId: userStore.userInfo?.orgId,
    applicationRemark: form.applicationRemark || undefined
  }
}

/** 创建或保存草稿;保存(PUT)仅更新主单字段,需携带 versionNo */
async function ensureDraft(): Promise<boolean> {
  const err = validateForDraft()
  if (err) {
    ElMessage.error(err)
    return false
  }
  saving.value = true
  try {
    const payload = buildPayload()
    if (draft.id) {
      const saved = await saveApplication(draft.id, { ...payload, versionNo: draft.versionNo ?? undefined })
      draft.versionNo = saved.versionNo
    } else {
      const created = await createApplication(payload)
      draft.id = created.id
      draft.versionNo = created.versionNo
      draft.applicationNo = created.applicationNo
    }
    return true
  } catch {
    return false
  } finally {
    saving.value = false
  }
}

async function onSaveDraft() {
  if (await ensureDraft()) {
    ElMessage.success(`草稿已保存,申请号 ${draft.applicationNo}(版本 v${draft.versionNo})`)
  }
}

async function onRoutePreview() {
  if (!(await ensureDraft()) || !draft.id) return
  try {
    routeResult.value = await routePreview(draft.id)
  } catch {
    routeResult.value = null
  }
}

async function onSubmit() {
  if (!(await ensureDraft()) || !draft.id) return
  try {
    checkResult.value = await submitCheck(draft.id)
    checkDialogVisible.value = true
  } catch {
    checkResult.value = null
  }
}

async function onConfirmSubmit() {
  if (!draft.id) return
  submitting.value = true
  try {
    const result = await submitApplication(draft.id)
    checkDialogVisible.value = false
    const firstNode = nodeName(result.items?.[0]?.currentNodeCode)
    const finalNode = nodeName(result.items?.[0]?.routeCode)
    ElMessageBox.alert(
      `申请号:${result.applicationNo}\n当前节点:${firstNode}\n终审岗位:${finalNode}\n提交时间:${result.submitTime || '—'}`,
      result.submitted === false ? '申请已提交(幂等返回)' : '提交成功',
      { confirmButtonText: '知道了' }
    )
  } catch {
    // 拦截器已提示
  } finally {
    submitting.value = false
  }
}

// ---------- 关联重提(?reapply={applicationId}:生成新草稿并加载内容) ----------
onMounted(async () => {
  const src = route.query.reapply
  if (!src) return
  try {
    const newDraft = await reapplyApplication(String(src))
    draft.id = newDraft.id
    draft.versionNo = newDraft.versionNo
    draft.applicationNo = newDraft.applicationNo
    await loadDraftIntoForm(newDraft.id)
    ElMessage.success(`已基于原申请 #${src} 生成新草稿 ${newDraft.applicationNo},请调整后提交`)
  } catch {
    // 拦截器已提示
  }
})

async function loadDraftIntoForm(id: number) {
  const d = await getApplicationDetail(id)
  const app = d.application
  form.customerScope = app.customerScope === 'INDIVIDUAL' ? 'INDIVIDUAL' : 'CORPORATE'
  form.customerNo = app.customerNo || ''
  form.applicationRemark = app.applicationRemark || ''
  if (app.customerNo) {
    await loadCustomerDetail()
  }
  const editable = (d.pricingItems || []).filter((p) => p.inheritFlag !== 'Y')
  items.value = editable.map((p) => {
    const rel = (d.depositRelations || []).find((r) => r.pricingItemId === p.id)
    const row = newItem()
    row.accountMode = rel?.plannedAccountFlag === 'Y' ? 'PLANNED' : 'EXISTING'
    row.productCode = p.productCode || ''
    row.termValue = p.termValue != null ? String(p.termValue) : ''
    row.termUnit = p.termUnit || 'MONTH'
    row.amount = p.pricingAmount != null ? String(p.pricingAmount) : ''
    row.currency = p.currency || 'CNY'
    row.originalRate = p.originalRate != null ? String(p.originalRate) : ''
    row.requestedRate = p.requestedRate != null ? String(p.requestedRate) : ''
    return row
  })
  if (!items.value.length) items.value = [newItem()]
}
</script>

<style scoped>
.section-head { margin-bottom: 20px; }
.eyebrow { font-size: 12px; color: var(--color-text-light); letter-spacing: 1px; margin-bottom: 4px; }
.section-title { font-size: var(--fs-h1); font-weight: 700; margin-bottom: 6px; }
.section-tip { font-size: 13px; color: var(--color-text-sub); }
.form-card {
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  padding: var(--space-4);
  margin-bottom: 16px;
  box-shadow: var(--shadow-sm);
}
.form-card__title { font-size: var(--fs-h3); font-weight: 600; margin-bottom: 16px; display: flex; align-items: center; gap: 8px; }
.form-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; }
.table { border-radius: var(--radius); overflow: hidden; }
.customer-cands { margin-top: 8px; border: 1px solid var(--color-border); border-radius: var(--radius-sm); overflow: hidden; background: var(--color-surface); }
.customer-cand { padding: 8px 12px; font-size: 13px; cursor: pointer; border-bottom: 1px solid var(--color-border); }
.customer-cand:last-child { border-bottom: none; }
.customer-cand:hover { background: var(--color-primary-light); }
.req { color: var(--color-danger); }
.sub-title { font-size: 14px; font-weight: 600; margin: 0 0 8px; color: var(--color-text-main); display: flex; align-items: center; gap: 8px; }
.draft-banner { display: flex; align-items: center; gap: 8px; font-size: 13px; color: var(--color-text-sub); }
.route-node {
  display: inline-block; padding: 1px 8px; border-radius: 999px;
  background: var(--color-primary-light); color: var(--color-primary);
  font-size: 12px; font-weight: 500;
}
</style>
