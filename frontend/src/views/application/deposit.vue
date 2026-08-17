<template>
  <div>
    <div class="section-head">
      <div class="section-title">存款利率提升申请</div>
      <InfoTip content="存款申请必须经过支行行长节点;贷款控制最低利率、存款控制最高利率,比较方向相反(存款越高越优惠)。" />
    </div>

    <!-- 规则来源提示(§12.4⑦) -->
    <div class="rule-notice">
      规则来源:本页审批路径、权限矩阵与产品利率上限依据《关于调整经营性贷款利率审批流程的议案》配置;规则调整经规则版本发布生效后自动适用于新申请。
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
        <InfoTip content="客户基本信息由数仓统一提供,按客户姓名模糊查询带出后只读展示。" />
      </div>
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
        <div class="form-field">
          <label class="form-field__label">客户性质</label>
          <input class="form-input" :value="customerNatureText" readonly placeholder="选客户后自动判定" />
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
            <input class="form-input" v-model="form.ucrCode" placeholder="数仓带出,可修改" />
          </div>
          <div class="form-field">
            <label class="form-field__label">五级分类</label>
            <input class="form-input" v-model="form.fiveLevelClass" placeholder="数仓带出,可修改" />
          </div>
          <div class="form-field">
            <label class="form-field__label">内部信用等级</label>
            <input class="form-input" v-model="form.creditLevel" placeholder="数仓带出,可修改" />
          </div>
          <div class="form-field">
            <label class="form-field__label">基本户账户</label>
            <input class="form-input" v-model="form.basicAccount" placeholder="请输入基本户账号,可空" />
          </div>
        </template>
        <!-- 对私字段(数仓带出,只读) -->
        <template v-else>
          <div class="form-field">
            <label class="form-field__label">证件类型</label>
            <input class="form-input" :value="certTypeText(form.idType)" placeholder="数仓带出" readonly />
          </div>
          <div class="form-field">
            <label class="form-field__label">证件号码</label>
            <input class="form-input" v-model="form.idNo" placeholder="数仓带出,可修改" />
          </div>
          <div class="form-field">
            <label class="form-field__label">职业</label>
            <input class="form-input" v-model="form.occupation" placeholder="数仓带出,可修改" />
          </div>
          <div class="form-field">
            <label class="form-field__label">联系电话</label>
            <input class="form-input" v-model="form.phone" placeholder="数仓带出,可修改" />
          </div>
        </template>
        <div class="form-field">
          <label class="form-field__label">开户机构</label>
          <input class="form-input" v-model="form.openOrg" placeholder="数仓带出,可修改" />
        </div>
        <div class="form-field">
          <label class="form-field__label">开户日期</label>
          <input class="form-input" v-model="form.openDate" placeholder="数仓带出,可修改" />
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
        <span class="badge badge--warning">存款利率越高越优惠</span>
        <InfoTip>每条分项按“产品/期限/金额/申请利率/账号”结构化提交;存量调价输入存款账号后自动反查数仓,命中即带出产品/期限/当前执行利率,未命中可手工完善;未开户业务选拟开户方案。</InfoTip>
      </div>
      <!-- 每分项一张卡片(字段带标签分行分块,避免多列横向滚动导致关键字段看不见;提交结构不变) -->
      <div v-for="(d, i) in items" :key="i" class="mortgage-item deposit-item">
        <div class="mortgage-item__head">
          <span class="deposit-item__title">存款分项 {{ i + 1 }}</span>
          <button class="btn btn--text" @click="items.splice(i, 1)" v-if="items.length > 1">删除</button>
        </div>
        <!-- 账户方式 + 存款账户(存量反查/拟开户) -->
        <div class="mortgage-item__grid">
          <div class="form-field">
            <label class="form-field__label">账户方式 <span class="req">*</span></label>
            <select class="form-select" v-model="d.accountMode" @change="onAccountModeChange(d)">
              <option value="EXISTING">存量账户调价</option>
              <option value="PLANNED">拟开户方案</option>
            </select>
          </div>
          <div class="form-field" style="grid-column: span 3">
            <label class="form-field__label">存款账户</label>
            <template v-if="d.accountMode === 'EXISTING'">
              <input class="form-input" v-model="d.depositAccountNo" placeholder="输入存款账号,自动查询数仓" @blur="onAccountLookup(d)" />
              <div v-if="d.lookupFound" class="section-tip" style="color:var(--color-success);margin-top:4px">
                数仓已匹配:余额 {{ d.accountBalance ?? '-' }} 万 · 当前利率 {{ d.originalRate || '-' }}% · 开户 {{ d.openDate || '-' }} · 到期 {{ d.maturityDate || '-' }}
              </div>
              <div v-else-if="d.lookupDone" class="section-tip" style="color:var(--color-warning);margin-top:4px">
                数仓未找到该账户,请手工完善产品/期限/原利率
              </div>
            </template>
            <span v-else class="badge badge--neutral">拟开户(未开户业务)</span>
          </div>
        </div>
        <!-- 产品/期限/金额/币种 -->
        <div class="mortgage-item__grid" style="margin-top:10px">
          <div class="form-field">
            <label class="form-field__label">产品 <span class="req">*</span></label>
            <select class="form-select" v-model="d.productCode">
              <option value="" disabled>选择产品</option>
              <option v-for="p in depositProducts" :key="p.code" :value="p.code">{{ p.name }}</option>
            </select>
          </div>
          <div class="form-field">
            <label class="form-field__label">期限 <span class="req">*</span></label>
            <div style="display:flex;gap:4px">
              <input class="form-input form-input--amount" v-model="d.termValue" placeholder="数值" style="flex:1" />
              <select class="form-select" v-model="d.termUnit" style="width:76px">
                <option value="DAY">天</option><option value="MONTH">月</option><option value="YEAR">年</option>
              </select>
            </div>
          </div>
          <div class="form-field">
            <label class="form-field__label">金额(万元) <span class="req">*</span></label>
            <input class="form-input form-input--amount" v-model="d.amount" />
          </div>
          <div class="form-field">
            <label class="form-field__label">币种</label>
            <select class="form-select" v-model="d.currency">
              <option v-for="c in currencies" :key="c" :value="c">{{ c }}</option>
            </select>
          </div>
        </div>
        <!-- 利率:当前执行 + 申请(含标准上限对比) -->
        <div class="mortgage-item__grid" style="margin-top:10px">
          <div class="form-field">
            <label class="form-field__label">当前执行利率(%)</label>
            <input class="form-input form-input--amount" v-model="d.originalRate" disabled placeholder="账户带出" />
          </div>
          <div class="form-field" style="grid-column: span 3">
            <label class="form-field__label">申请利率(%) <span class="req">*</span></label>
            <input class="form-input form-input--amount" v-model="d.requestedRate" placeholder="高于当前执行利率" />
            <!-- 产品标准上限与较上限 BP(取产品边界配置;无权限/无配置则隐藏) -->
            <div v-if="limitOf(d.productCode)" class="limit-hint" :class="{ 'limit-hint--exceed': exceedOf(d) }">
              标准上限 {{ limitOf(d.productCode)!.hardBoundaryRate }}%
              <template v-if="bpOf(d) != null"> · 较上限 {{ bpOf(d)! > 0 ? '+' : '' }}{{ bpOf(d) }} BP</template>
            </div>
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
  lookupDepositAccount,
  createApplication,
  saveApplication,
  getApplicationDetail,
  routePreview,
  submitCheck,
  submitApplication,
  reapplyApplication,
  type ApplicationPayload,
  type RoutePreview,
  type SubmitCheck
} from '@/api/application'
import SubmitCheckDialog from './SubmitCheckDialog.vue'
import ContributionPanel from '@/components/ContributionPanel.vue'
import { listProductLimits } from '@/api/approval2'
import { listEnabledProducts } from '@/api/system'
import { nodeLabel, rateDirectionText, productName, DEPOSIT_PRODUCTS, certTypeText } from '@/utils/dict'

