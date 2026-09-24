<template>
  <div>
    <div class="section-head">
      <div class="section-title">纾困调息</div>
      <InfoTip content="特殊资产管理部对已无力偿还的存量贷款客户给予利率优惠、以促成其还款的专用入口。按客户维度申请一个利率（不针对某一笔贷款）：选择客户 → 录入申请利率 → 填写申请说明后提交。申请利率 ≥4.0% 时审批链止于特殊资产部总经理；低于 4.0% 走完整链（分管行长 → 六人小组 → 总行行长）。" />
    </div>

    <!-- ① 选择客户 -->
    <div class="card">
      <div class="card-toolbar">
        <span class="card-toolbar__title">① 选择客户</span>
      </div>
      <div class="form-grid">
        <div class="form-field sa-suggest">
          <label class="form-field__label">客户名称 / 客户号 <span class="req">*</span></label>
          <input
            class="form-input"
            v-model="query"
            placeholder="输入客户名称或客户号联想查询"
            @input="onQueryInput"
            @focus="onQueryInput"
            @blur="closeSuggest"
          />
          <!-- 原生联想下拉:避免依赖组件库注册,风格与全站扁平化一致 -->
          <ul v-if="suggestOpen && suggestions.length" class="sa-suggest__list">
            <li v-for="s in suggestions" :key="s.customerNo" @mousedown.prevent="pickCustomer(s)">
              {{ s.label }}
            </li>
          </ul>
        </div>
        <div class="form-field">
          <label class="form-field__label">客户号</label>
          <div class="sa-static">{{ customerNo || '—' }}</div>
        </div>
        <div class="form-field">
          <label class="form-field__label">证件号码</label>
          <div class="sa-static">{{ basic.certNo || '—' }}</div>
        </div>
        <div class="form-field">
          <label class="form-field__label">企业性质</label>
          <div class="sa-static">
            <span v-if="basic.entpCharic" :class="basic.entpCharic === 'SOE' ? 'badge badge--success' : 'badge badge--neutral'">
              {{ basic.entpCharic === 'SOE' ? '国有企业' : '非国有企业' }}
            </span>
            <span v-else>—</span>
          </div>
        </div>
        <!-- 五级分类:带出数仓 ffthlv_class 作默认值,允许人工改(特资客户分类常需人工核定,
             且数仓目前只推 5 档,「已核销/欠息」只能在此选) -->
        <div class="form-field">
          <label class="form-field__label">五级分类 <span class="req">*</span></label>
          <select class="form-select" v-model="fiveLevelClass">
            <option value="">请选择</option>
            <option v-for="o in FIVE_LEVEL_OPTIONS" :key="o.code" :value="o.code">{{ o.name }}</option>
          </select>
        </div>
        <!-- 违约概率:数仓无此字段,全人工录入 -->
        <div class="form-field">
          <label class="form-field__label">违约概率 <span class="req">*</span></label>
          <select class="form-select" v-model="defaultProb">
            <option value="">请选择</option>
            <option v-for="o in DEFAULT_PROBABILITY_OPTIONS" :key="o.code" :value="o.code">{{ o.name }}</option>
          </select>
        </div>
      </div>
    </div>

    <!-- ② 申请利率(按客户一个利率,不展示其名下贷款分项) -->
    <div class="card">
      <div class="card-toolbar">
        <span class="card-toolbar__title">② 申请利率</span>
      </div>
      <div class="form-grid">
        <div class="form-field">
          <label class="form-field__label">申请利率(%) <span class="req">*</span></label>
          <input class="form-input" v-model="requestedRate" type="number" step="0.0001" min="0" max="36" placeholder="如 4.2000" />
          <div class="section-tip">按客户维度申请，不区分名下具体贷款</div>
        </div>
        <div class="form-field sa-span2">
          <label class="form-field__label">审批链预览</label>
          <div class="sa-route" :class="lowRate ? 'sa-route--long' : 'sa-route--short'">
            <span v-if="!requestedRate" class="section-tip">录入申请利率后显示预计审批链</span>
            <template v-else>
              <span class="sa-route__node" v-for="(n, i) in routeNodes" :key="n">
                <span v-if="i" class="sa-route__arrow">→</span>{{ n }}
              </span>
              <div class="sa-route__hint">
                {{ lowRate
                  ? '申请利率低于 4.0%，需经分管行长、六人小组、总行行长完整审批'
                  : '申请利率不低于 4.0%，审批止于特殊资产部总经理' }}
              </div>
            </template>
          </div>
        </div>
      </div>
    </div>

    <!-- ③ 申请说明 -->
    <div class="card">
      <div class="card-toolbar">
        <span class="card-toolbar__title">③ 申请说明</span>
      </div>
      <div class="form-field">
        <label class="form-field__label">申请详细描述 <span class="req">*</span></label>
        <textarea class="form-input sa-remark" v-model="remark" rows="4" placeholder="请说明客户困难情况、调息依据与本次申请理由"></textarea>
      </div>
    </div>

    <!-- ④ 申请材料附件(2026-09-24 用户要求补入口)
         特资是给已无力偿还的客户做利率优惠,审批人需要看到客户困难情况的佐证材料
         (财务报表/催收记录/还款计划等)才能判断调息依据是否成立。
         上传接口 uploadAttachment 绑「申请 id」,故提交流程改为:建单拿 id → 逐个上传 → 再提交,
         对用户仍是「点一次提交」。上传失败的附件在表内标「待上传」,重试提交时复用同一单、只补传未成功的项。 -->
    <div class="card">
      <div class="card-toolbar">
        <span class="card-toolbar__title">④ 申请材料附件</span>
        <InfoTip content="可上传客户困难情况佐证材料（财务报表、催收记录、还款计划等）。附件随提交一并上传至申请单，审批各环节与申请详情均可查看。附件可选填。" />
      </div>
      <button class="btn btn--secondary" :disabled="submitting" @click="attachmentInput?.click()">＋ 添加附件</button>
      <input ref="attachmentInput" type="file" multiple style="display:none" @change="onAttachmentFiles" />
      <table class="table" v-if="attachments.length" style="margin-top:12px">
        <thead><tr><th>文件名</th><th>大小</th><th>状态</th><th style="width:80px">操作</th></tr></thead>
        <tbody>
          <tr v-for="(a, i) in attachments" :key="`${a.name}-${i}`">
            <td>{{ a.name }}</td>
            <td class="num">{{ fmtSize(a.size) }}</td>
            <td>
              <span class="badge" :class="a.uploaded ? 'badge--success' : 'badge--warning'">{{ a.uploaded ? '已上传' : '待上传' }}</span>
            </td>
            <td><button class="btn btn--text" :disabled="submitting" @click="attachments.splice(i, 1)">移除</button></td>
          </tr>
        </tbody>
      </table>
      <div class="empty-line" v-else>暂无附件</div>
    </div>

    <div class="sa-actions">
      <button class="btn btn--secondary" :disabled="submitting" @click="reset">重置</button>
      <button class="btn btn--primary" :disabled="submitting" @click="handleSubmit">
        {{ submitting ? '提交中…' : '提交审批' }}
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/store/user'
import { createApplication, getCustomerDetail, getCustomerBusinessView, searchCustomers, submitApplication, uploadAttachment, type ApplicationPayload } from '@/api/application'
import { DEFAULT_PROBABILITY_OPTIONS, FIVE_LEVEL_OPTIONS, normalizeFiveLevelClass } from '@/utils/dict'
import { fmtSize } from '@/utils/format'

