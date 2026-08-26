<template>
  <div>
    <div class="section-head">
      <div class="section-title">存款利率提升申请</div>
    </div>

    <!-- 关联重提/草稿提示 -->
    <div v-if="draft.id" class="form-card draft-banner">
      <span class="badge badge--info">草稿</span>
      申请号 {{ draft.applicationNo }}(版本 v{{ draft.versionNo }})
      <InfoTip content="草稿保存仅更新主单信息,存款分项以创建时内容为准" />
    </div>

    <!-- 1. 客户信息(复用贷款申请的客户查询带出逻辑) -->
    <div class="form-card">
      <div class="form-card__title">
        客户信息
      </div>
      <div class="form-grid form-grid--5">
        <div class="form-field">
          <label class="form-field__label">客户主体 <span class="req">*</span></label>
          <select class="form-select" v-model="form.customerScope" @change="onCustomerScopeChange">
            <option value="CORPORATE">企业单户</option>
            <option value="GROUP">集团客户</option>
          </select>
        </div>
        <!-- 非集团:客户名称/客户号/客户性质(集团分支见下方 GROUP 模板) -->
        <template v-if="form.customerScope !== 'GROUP'">
          <div class="form-field">
            <label class="form-field__label">客户名称 <span class="req">*</span></label>
            <el-autocomplete
              v-model="form.customerName"
              :fetch-suggestions="queryCustomerSuggestions"
              :trigger-on-focus="false"
              clearable
              placeholder="输入客户名称自动联想,下拉选择客户"
              style="width:100%"
              @select="selectCustomer"
            />
          </div>
          <div class="form-field">
            <label class="form-field__label">客户号</label>
            <input class="form-input" v-model="form.customerNo" placeholder="数仓带出,可修改;新增客户可手工填写" />
          </div>
        </template>

        <!-- 集团客户:联想查询 + 集团信息带出(§2026-08-25 精简为集团客户/集团编号/统一社会信用代码;证件号码已删,信用代码数仓无字段可编辑,§2026-08-26) -->
        <template v-else>
          <div class="form-field">
            <label class="form-field__label">集团客户名称 <span class="req">*</span></label>
            <el-autocomplete v-model="form.groupName" :fetch-suggestions="queryGroupSuggestions" :trigger-on-focus="false" clearable
              placeholder="输入集团名称联想选择;未收录回车补录" style="width:100%" @select="selectGroup" @keyup.enter="queryGroup" />
          </div>
          <div class="form-field"><label class="form-field__label">集团客户编号</label>
            <input class="form-input" :value="form.groupNo" readonly placeholder="查询后带出" /></div>
          <div class="form-field"><label class="form-field__label">统一社会信用代码</label>
            <input class="form-input" v-model="form.ucrCode" placeholder="数仓无,请手工填写" /></div>
        </template>

        <!-- 对公字段(数仓带出,可修改;所属行业已删,后续需要再加,§2026-08-26) -->
        <template v-if="form.customerScope === 'CORPORATE'">
          <div class="form-field">
            <label class="form-field__label">统一社会信用代码</label>
            <input class="form-input" v-model="form.ucrCode" placeholder="数仓带出,可修改" />
          </div>
        </template>
      </div>
    </div>


    <!-- 2. 存款分项(结构化 depositItems,不再拼 remark) -->
    <div class="form-card">
      <div class="form-card__title">
        存款分项
      </div>
      <!-- 每分项一张卡片(字段带标签分行分块,避免多列横向滚动导致关键字段看不见;提交结构不变) -->
      <div v-for="(d, i) in items" :key="i" class="mortgage-item deposit-item">
        <div class="mortgage-item__head">
          <span class="deposit-item__title">存款分项 {{ i + 1 }}</span>
          <button class="btn btn--text" @click="items.splice(i, 1)" v-if="items.length > 1">删除</button>
        </div>
        <!-- 8 字段均匀 2 行×4 列:账户方式/存款账户/产品/期限 + 金额/币种/当前执行利率/申请利率 -->
        <div class="mortgage-item__grid">
          <div class="form-field">
            <label class="form-field__label">账户方式 <span class="req">*</span></label>
            <select class="form-select" v-model="d.accountMode" @change="onAccountModeChange(d)">
              <option value="EXISTING">存量账户调价</option>
              <option value="PLANNED">拟开户方案</option>
            </select>
          </div>
          <div class="form-field">
            <label class="form-field__label">存款账户</label>
            <template v-if="d.accountMode === 'EXISTING'">
              <!-- 存量账户下拉:按当前客户数仓账户列表选择并自动带出(§2026-08-25);集团/无数仓账户时回退手工输入+反查 -->
              <select v-if="form.customerScope !== 'GROUP' && accountOptions.length" class="form-select" :value="d.depositAccountNo" @change="onAccountPick(d, $event)">
                <option value="">请选择存款账户</option>
                <option v-for="a in accountOptions" :key="a.depositAccountNo" :value="a.depositAccountNo">
                  {{ a.depositAccountNo }}<span v-if="a.accountBalance != null"> · 余额{{ a.accountBalance }}万</span><span v-if="a.executionRate != null"> · 利率{{ a.executionRate }}%</span>
                </option>
              </select>
              <input v-else class="form-input" v-model="d.depositAccountNo" placeholder="输入存款账号,自动查询数仓" @blur="onAccountLookup(d)" />
              <div v-if="d.lookupFound" class="section-tip" style="color:var(--color-success);margin-top:4px">
                数仓已匹配:余额 {{ d.accountBalance ?? '-' }} 万 · 当前利率 {{ d.originalRate || '-' }}% · 开户 {{ d.openDate || '-' }} · 到期 {{ d.maturityDate || '-' }}
              </div>
              <div v-else-if="d.lookupDone" class="section-tip" style="color:var(--color-warning);margin-top:4px">
                数仓未找到该账户,请手工完善产品/期限/原利率
              </div>
            </template>
            <span v-else class="badge badge--neutral">拟开户(未开户业务)</span>
          </div>
          <div class="form-field">
            <label class="form-field__label">产品 <span class="req">*</span></label>
            <select class="form-select" v-model="d.productCode" @change="onProductChange(d)">
              <option value="" disabled>选择产品</option>
              <option v-for="p in depositProducts" :key="p.code" :value="p.code">{{ p.name }}</option>
            </select>
          </div>
          <div class="form-field">
            <label class="form-field__label">期限 <span class="req" v-if="termRequired(d.productCode)">*</span></label>
            <template v-if="!termRequired(d.productCode)">
              <span class="badge badge--neutral">{{ termNoneText(d.productCode) }}</span>
            </template>
            <template v-else>
              <select class="form-select" v-model="d.termOption" @change="onTermOptionChange(d)" style="width:100%">
                <option value="" disabled>选择期限</option>
                <option v-for="o in termOptionsFor(d)" :key="o.value" :value="o.value">{{ o.label }}</option>
              </select>
            </template>
          </div>
          <div class="form-field">
            <label class="form-field__label">金额(万元) <span class="req">*</span></label>
            <input class="form-input form-input--amount" v-model="d.amount" type="number" min="0" max="999999999.99" step="0.0001" />
          </div>
          <div class="form-field">
            <label class="form-field__label">币种</label>
            <select class="form-select" v-model="d.currency">
              <option v-for="c in currencies" :key="c" :value="c">{{ currencyText(c) }}</option>
            </select>
          </div>
          <div class="form-field">
            <label class="form-field__label">当前执行利率(%)</label>
            <input class="form-input form-input--amount" v-model="d.originalRate" disabled placeholder="账户带出" />
          </div>
          <div class="form-field">
            <label class="form-field__label">申请利率(%) <span class="req">*</span></label>
            <input class="form-input form-input--amount" v-model="d.requestedRate" type="number" min="0" max="100" step="0.000001" placeholder="高于当前执行利率" />
          </div>
          <div class="form-field">
            <label class="form-field__label">测算利率(%) <span class="req">*</span></label>
            <input class="form-input form-input--amount" v-model="d.calculatedRate" type="number" min="0" max="100" step="0.000001" placeholder="如 1.65" />
          </div>
        </div>
      </div>
      <button class="btn btn--secondary" style="margin-top:12px" @click="addItem">＋ 添加存款分项</button>
    </div>

    <!-- 3. 提交预览(路由预览 + 提交校验 + 正式提交) -->
    <div class="form-card">
      <div class="form-card__title">
        提交预览
        <InfoTip content="提交前先生成/保存草稿,再预览审批路由;正式提交需通过数据批次差异与质量预校验确认。" />
      </div>
      <div class="form-field form-field--stack">
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
              <td>{{ rateDirectionText(it.rateDirection) }}</td>
              <td>
                <template v-if="it.errorCode">
                  <span class="badge badge--danger">路由失败:{{ it.errorMessage || it.errorCode }}</span>
                </template>
                <template v-else-if="it.routeChain?.length">
                  <span v-for="(n, ni) in it.routeChain" :key="ni">
                    <span class="route-node">{{ nodeLabel(n) }}</span><span v-if="ni < it.routeChain.length - 1"> → </span>
                  </span>
                </template>
                <span v-else>暂无数据</span>
              </td>
              <td>{{ nodeLabel(it.finalNodeCode) }}</td>
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

      <div style="display:flex;gap:12px;margin-top:12px">
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
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/store/user'
import {
  searchCustomers as apiSearchCustomers,
  getCustomerDetail,
  lookupDepositAccount,
  listDepositAccounts,
  getGroup,
  getGroupMembers,
  suggestGroups,
  createApplication,
  saveApplication,
  getApplicationDetail,
  routePreview,
  submitCheck,
  submitApplication,
  reapplyApplication,
  getOpenOrgs,
  type ApplicationPayload,
  type RoutePreview,
  type SubmitCheck
} from '@/api/application'
import SubmitCheckDialog from './SubmitCheckDialog.vue'
import ContributionPanel from '@/components/ContributionPanel.vue'
import { listProductLimits } from '@/api/approval2'
import { listEnabledProducts } from '@/api/system'
import { nodeLabel, rateDirectionText, productName, DEPOSIT_PRODUCTS, certTypeText, currencyText, normalizeFiveLevelClass } from '@/utils/dict'