const userStore = useUserStore()
const route = useRoute()

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
  termValue: string
  termUnit: string
  amount: string
  currency: string
  originalRate: string
  requestedRate: string
}
function newItem(): DepositItemRow {
  return {
    accountMode: 'EXISTING', depositAccountNo: '',
    accountBalance: null, openDate: '', maturityDate: '', accountStatus: '',
    lookupDone: false, lookupFound: false,
    productCode: '', termValue: '', termUnit: 'MONTH',
    amount: '', currency: 'CNY', originalRate: '', requestedRate: ''
  }
}

const form = reactive({
  customerScope: 'CORPORATE',
  customerName: '',
  customerNo: '',
  customerNature: '', // 客户性质由数仓 customerClass 自动判定(存量/新增),不允许手选
  customerType: 'NON_SOE',
  // 对公(数仓带出,只读)
  ucrCode: '', fiveLevelClass: '', creditLevel: '', basicAccount: '',
  // 对私(数仓带出,只读)
  idType: '', idNo: '', occupation: '', phone: '',
  openOrg: '', openDate: '',
  applicationRemark: ''
})
const items = ref<DepositItemRow[]>([newItem()])
// 关联人员(§12.4④,后端无独立接收字段,序列化后随申请备注附带)
const contribution = ref<any[]>([])
// 产品标准上限(生效中的存款硬边界;非 admin 可能 403,失败则隐藏)
const productLimits = ref<any[]>([])