const router = useRouter()
const userStore = useUserStore()

/** 特资专用产品码(2026-09-24):仅本页写入分项,普通贷款产品下拉不含此码,
 *  路由侧靠它把自己与普通对公贷款区分开,命中特资专用矩阵行(M-SA-EX-*) */
const SA_PRODUCT_CODE = 'LOAN_SA'
/** 高/低利率分档阈值:与特资矩阵行 boundary_min_rate 同值(≥4.0% 止于特殊资产部总经理)。
 *  此处仅用于链预览展示,真实链路以提交时矩阵算出的冻结链为准 */
const HIGH_RATE_THRESHOLD = 4

// ---------- 客户 ----------
const query = ref('')
const suggestions = ref<{ customerNo: string; customerName: string; custType: string; label: string }[]>([])
const suggestOpen = ref(false)
const customerNo = ref('')
const customerName = ref('')
const basic = ref<Record<string, any>>({})
/** 客户分类要素(2026-09-24):五级分类带出数仓值可人工改;违约概率数仓无此字段,全人工选;两项均必填 */
const fiveLevelClass = ref('')
const defaultProb = ref('')

async function onQueryInput() {
  const kw = query.value.trim()
  if (!kw) {
    suggestions.value = []
    return
  }
  try {
    const rows = await searchCustomers(kw)
    suggestions.value = (rows || []).map((r: any) => ({
      customerNo: r.customerNo,
      customerName: r.customerName,
      custType: r.custType,
      label: `${r.customerName} · ${r.custType === 'INDV' ? (r.certNo || r.customerNo) : r.customerNo} · ${r.custType === 'INDV' ? '个人' : '对公'}`
    }))
    suggestOpen.value = true
  } catch {
    suggestions.value = []
  }
}
function closeSuggest() {
  // 延迟关闭:让下拉项的 mousedown 先于 blur 生效
  setTimeout(() => { suggestOpen.value = false }, 120)
}