const userStore = useUserStore()
const route = useRoute()
const router = useRouter()

const currencies = ['CNY', 'USD', 'EUR', 'HKD', 'JPY']
// 存款产品(product_code 与产品边界/数仓账户口径对齐)
// P2-4:以产品目录 ccr_product 为权威来源,目录为空时回退内置字典(避免新建环境缺目录不可用)
const depositProducts = ref<Array<{ code: string; name: string }>>(
  DEPOSIT_PRODUCTS.map((p) => ({ code: p.code, name: p.name }))
)
async function loadDepositProducts() {
  try {
    const rows = await listEnabledProducts('DEPOSIT')
    if (rows?.length) {
      depositProducts.value = rows.map((r) => ({ code: r.productCode, name: r.productName }))
    }
  } catch {
    // 失败保持字典回退
  }
}
// 开户机构下拉匹配(§用户要求):数据源 ccr_sys_dept 启用机构(客户经理可访问接口 /ccr/customers/open-orgs)
const openOrgOptions = ref<Array<{ id: number; deptName: string }>>([])
async function loadOpenOrgOptions() {
  try {
    const rows = await getOpenOrgs()
    openOrgOptions.value = (rows || []).map((r) => ({ id: r.id, deptName: r.deptName }))
  } catch {
    // 失败保持空,不影响手工输入
  }
}