function limitOf(productCode: string) {
  if (!productCode) return null
  return productLimits.value.find((l) => l.productCode === productCode && l.businessType === 'DEPOSIT') || null
}
// 较上限 BP:1% = 100BP,存款越高越优惠,超过上限为正向风险提示
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

const applyOrgText = computed(() => userStore.userInfo?.orgName || (userStore.userInfo?.orgId ? `机构 #${userStore.userInfo.orgId}` : '暂无数据'))

// 客户性质只读展示(§用户要求):由数仓 customerClass 自动判定,不允许手选;未选客户显示占位
const customerNatureText = computed(() => {
  if (form.customerNature === 'EXISTING') return '存量客户'
  if (form.customerNature === 'NEW') return '新增客户'
  return '—'
})

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
    cb((rows || []).map(r => ({
      value: `${r.customerName} · ${r.customerNo} · ${r.custType === 'CORP' ? '对公' : '个人'}`,
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
  form.customerScope = c.custType === 'INDV' ? 'INDIVIDUAL' : 'CORPORATE'
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
    form.basicAccount = basic.basicAccount || form.basicAccount
    form.idType = basic.certType || ''
    form.idNo = basic.certNo || ''
    form.occupation = basic.occupation || ''
    form.phone = basic.phone || ''
    form.customerNature = basic.customerClass === 'EXISTING' ? 'EXISTING' : 'NEW'
    // 企业性质带出(数仓 entp_charic 仅 SOE 判国企,其余非国企,与后端 resolveCustomerType 同口径)
    form.customerType = basic.entpCharic === 'SOE' ? 'SOE' : 'NON_SOE'
    contribution.value = detail.contribution || []
  } catch {
    // 数仓无该客户记录(新增客户手工填写)按新户判定;其余错误由拦截器提示
    form.customerNature = 'NEW'
  }
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
  if (isBlank(form.customerNo)) return '请先查询并选择客户'
  if (!items.value.length) return '请至少录入一条存款分项'
  for (let i = 0; i < items.value.length; i++) {
    const d = items.value[i]
    if (d.accountMode === 'EXISTING' && isBlank(d.depositAccountNo)) return `第 ${i + 1} 条存款分项为存量调价,请录入存款账号`
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
    // 关联人员随备注结构附带(后端申请单无独立接收字段,§12.4④)
    applicationRemark: (form.applicationRemark || '').trim() || undefined,
    // 客户信息人工修正快照(数仓带出后人工调整,新增客户后台拉不出时手工填写;审批详情优先展示)
    customerInfoJson: JSON.stringify({
      customerNo: form.customerNo,
      customerName: form.customerName,
      custType: form.customerScope === 'INDIVIDUAL' ? 'INDV' : 'CORP',
      ucrCode: form.ucrCode,
      fiveLevelClass: form.fiveLevelClass,
      creditLevel: form.creditLevel,
      idType: form.idType,
      idNo: form.idNo,
      occupation: form.occupation,
      phone: form.phone,
      openOrg: form.openOrg,
      openDate: form.openDate,
      basicAccount: form.basicAccount
    })
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
    const finalNode = nodeLabel(result.items?.[0]?.routeCode)
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
  // 产品下拉:以产品目录为权威来源(空目录回退字典)
  loadDepositProducts()
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
  form.customerScope = app.customerScope === 'INDIVIDUAL' ? 'INDIVIDUAL' : 'CORPORATE'
  form.customerNo = app.customerNo || ''
  form.applicationRemark = app.applicationRemark || ''
  // 客户信息人工快照回填(继续编辑/重提):数仓重查可能缺客户名称等字段,以提交时快照为准
  let custInfo: any = null
  try { custInfo = app.customerInfoJson ? JSON.parse(app.customerInfoJson) : null } catch { custInfo = null }
  if (custInfo?.customerName) form.customerName = custInfo.customerName
  form.basicAccount = custInfo?.basicAccount || ''
  if (app.customerNo) {
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
.section-title { font-size: var(--fs-h1); font-weight: 700; }
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
.deposit-item { margin-bottom: 14px; }
.deposit-item__title { font-size: 14px; font-weight: 600; }
</style>