async function pickCustomer(s: { customerNo: string; customerName: string }) {
  customerNo.value = s.customerNo
  customerName.value = s.customerName
  query.value = s.customerName
  suggestOpen.value = false
  // 换客户:清空上一客户的带出态,防止残留数据串到新客户
  basic.value = {}
  splits.value = []
  fiveLevelClass.value = ''
  defaultProb.value = ''
  // 已建的草稿单属于上一客户(仅附件上传失败时才会存在),不能复用,否则新客户内容会提交进旧单;
  // 已选附件同属上一客户的申请材料,一并清空
  draftId.value = null
  attachments.value = []
  await loadCustomer()
}

/** 带出客户基本信息 + 存量贷款拆分项(仅用于后台推算申请金额与期限,界面不展示) */
async function loadCustomer() {
  try {
    const detail = await getCustomerDetail(customerNo.value)
    basic.value = detail.basic || {}
    // 带出数仓五级分类作默认值:数仓码值(010)与中文(正常)混存,归一化到码值才能在下拉里回显
    fiveLevelClass.value = normalizeFiveLevelClass(basic.value.fiveLevelClass || '')
  } catch {
    // 数仓无该客户记录由拦截器提示
  }
  try {
    const view = await getCustomerBusinessView(customerNo.value)
    splits.value = view.creditSplits || []
  } catch {
    splits.value = []
  }
}

// ---------- 存量贷款数据(界面不展示,仅用于填充后端必填的分项金额/期限) ----------
const splits = ref<any[]>([])

/** 申请金额:取客户存量拆分项合计。特资按客户申请、不涉及金额录入,
 *  但后端分项 pricing_amount 必填且参与「分项合计 ≤ 授信总额」勾稽,故按其存量合计带入 */
const applyAmount = computed(() => {
  const sum = splits.value.reduce((s: number, r: any) => s + (Number(r.splitAmount) || 0), 0)
  return sum > 0 ? String(sum) : '0'
})

/** 申请期限:按存量分项最近到期日就近归到 12/36/60 个月档(后端分项期限必填,界面不展示)。
 *  期限不参与特资审批链定档(矩阵 term_tier 通配),仅满足落库完整性 */
const term = computed<{ value: string; unit: string }>(() => {
  const dates = splits.value.map((r: any) => r.maturityDate).filter(Boolean).sort()
  if (!dates.length) return { value: '1', unit: 'YEAR' }
  const months = Math.round((new Date(dates[0]).getTime() - Date.now()) / (30.44 * 24 * 3600 * 1000))
  const buckets = [12, 36, 60]
  const best = buckets.reduce((a, b) => (Math.abs(b - months) < Math.abs(a - months) ? b : a))
  return { value: String(best / 12), unit: 'YEAR' }
})

// ---------- 申请利率 ----------
const requestedRate = ref('')
const lowRate = computed(() => {
  const v = Number(requestedRate.value)
  return !Number.isNaN(v) && v > 0 && v < HIGH_RATE_THRESHOLD
})
const routeNodes = computed(() => {
  const head = ['客户经理(发起)', '支行行长', '特殊资产部总经理']
  return lowRate.value ? [...head, '分管行长', '六人小组', '总行行长'] : head
})