interface DepositItemRow {
  accountMode: 'EXISTING' | 'PLANNED'
  depositAccountNo: string // 存量模式输入真实账号,自动反查数仓
  accountBalance: string | number | null
  openDate: string
  maturityDate: string
  accountStatus: string
  lookupDone: boolean
  lookupFound: boolean
  productCode: string
  termOption: string // 期限下拉选中值(格式 tv:tu;协定存款为空)
  termValue: string
  termUnit: string
  amount: string
  currency: string
  originalRate: string
  requestedRate: string
  calculatedRate: string
}
function newItem(): DepositItemRow {
  return {
    accountMode: 'EXISTING', depositAccountNo: '',
    accountBalance: null, openDate: '', maturityDate: '', accountStatus: '',
    lookupDone: false, lookupFound: false,
    productCode: '', termOption: '', termValue: '', termUnit: '',
    amount: '', currency: 'CNY', originalRate: '', requestedRate: '', calculatedRate: ''
  }
}

const form = reactive({
  customerScope: 'CORPORATE',
  customerName: '',
  customerNo: '',
  customerNature: '', // 客户性质由数仓 customerClass 自动判定(存量/新增),不允许手选
  customerType: 'NON_SOE',
  // 集团(集团客户主体:联想查询带出集团信息,按集团整体申请)
  groupNo: '',
  groupName: '', // 集团名称(联想选择显示;新增集团手输)
  // 对公(数仓带出,只读)
  ucrCode: '', fiveLevelClass: '', creditLevel: '', industry: '', basicAccount: '',
  // 对私(数仓带出,只读)
  idType: '', idNo: '', occupation: '', phone: '',
  openOrg: '', openDate: '',
  applicationRemark: ''
})
const items = ref<DepositItemRow[]>([newItem()])
// 集团信息(查询带出;未收录=新增集团就地补录,见 loan.vue 同构逻辑)
const groupInfo = ref<any | null>(null)
const groupCredit = ref<any | null>(null)
const groupQueried = ref(false)
const groupMembers = ref<any[]>([])
const isNewGroup = ref(false)
// 存量账户下拉选项(按当前客户数仓账户列表;集团主体无数仓账户,回退手工输入)
const accountOptions = ref<Array<Record<string, any>>>([])
// 关联人员(§12.4④,后端无独立接收字段,序列化后随申请备注附带)
const contribution = ref<any[]>([])
// 产品标准上限(生效中的存款硬边界;非 admin 可能 403,失败则隐藏)
const productLimits = ref<any[]>([])

function limitOf(productCode: string) {
  if (!productCode) return null
  return productLimits.value.find((l) => l.productCode === productCode && l.businessType === 'DEPOSIT') || null
}
// 较上限 BP:1% = 100BP,存款申请利率超过硬边界为正向风险提示
function bpOf(d: DepositItemRow): number | null {
  const limit = limitOf(d.productCode)
  const rate = Number(d.requestedRate)
  if (!limit || !d.requestedRate || Number.isNaN(rate)) return null
  return Math.round((rate - Number(limit.hardBoundaryRate)) * 100)
}
function exceedOf(d: DepositItemRow): boolean {
  const bp = bpOf(d)
  return bp != null && bp > 0
}

// 存款期限按产品类型下拉(需求:不手输直接选):对公定期=3/6月·1/2/3年;通知存款=1天/7天;协定存款无固定期限
const DEPOSIT_TERM_OPTIONS: Record<string, { value: string; label: string; tv: string; tu: string }[]> = {
  CORP_TIME_DEPOSIT: [
    { value: '3:MONTH', label: '3个月', tv: '3', tu: 'MONTH' },
    { value: '6:MONTH', label: '6个月', tv: '6', tu: 'MONTH' },
    { value: '1:YEAR', label: '一年', tv: '1', tu: 'YEAR' },
    { value: '2:YEAR', label: '两年', tv: '2', tu: 'YEAR' },
    { value: '3:YEAR', label: '三年', tv: '3', tu: 'YEAR' },
  ],
  NOTICE_DEPOSIT: [
    { value: '1:DAY', label: '一天通知', tv: '1', tu: 'DAY' },
    { value: '7:DAY', label: '7天通知', tv: '7', tu: 'DAY' },
  ],
}
const TERM_UNIT_TEXT: Record<string, string> = { DAY: '天', MONTH: '个月', YEAR: '年' }
function termUnitText(tu: string) {
  return TERM_UNIT_TEXT[tu] || tu
}
function termRequired(productCode: string) {
  // 仅对公定期/通知存款有期限;协定存款与保证金存款(银票/信用证)无期限
  return productCode === 'CORP_TIME_DEPOSIT' || productCode === 'NOTICE_DEPOSIT'
}
const TERM_NONE_TEXT: Record<string, string> = {
  AGREEMENT_DEPOSIT: '协定存款无固定期限',
  BANK_ACCEPTANCE_MARGIN: '保证金存款无期限',
  LC_MARGIN: '保证金存款无期限',
}
function termNoneText(productCode: string) {
  return TERM_NONE_TEXT[productCode] || (productCode ? '该产品无需选择期限' : '选择产品后确定期限')
}
// 产品→期限选项;数仓带出的期限不在固定选项时,追加自定义选项保底(避免下拉无法选中已带出值)
function termOptionsFor(d: DepositItemRow) {
  const base = DEPOSIT_TERM_OPTIONS[d.productCode] || []
  const opts = base.slice()
  const cur = d.termValue && d.termUnit ? `${d.termValue}:${d.termUnit}` : ''
  if (cur && !opts.some((o) => o.value === cur)) {
    opts.push({ value: cur, label: `自定义(${d.termValue}${termUnitText(d.termUnit)})`, tv: d.termValue, tu: d.termUnit })
  }
  return opts
}
function onTermOptionChange(d: DepositItemRow) {
  const opt = termOptionsFor(d).find((o) => o.value === d.termOption)
  if (opt) {
    d.termValue = opt.tv
    d.termUnit = opt.tu
  }
}
// 切换产品:重置期限(旧产品的期限对新产品不适用)
function onProductChange(d: DepositItemRow) {
  d.termValue = ''
  d.termUnit = ''
  d.termOption = ''
}

const applyOrgText = computed(() => userStore.userInfo?.orgName || (userStore.userInfo?.orgId ? `机构 #${userStore.userInfo.orgId}` : '暂无数据'))

// 草稿与提交闭环状态
const draft = reactive<{ id: number | null; versionNo: number | null; applicationNo: string }>({ id: null, versionNo: null, applicationNo: '' })
const saving = ref(false)
const submitting = ref(false)
const routeResult = ref<RoutePreview | null>(null)
const checkResult = ref<SubmitCheck | null>(null)
const checkDialogVisible = ref(false)

// ---------- 客户查询带出(与贷款申请同一逻辑) ----------
/** 客户名称联想下拉(el-autocomplete fetch-suggestions;输入即查,取消独立查询按钮) */
async function queryCustomerSuggestions(queryString: string, cb: (list: any[]) => void) {
  if (!queryString || !queryString.trim()) return cb([])
  try {
    const rows = await apiSearchCustomers(queryString.trim())
    // 存款仅对公(§2026-08-25):联想过滤个人客户,只提供对公候选
    cb((rows || []).filter((r: any) => r.custType !== 'INDV').map((r: any) => ({
      value: `${r.customerName} · ${r.customerNo} · 对公`,
      data: r,
    })))
  } catch {
    cb([])
  }
}

async function selectCustomer(item: any) {
  const c = item?.data ?? item
  form.customerNo = c.customerNo
  form.customerName = c.customerName
  form.customerScope = 'CORPORATE'
  await loadCustomerDetail()
}

async function loadCustomerDetail() {
  if (!form.customerNo) return
  try {
    const detail = await getCustomerDetail(form.customerNo)
    const basic = detail.basic || {}
    form.ucrCode = basic.certNo || ''
    form.fiveLevelClass = normalizeFiveLevelClass(basic.fiveLevelClass || '')
    form.creditLevel = basic.creditLevel || ''
    form.industry = basic.industry || ''
    form.openOrg = basic.openOrgName || ''
    form.openDate = basic.openDate || ''
    form.basicAccount = basic.basicAccount || form.basicAccount
    form.idType = basic.certType || ''
    form.idNo = basic.certNo || ''
    form.occupation = basic.occupation || ''
    form.phone = basic.phone || ''
    form.customerNature = basic.customerClass === 'EXISTING' ? 'EXISTING' : 'NEW'
    // 企业性质带出(数仓 entp_charic 仅 SOE 判国企,其余非国企,与后端 resolveCustomerType 同口径)
    form.customerType = basic.entpCharic === 'SOE' ? 'SOE' : 'NON_SOE'
    contribution.value = detail.contribution || []
    // 存量账户下拉选项按客户号刷新(§2026-08-25)
    await loadAccountOptions()
  } catch {
    // 数仓无该客户记录(新增客户手工填写)按新户判定;其余错误由拦截器提示
    form.customerNature = 'NEW'
  }
}

/** 存量账户下拉选项(数仓列表;集团主体无数仓账户/加载失败回退手工输入+反查) */
async function loadAccountOptions() {
  accountOptions.value = []
  if (!form.customerNo || form.customerScope === 'GROUP') return
  try {
    const rows = await listDepositAccounts(form.customerNo)
    accountOptions.value = rows || []
  } catch {
    accountOptions.value = []
  }
}

/** 存量账户下拉选中:直接按数仓账户行带出产品/期限/币种/余额/当前利率(复用 onAccountLookup 赋值口径) */
function onAccountPick(d: DepositItemRow, ev: Event) {
  const no = (ev.target as HTMLSelectElement).value
  d.depositAccountNo = no
  if (!no) {
    d.accountBalance = null
    d.openDate = ''
    d.maturityDate = ''
    d.accountStatus = ''
    d.lookupDone = false
    d.lookupFound = false
    d.originalRate = ''
    d.productCode = ''
    d.termValue = ''
    d.termUnit = ''
    d.termOption = ''
    return
  }
  const a = accountOptions.value.find((x) => x.depositAccountNo === no)
  d.lookupDone = true
  if (a) {
    d.lookupFound = true
    d.accountBalance = a.accountBalance ?? null
    d.openDate = a.openDate || ''
    d.maturityDate = a.maturityDate || ''
    d.accountStatus = a.accountStatus || ''
    d.productCode = a.productCode || d.productCode
    d.termValue = a.termValue != null ? String(a.termValue) : d.termValue
    d.termUnit = a.termUnit || d.termUnit
    d.termOption = d.termValue && d.termUnit ? `${d.termValue}:${d.termUnit}` : ''
    d.currency = a.currency || d.currency
    d.originalRate = a.executionRate != null ? String(a.executionRate) : ''
  } else {
    d.lookupFound = false
    d.accountBalance = null
  }
}