// ---------- 申请说明 ----------
const remark = ref('')

// ---------- 申请材料附件 ----------
/** 前端暂存待上传附件:上传接口绑申请 id,建单前无 id 可传,故先存文件对象,提交时统一下传 */
const attachments = ref<{ name: string; size: number; file: File; uploaded: boolean }[]>([])
const attachmentInput = ref<HTMLInputElement | null>(null)

function onAttachmentFiles(e: Event) {
  const input = e.target as HTMLInputElement
  for (const f of Array.from(input.files || [])) {
    attachments.value.push({ name: f.name, size: f.size, file: f, uploaded: false })
  }
  // 清空 input 值:否则连续两次选同一文件不触发 change
  input.value = ''
}

/** 提交前把暂存附件逐个上传到刚建好的申请单。
 *  幂等:已上传的跳过,故「上传失败 → 再次点提交」不会产生重复附件(draftId 复用同一单,也不重复建单)。
 *  全部成功才返回 true;任一失败则留在页面上重试,不提交——特资的困难佐证是审批定价依据,缺件上送不合适。 */
async function uploadPendingAttachments(id: string | number): Promise<boolean> {
  const failed: string[] = []
  for (const a of attachments.value) {
    if (a.uploaded) continue
    try {
      await uploadAttachment(id, a.file)
      a.uploaded = true
    } catch {
      failed.push(a.name)
    }
  }
  if (failed.length) {
    ElMessage.error(`附件「${failed.join('、')}」上传失败，申请已保存为草稿，请重试后再提交`)
    return false
  }
  return true
}

// ---------- 提交 ----------
const submitting = ref(false)
/** 草稿单 id:附件上传失败时保留,重试提交复用同一单,避免在库中留下多张重复草稿 */
const draftId = ref<string | number | null>(null)

function reset() {
  query.value = ''
  customerNo.value = ''
  customerName.value = ''
  basic.value = {}
  splits.value = []
  fiveLevelClass.value = ''
  defaultProb.value = ''
  requestedRate.value = ''
  remark.value = ''
  attachments.value = []
  draftId.value = null
  submitting.value = false
}

/** 与后端硬校验同口径的前端先拦,避免走到提交才回滚(整单回滚会留下草稿) */
function validate(): string | null {
  if (!customerNo.value) return '请先选择客户'
  if (!fiveLevelClass.value) return '请选择五级分类'
  if (!defaultProb.value) return '请选择违约概率'
  if (!requestedRate.value) return '请录入申请利率'
  const rate = Number(requestedRate.value)
  if (Number.isNaN(rate) || rate <= 0) return '申请利率须为大于 0 的数字'
  if (rate > 36) return '申请利率不得超过 36%'
  if (!remark.value.trim()) return '请填写申请详细描述'
  return null
}