// ---------- 集团查询(与贷款申请同构;存款按集团整体申请,不拉成员分项) ----------
/** 集团联想(el-autocomplete fetch-suggestions;输入即查,§13.1) */
async function queryGroupSuggestions(queryString: string, cb: (list: any[]) => void) {
  if (!queryString || !queryString.trim()) return cb([])
  try {
    const rows = await suggestGroups(queryString.trim())
    cb((rows || []).map(r => ({
      value: `${r.groupNo} · ${r.groupName}`,
      data: r,
    })))
  } catch {
    cb([])
  }
}

/** 集团下拉选中:回填集团号并加载集团信息 */
async function selectGroup(item: any) {
  const g = item?.data ?? item
  form.groupNo = g.groupNo
  form.groupName = g.groupName || ''
  await queryGroup()
}

/** 查询集团信息(数仓收录=存量带出;404=未收录按新增集团就地补录) */
async function queryGroup() {
  // autocomplete 绑定名称;手动输入编号回车时同步到 groupNo(联想选中已由 selectGroup 回填)
  if (!form.groupNo?.trim()) form.groupNo = (form.groupName || '').trim()
  if (!form.groupNo || !form.groupNo.trim()) {
    ElMessage.warning('请输入集团客户编号')
    return
  }
  const no = form.groupNo.trim()
  try {
    const g = await getGroup(no)
    isNewGroup.value = false
    groupInfo.value = g.group || null
    groupCredit.value = g.groupCredit || null
    // 集团名称带出(autocomplete 显示,§2026-08-25 名称框/编号框对齐对公)
    if (g.group?.groupName) form.groupName = g.group.groupName
  } catch {
    isNewGroup.value = true
    groupInfo.value = null
    groupCredit.value = null
  }
  groupQueried.value = true
  // 有效成员快照(提交校验:members 必须在集团有效成员快照中,集团号本身不算成员;存款按集团整体申请,等分本次申请金额构造 members)
  try {
    const members = await getGroupMembers(no)
    groupMembers.value = (members || []).filter((m: any) => m?.memberCustomerNo)
  } catch {
    groupMembers.value = []
  }
  if (isNewGroup.value) {
    ElMessage.info('数仓未收录该集团,请按新增集团补录基本信息(存款按集团整体申请)')
  }
}

/** 切客户主体:清空客户/集团信息与账户下拉,避免残留 */
function onCustomerScopeChange() {
  form.customerNo = ''
  form.customerName = ''
  form.groupNo = ''
  form.groupName = ''
  form.ucrCode = ''
  form.idNo = ''
  form.idType = ''
  form.occupation = ''
  form.phone = ''
  form.industry = ''
  form.fiveLevelClass = ''
  form.creditLevel = ''
  form.customerNature = ''
  groupInfo.value = null
  groupCredit.value = null
  groupQueried.value = false
  isNewGroup.value = false
  accountOptions.value = []
  groupMembers.value = []
  items.value = [newItem()]
}

// ---------- 存款分项 ----------
function addItem() {
  items.value.push(newItem())
}
function onAccountModeChange(d: DepositItemRow) {
  d.depositAccountNo = ''
  d.accountBalance = null
  d.openDate = ''
  d.maturityDate = ''
  d.accountStatus = ''
  d.lookupDone = false
  d.lookupFound = false
  d.originalRate = ''
}

/** 输入账号后自动反查数仓(明文匹配),命中带出产品/期限/币种/余额/当前利率,未命中不阻断 */
async function onAccountLookup(d: DepositItemRow) {
  if (isBlank(d.depositAccountNo) || isBlank(form.customerNo)) {
    d.lookupDone = false
    d.lookupFound = false
    return
  }
  try {
    const a: any = await lookupDepositAccount(form.customerNo, d.depositAccountNo.trim())
    d.lookupDone = true
    if (a) {
      d.lookupFound = true
      d.accountBalance = a.accountBalance ?? null
      d.openDate = a.openDate || ''
      d.maturityDate = a.maturityDate || ''
      d.accountStatus = a.accountStatus || ''
      d.productCode = a.productCode || d.productCode
      d.termValue = a.termValue != null ? String(a.termValue) : d.termValue
      d.termUnit = a.termUnit || d.termUnit
      d.termOption = d.termValue && d.termUnit ? `${d.termValue}:${d.termUnit}` : ''
      d.currency = a.currency || d.currency
      d.originalRate = a.executionRate != null ? String(a.executionRate) : ''
    } else {
      d.lookupFound = false
      d.accountBalance = null
    }
  } catch {
    d.lookupDone = true
    d.lookupFound = false
  }
}

// ---------- 提交闭环:创建/保存草稿 → submit-check → 确认 → submit ----------
function isBlank(v: any) {
  return v === undefined || v === null || String(v).trim() === ''
}

function validateForDraft(): string | null {
  // 集团主体:按集团整体申请,校验集团编号已查询(不校验客户号/证件号)
  if (form.customerScope === 'GROUP') {
    if (isBlank(form.groupNo) || !groupQueried.value) return '请录入集团编号并查询加载集团信息'
  } else {
    // 新增客户无客户号:允许先录证件号(对公 ucrCode),提交时后端反查数仓/占位(2026-08-20 #017)
    const hasIdentity = !isBlank(form.customerNo) || !isBlank(form.ucrCode)
    if (!hasIdentity) return '请先查询并选择客户,或录入证件号(新增客户可先录证件号)'
  }
  if (!items.value.length) return '请至少录入一条存款分项'
  for (let i = 0; i < items.value.length; i++) {
    const d = items.value[i]
    if (d.accountMode === 'EXISTING' && isBlank(d.depositAccountNo)) return `第 ${i + 1} 条存款分项为存量调价,请录入存款账号`
    if (isBlank(d.productCode)) return `第 ${i + 1} 条存款分项未选择产品`
    // 期限:协定存款无固定期限可空,其余(对公定期/通知/保证金)必填下拉
    if (termRequired(d.productCode)) {
      if (isBlank(d.termValue)) return `第 ${i + 1} 条存款分项未选择期限`
      const tv = Number(d.termValue)
      if (!Number.isInteger(tv) || tv < 1) return `第 ${i + 1} 条存款分项期限须为正整数(当前 ${d.termValue})`
    }
    if (isBlank(d.amount)) return `第 ${i + 1} 条存款分项未录入金额`
    const amt = Number(d.amount)
    if (!(amt > 0 && amt <= 999999999.99)) return `第 ${i + 1} 条存款分项金额须在 0~999999999.99 万元之间(当前 ${d.amount})`
    if (isBlank(d.requestedRate)) return `第 ${i + 1} 条存款分项未录入申请利率`
    const rt = Number(d.requestedRate)
    if (!(rt > 0 && rt <= 100)) return `第 ${i + 1} 条存款分项申请利率须在 0~100 之间(当前 ${d.requestedRate})`
    if (isBlank(d.calculatedRate)) return `第 ${i + 1} 条存款分项未录入测算利率`
    const crt = Number(d.calculatedRate)
    if (!(crt > 0 && crt <= 100)) return `第 ${i + 1} 条存款分项测算利率须在 0~100 之间(当前 ${d.calculatedRate})`
  }
  return null
}

function buildPayload(): ApplicationPayload {
  const isGroup = form.customerScope === 'GROUP'
  const scopeMap: Record<string, ApplicationPayload['customerScope']> = {
    CORPORATE: 'CORPORATE_SINGLE', INDIVIDUAL: 'INDIVIDUAL', GROUP: 'GROUP'
  }
  const payload: ApplicationPayload = {
    businessType: 'DEPOSIT',
    customerScope: scopeMap[form.customerScope] || 'CORPORATE_SINGLE',
    customerNo: isGroup ? null : form.customerNo,
    depositItems: items.value.map((d) => ({
      // 集团主体:后端 newPricingItem 强制 GROUP 分项携带 memberCustomerNo,按集团整体视为唯一成员(§2026-08-25)
      memberCustomerNo: isGroup ? form.groupNo : undefined,
      productCode: d.productCode,
      termValue: d.termValue,
      termUnit: d.termUnit,
      amount: d.amount,
      currency: d.currency || 'CNY',
      requestedRate: d.requestedRate,
      calculatedRate: d.calculatedRate,
      originalRate: isBlank(d.originalRate) ? undefined : d.originalRate,
      depositAccountNo: d.accountMode === 'EXISTING' && !isBlank(d.depositAccountNo) ? d.depositAccountNo : undefined,
      plannedAccountFlag: d.accountMode === 'PLANNED' ? 'Y' : 'N'
    })),
    applicantUserId: userStore.userInfo?.userId,
    applicantOrgId: userStore.userInfo?.orgId,
    orgId: userStore.userInfo?.orgId,
    // 关联人员随备注结构附带(后端申请单无独立接收字段,§12.4④)
    applicationRemark: (form.applicationRemark || '').trim() || undefined,
  }
  if (isGroup) {
    // 集团整体申请:members 用真实有效成员(后端 checkGroupConstraints 校验成员必须在集团有效成员快照中,集团号本身不算成员),金额等分合计=申请总额
    const totalAmount = items.value.reduce((s, d) => s + (Number(d.amount) || 0), 0)
    payload.groupNo = form.groupNo
    const realNos = (groupMembers.value || []).map((m: any) => m.memberCustomerNo).filter(Boolean) as string[]
    if (realNos.length && totalAmount > 0) {
      // 等分到分位,末位成员补足尾差,保证合计精确=totalAmount
      const share = Math.floor((totalAmount * 100) / realNos.length) / 100
      payload.members = realNos.map((no, i) => ({
        memberCustomerNo: no,
        requestAmount: i === realNos.length - 1
          ? +(totalAmount - share * (realNos.length - 1)).toFixed(2)
          : share,
        currency: 'CNY',
        memberRole: 'GENERAL'
      }))
    } else {
      // 无有效成员时兜底(数仓异常):仍以集团号提交,由后端校验给出明确提示
      payload.members = [{ memberCustomerNo: form.groupNo, requestAmount: totalAmount, currency: 'CNY', memberRole: 'CORE' }]
    }
    payload.groupInfoJson = JSON.stringify({
      groupNo: form.groupNo,
      groupName: form.groupName || groupInfo.value?.groupName || '',
      // 本次申请额度(后端提交校验必填,§2026-08-25 精简字段时保留:非界面字段,界面集团信息已去授信展示)
      applyAmount: totalAmount > 0 ? totalAmount : undefined,
      idNo: form.idNo,
      ucrCode: form.ucrCode,
    })
  } else {
    // 客户信息人工修正快照(数仓带出后人工调整,新增客户后台拉不出时手工填写;审批详情优先展示)
    payload.customerInfoJson = JSON.stringify({
      customerNo: form.customerNo,
      customerName: form.customerName,
      custType: 'CORP',
      ucrCode: form.ucrCode,
      fiveLevelClass: form.fiveLevelClass,
      creditLevel: form.creditLevel,
      industry: form.industry,
      idType: form.idType,
      idNo: form.idNo,
      occupation: form.occupation,
      phone: form.phone,
      openOrg: form.openOrg,
      openDate: form.openDate,
      basicAccount: form.basicAccount
    })
  }
  return payload
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
      draft.versionNo = created.versionNo ?? 1
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
  // 存款申请无关联人录入,不存在关联人校验(勿从 loan.vue 拷入 missingRel 校验,该变量未定义会抛 ReferenceError)
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
    const firstNode = nodeLabel(result.items?.[0]?.currentNodeCode)
    // 提交成功直接跳回工作台首页(与贷款申请一致,§用户要求);不再停留申请页
    ElMessage.success(`申请 ${result.applicationNo} 已提交,当前节点:${firstNode}`)
    router.push('/overview')
  } catch {
    // 拦截器已提示
  } finally {
    submitting.value = false
  }
}