async function handleSubmit() {
  const err = validate()
  if (err) {
    ElMessage.warning(err)
    return
  }
  submitting.value = true
  try {
    const payload: ApplicationPayload = {
      businessType: 'LOAN',
      customerScope: 'CORPORATE_SINGLE',
      customerNo: customerNo.value,
      groupNo: null,
      // 特资按客户申请一个利率:整单只构造一个分项承载该利率
      // (后端要求至少一个定价分项,checkCompleteness「定价分项不能为空」)
      guarantees: [{
        productCode: SA_PRODUCT_CODE,
        termValue: term.value.value,
        termUnit: term.value.unit,
        amount: applyAmount.value,
        currency: 'CNY',
        // 测算利率:后端 createLoanItems 要求非空才落库分项(草稿宽松跳过),
        // 特资无独立测算环节,以申请利率同值填充
        calculatedRate: requestedRate.value,
        requestedRate: requestedRate.value,
        // 不传 guaranteeType(2026-09-24 用户拍板):特资是面向「特定客户」的纾困调息,不对应某笔贷款,
        // 申请页本就没有担保录入入口——此前为迁就后端必填硬填 MORTGAGE,凭空造出「担保方式:抵押」,
        // 该假值会一路打进审批详情/档案/决议书。后端 CcrApplicationServiceImpl:318 仅在 guaranteeType
        // 非空时才建担保包,传空即不建包(guaranteePackageId 保持 null),无需后端改动。
        // 关键:不绑定存量拆分项(sourceSplitNo)、不填原利率(originalRate)。
        // 特资是按客户申请,不针对具体贷款;不设原利率基准即不受
        // 「存量调息申请利率不得高于原利率」约束,申请利率可自由跨越 4% 分档线
        measures: []
      }],
      applicantUserId: userStore.userInfo?.userId,
      applicantOrgId: userStore.userInfo?.orgId,
      orgId: userStore.userInfo?.orgId,
      applicationRemark: remark.value.trim(),
      customerInfoJson: JSON.stringify({
        customerNo: customerNo.value,
        customerName: customerName.value,
        custType: 'CORP',
        // 企业性质随单提交:决定矩阵 SOE/NON_SOE 分档(后端路由优先用此值,数仓兜底)
        entpCharic: basic.value.entpCharic === 'SOE' ? 'SOE' : 'NON_SOE',
        // 对公证件号取统一社会信用代码:后端 checkCompleteness 要求单户主客户证件号必填
        ucrCode: basic.value.certNo || '',
        // 五级分类:以人工确认为准(选客户时带出数仓值作默认,客户经理可改),7 档含已核销/欠息
        fiveLevelClass: fiveLevelClass.value,
        // 违约概率:数仓无此字段,全人工录入(A/B/C/D/E/逾欠客户)
        defaultProb: defaultProb.value,
        creditLevel: basic.value.creditLevel || '',
        industry: basic.value.industry || '',
        registeredCapital: basic.value.registeredCapital || '',
        openOrg: basic.value.openOrgName || '',
        openDate: basic.value.openDate || '',
        basicAccount: basic.value.basicAccount || ''
      }),
      // 存量口径:后端按 credit_info_json.businessType=EXISTING 判定存量路由,不以分项原利率推断
      creditInfoJson: JSON.stringify({ businessType: 'EXISTING' })
      // 不提交 commitments:特资申请免贡献度承诺(后端 checkCompleteness 已按 LOAN_SA 豁免)
    }
    // 附件上传必须先于提交:接口绑申请 id,故先建单(草稿态)拿 id,传完再提交。
    // 附件上传失败时停在页面(单已落草稿,不丢数据),用户重试复用同一 id 只补传未成功的附件,不会重复建单
    if (!draftId.value) {
      const created = await createApplication(payload)
      draftId.value = created.id
    }
    if (!(await uploadPendingAttachments(draftId.value))) return
    await submitApplication(draftId.value)
    ElMessage.success('纾困调息申请已提交')
    router.push('/overview')
  } catch {
    // 业务错误信息由请求拦截器统一提示
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
/* 客户联想下拉:相对定位容器 + 绝对定位列表,避免撑开表单栅格 */
.sa-suggest { position: relative; }
.sa-suggest__list {
  position: absolute; z-index: 20; left: 0; right: 0; top: 100%;
  margin: 0; padding: 4px 0; list-style: none; max-height: 240px; overflow-y: auto;
  background: var(--color-bg-card, #fff); border: 1px solid var(--color-border, #e5e7eb);
  border-radius: 6px; box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08);
}
.sa-suggest__list li { padding: 8px 12px; font-size: 13px; cursor: pointer; }
.sa-suggest__list li:hover { background: var(--color-bg-hover, #f5f7fa); }

/* 只读静态值:与 form-input 同高,保持表单栅格对齐 */
.sa-static { font-size: 13px; line-height: 36px; color: var(--color-text-main); }

.sa-span2 { grid-column: span 2; }

/* 审批链预览:短链(绿)/长链(橙)一眼区分 */
.sa-route { font-size: 13px; line-height: 1.9; }
.sa-route__node { color: var(--color-text-main); }
.sa-route__arrow { margin: 0 6px; color: var(--color-text-sub, #909399); }
.sa-route__hint { margin-top: 4px; font-size: 12px; }
.sa-route--short .sa-route__hint { color: #16a34a; }
.sa-route--long .sa-route__hint { color: #d97706; }

.sa-remark { resize: vertical; line-height: 1.6; padding: 8px 12px; height: auto; }

.sa-actions { display: flex; justify-content: flex-end; gap: 12px; margin: 16px 0 8px; }

@media (max-width: 900px) {
  .sa-span2 { grid-column: span 1; }
}
</style>