// ---------- 关联重提(?reapply={applicationId}:生成新草稿并加载内容) ----------
onMounted(async () => {
  // 产品下拉:以产品目录为权威来源(空目录回退字典)
  loadDepositProducts()
  // 开户机构下拉(§用户要求):数据源 ccr_sys_dept 启用机构
  loadOpenOrgOptions()
  // 产品标准上限(生效中;非 admin 可能 403,失败则隐藏)
  try {
    productLimits.value = await listProductLimits('EFFECTIVE')
  } catch {
    productLimits.value = []
  }
  // 关联重提(?reapply={applicationId}:生成新草稿并加载内容)
  const src = route.query.reapply
  if (src) {
    try {
      const newDraft = await reapplyApplication(String(src))
      draft.id = newDraft.id
      draft.versionNo = newDraft.versionNo ?? 1
      draft.applicationNo = newDraft.applicationNo
      await loadDraftIntoForm(newDraft.id)
      ElMessage.success(`已基于原申请 #${src} 生成新草稿 ${newDraft.applicationNo},请调整后提交`)
    } catch {
      // 拦截器已提示
    }
  }
  // ?edit={draftId}:从历史页"继续编辑"加载草稿(草稿重新发起流程;雪花主键超 JS 安全整数,按字符串传递防精度丢失)
  const editId = route.query.edit
  if (editId) {
    draft.id = String(editId)
    try {
      await loadDraftIntoForm(editId)
    } catch {
      // 拦截器已提示
    }
  }
})

async function loadDraftIntoForm(id: number | string) {
  const d = await getApplicationDetail(id)
  const app = d.application
  // 同步数据版本号:保存草稿(PUT)必须携带 versionNo(乐观锁),缺失会导致"保存草稿必须携带数据版本号"报错
  draft.versionNo = app.versionNo != null ? app.versionNo : (draft.versionNo ?? 1)
  form.customerScope = app.customerScope === 'GROUP' ? 'GROUP' : 'CORPORATE'
  form.customerNo = app.customerNo || ''
  form.groupNo = app.groupNo || ''
  form.applicationRemark = app.applicationRemark || ''
  // 客户信息人工快照回填(继续编辑/重提):数仓重查可能缺客户名称等字段,以提交时快照为准
  let custInfo: any = null
  try { custInfo = app.customerInfoJson ? JSON.parse(app.customerInfoJson) : null } catch { custInfo = null }
  if (custInfo?.customerName) form.customerName = custInfo.customerName
  form.industry = custInfo?.industry || ''
  form.basicAccount = custInfo?.basicAccount || ''
  if (form.customerScope === 'GROUP' && form.groupNo) {
    // 集团主体:查询带出集团信息;未收录集团名称从提交快照回填展示
    await queryGroup()
    try {
      const gi = app.groupInfoJson ? JSON.parse(app.groupInfoJson) : null
      if (gi?.groupName && !groupInfo.value?.groupName) {
        if (!groupInfo.value) groupInfo.value = {}
        groupInfo.value.groupName = gi.groupName
        form.groupName = gi.groupName
      }
    } catch { /* 快照解析失败忽略 */ }
  } else if (app.customerNo) {
    await loadCustomerDetail()
  }
  const editable = (d.pricingItems || []).filter((p) => p.inheritFlag !== 'Y')
  items.value = editable.map((p) => {
    const rel = (d.depositRelations || []).find((r) => r.pricingItemId === p.id)
    const row = newItem()
    row.accountMode = rel?.plannedAccountFlag === 'Y' ? 'PLANNED' : 'EXISTING'
    // 明文账号直接还原
    if (row.accountMode === 'EXISTING' && rel?.depositAccountNo) {
      row.depositAccountNo = rel.depositAccountNo
    }
    row.productCode = p.productCode || ''
    row.termValue = p.termValue != null ? String(p.termValue) : ''
    row.termUnit = p.termUnit || ''
    row.termOption = row.termValue && row.termUnit ? `${row.termValue}:${row.termUnit}` : ''
    row.amount = p.pricingAmount != null ? String(p.pricingAmount) : ''
    row.currency = p.currency || 'CNY'
    row.originalRate = p.originalRate != null ? String(p.originalRate) : ''
    row.requestedRate = p.requestedRate != null ? String(p.requestedRate) : ''
    row.calculatedRate = p.calculatedRate != null ? String(p.calculatedRate) : ''
    return row
  })
  if (!items.value.length) items.value = [newItem()]
}
</script>

<style scoped>
/* 开户机构下拉(el-select)与 .form-input 对齐(36px 高度/边框/圆角一致,跟随全局紧凑值) */
.open-org-select.el-select { width: 100%; }
.open-org-select.el-select :deep(.el-select__wrapper) {
  min-height: 36px;
  padding: 0 10px;
  border-radius: var(--radius-sm);
  box-shadow: 0 0 0 1px #dcdfe6 inset;
}
.open-org-select.el-select :deep(.el-select__placeholder) { color: #c0c4cc; }
.section-head { margin-bottom: 10px; }
.section-tip { font-size: 13px; color: var(--color-text-sub); }
/* 表单字段横向布局:label 定宽右对齐 + 输入框同行,输入框左缘整齐对齐 */
.form-field {
  display: grid;
  grid-template-columns: 108px 1fr;
  align-items: center;
  column-gap: 6px;
}
.form-field__label { margin-bottom: 0; font-size: 12px; text-align: right; padding-right: 2px; }
.form-field > .form-input,
.form-field > .form-select,
.form-field > .el-select,
.form-field > .el-autocomplete,
.form-field > div:not(.section-tip):not(.limit-hint) {
  width: 100%;
  min-width: 0;
  grid-column: 2;
}
/* 反查提示/标准上限说明置于输入框下方,与输入框左缘对齐 */
.form-field > .section-tip,
.form-field > .limit-hint { grid-column: 2; }
/* 客户信息区 5 列一行(企业单户/集团均 5 字段,§2026-08-25 用户要求五列紧凑一行):label 上置缩小+控件全宽 */
.form-grid--5 { grid-template-columns: repeat(5, minmax(0, 1fr)); }
.form-grid--5 .form-field { display: block; }
.form-grid--5 .form-field__label { display: block; margin-bottom: 3px; font-size: 12px; text-align: left; padding-right: 0; }
.form-grid--5 .form-field > .form-input,
.form-grid--5 .form-field > .form-select,
.form-grid--5 .form-field > .el-select,
.form-grid--5 .form-field > .el-autocomplete,
.form-grid--5 .form-field > div:not(.section-tip):not(.limit-hint) { grid-column: auto; width: 100%; min-width: 0; }
/* 提交预览申请备注:保持原竖排风格(label 左对齐在上方,文本框全宽) */
.form-field--stack { display: block; }
.form-field--stack .form-field__label { display: block; margin-bottom: 4px; font-size: 13px; text-align: left; padding-right: 0; }
/* 文本框内字体优化:紧凑字号+字体族统一+数字等宽对齐+占位提示可读(原生控件与 Element 控件一致) */
.form-field .form-input,
.form-field .form-select,
.form-field :deep(.el-input__inner),
.form-field :deep(.el-select__selected-item),
.form-field :deep(.el-select__placeholder),
.form-field :deep(.el-textarea__inner) {
  font-size: 13px;
  font-family: inherit;
  font-variant-numeric: tabular-nums;
}
.form-field .form-input::placeholder,
.form-field .form-select::placeholder,
.form-field :deep(.el-input__inner::placeholder),
.form-field :deep(.el-select__placeholder) {
  color: #9ca3af;
}
/* 表单卡与全局 .card 观感一致(去边框+浅投影,紧凑内边距) */
.form-card {
  background: var(--color-surface);
  border: none;
  border-radius: var(--radius);
  padding: 12px 14px;
  margin-bottom: 10px;
  box-shadow: 0 1px 2px rgba(16, 24, 40, 0.05);
}
.form-card__title { font-size: var(--fs-h3); font-weight: 600; margin-bottom: 10px; display: flex; align-items: center; gap: 8px; }
/* 4 列网格:文本框随列收窄;label 同行后列宽紧凑,跨列字段降至 span 2 */
.form-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px 10px; }
.table { border-radius: var(--radius); overflow-x: auto; }
.customer-cands { margin-top: 8px; border: 1px solid var(--color-border); border-radius: var(--radius-sm); overflow-x: auto; background: var(--color-surface); }
.customer-cand { padding: 8px 12px; font-size: 13px; cursor: pointer; border-bottom: 1px solid var(--color-border); }
.customer-cand:last-child { border-bottom: none; }
.customer-cand:hover { background: var(--color-primary-light); }
.req { color: var(--color-danger); }
.sub-title { font-size: 14px; font-weight: 600; margin: 0 0 8px; color: var(--color-text-main); display: flex; align-items: center; gap: 8px; }
.draft-banner { display: flex; align-items: center; gap: 8px; font-size: 13px; color: var(--color-text-sub); }
/* 规则来源提示(§12.4⑦) */
.rule-notice {
  background: var(--color-primary-light); color: var(--color-primary);
  border-left: 3px solid var(--color-primary); border-radius: var(--radius-sm);
  padding: 10px 14px; font-size: 13px; margin-bottom: 16px;
}
/* 产品标准上限提示(§12.4) */
.limit-hint { font-size: 12px; color: var(--color-text-sub); margin-top: 4px; }
.limit-hint--exceed { color: var(--color-danger); font-weight: 600; }
.route-node {
  display: inline-block; padding: 1px 8px; border-radius: 999px;
  background: var(--color-primary-light); color: var(--color-primary);
  font-size: 12px; font-weight: 500;
}
/* 存款分项卡片(复用全局 .mortgage-item/.mortgage-item__head/.mortgage-item__grid) */
.deposit-item { margin-bottom: 10px; }
.deposit-item__title { font-size: 14px; font-weight: 600; }
/* 中间断点:4 列网格降为 2 列(与贷款申请一致) */
@media (max-width: 1100px) {
  .form-grid { grid-template-columns: repeat(2, 1fr); }
}
</style>
