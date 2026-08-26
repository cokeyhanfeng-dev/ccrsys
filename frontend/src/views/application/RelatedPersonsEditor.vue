<template>
  <div class="relations-editor">
    <div class="sub-title">
      关联人员
      <span class="badge badge--neutral">随申请备注结构附带提交</span>
      <InfoTip>
        录入与本笔业务相关的配偶/直系亲属/担保人等;姓名行内自动匹配客户号,匹配不到可手工补录。
        证件号(对公 USCC/对私身份证)必填,失焦全行判重并<strong>录入即绑定</strong>:已绑定其他客户/集团将标红阻断。
      </InfoTip>
    </div>

    <!-- 已绑定关联人历史(申请已建档时展示,§11.2 application/{id}/relations) -->
    <div class="history-block" v-if="history.length">
      <div class="sub-title" style="font-size:13px">已绑定关联人(历史,同一证件号复用幂等)</div>
      <table class="table table--sm">
        <thead><tr><th>姓名</th><th>证件号</th><th>关系</th><th>绑定对象</th><th>绑定时间</th></tr></thead>
        <tbody>
          <tr v-for="(h, i) in history" :key="i">
            <td>{{ h.relationName || h.certNo || '—' }}</td>
            <td>{{ h.certNo || '—' }}</td>
            <td>{{ relationTypeText(h.relationType) }}</td>
            <td>{{ h.groupNo ? `集团 ${h.groupNo}` : (h.customerNo || '—') }}</td>
            <td>{{ h.bindTime ? String(h.bindTime).replace('T', ' ').slice(0, 16) : '—' }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <table class="table" v-if="rows.length">
      <thead>
        <tr>
          <th>姓名 <span class="req">*</span></th>
          <th>证件类型</th>
          <th>证件号 <span class="req">*</span></th>
          <th>关系类型</th>
          <th>客户号(自动匹配)</th>
          <th>判重状态</th>
          <th>操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="(r, i) in rows" :key="i">
          <td>
            <input class="form-input" v-model="r.name" placeholder="输入姓名后自动匹配" @blur="matchCustomer(r)" />
          </td>
          <td>
            <select class="form-select" v-model="r.certType" style="min-width:120px" @change="onCertChange(r)">
              <option value="USCC">统一社会信用代码(对公)</option>
              <option value="ID_CARD">居民身份证(对私)</option>
            </select>
          </td>
          <td><input class="form-input" v-model="r.certNo" placeholder="必填,失焦判重" @input="onCertChange(r)" @blur="onCertBlur(r, i)" /></td>
          <td>
            <select class="form-select" v-model="r.relationType">
              <option value="SPOUSE">配偶</option>
              <option value="DIRECT_RELATIVE">直系亲属</option>
              <option value="GUARANTOR">担保人</option>
              <option value="RELATED_COMPANY">关联企业</option>
              <option value="OTHER">其他</option>
            </select>
          </td>
          <td><input class="form-input" v-model="r.customerNo" placeholder="自动匹配/手工补录" /></td>
          <td>
            <span v-if="r.checkStatus === 'bound'" class="rel-badge rel-badge--ok">已绑定</span>
            <span v-else-if="r.checkStatus === 'available'" class="rel-badge rel-badge--ok">可绑定</span>
            <span v-else-if="r.checkStatus === 'occupied'" class="rel-badge rel-badge--bad" :title="r.occupiedBy || '已占用'">{{ r.occupiedBy || '已占用' }}</span>
            <span v-else-if="r.checkStatus === 'checking'" class="rel-badge rel-badge--warn">校验中…</span>
            <span v-else-if="r.checkStatus === 'error'" class="rel-badge rel-badge--bad">校验失败</span>
            <span v-else class="rel-badge rel-badge--muted">未校验</span>
          </td>
          <td><button class="btn btn--text" @click="removeRow(i)">删除</button></td>
        </tr>
      </tbody>
    </table>
    <div class="empty" v-else style="padding:8px">暂未录入关联人员</div>
    <button class="btn btn--secondary" style="margin-top:8px" @click="addRow">＋ 添加关联人员</button>
  </div>
</template>

<script lang="ts">
/** 关联人员行(后端无独立接收字段,序列化后随申请备注附带;UI 判重态不参与序列化) */
export interface RelatedPersonRow {
  name: string
  /** 证件类型:USCC 统一社会信用代码(对公) / ID_CARD 居民身份证(对私) */
  certType: string
  certNo: string
  relationType: string
  customerNo: string
  /** 判重绑定态(仅前端 UI,不随备注序列化) */
  checkStatus?: 'idle' | 'checking' | 'available' | 'bound' | 'occupied' | 'error'
  /** 占用说明(已绑定其他客户/集团时的提示) */
  occupiedBy?: string
}

const RELATION_TEXT: Record<string, string> = {
  SPOUSE: '配偶', DIRECT_RELATIVE: '直系亲属', GUARANTOR: '担保人',
  RELATED_COMPANY: '关联企业', OTHER: '其他'
}

const CERT_TYPE_TEXT: Record<string, string> = {
  USCC: '统一社会信用代码(对公)', ID_CARD: '居民身份证(对私)'
}

/**
 * 序列化为申请备注附带结构:【关联人员】姓名/证件类型:证件号/关系/客户号;...
 * 证件号缺省沿用旧格式「姓名/-/关系/客户号」,旧草稿与后端字符串存储均兼容,无需后端改动。
 */
export function serializeRelations(rows: RelatedPersonRow[]): string {
  const valid = (rows || []).filter((r) => r.name && r.name.trim())
  if (!valid.length) return ''
  const text = valid
    .map((r) => {
      const cert = r.certNo?.trim()
      const certSeg = cert ? `${r.certType || 'ID_CARD'}:${cert}` : '-'
      return `${r.name.trim()}/${certSeg}/${RELATION_TEXT[r.relationType] || r.relationType}/${r.customerNo || '-'}`
    })
    .join(';')
  return `\n【关联人员】${text}`
}

/** 提交前校验(D5):已填写姓名的关联人必须补全证件号;返回缺失名单(空数组=通过) */
export function validateRelations(rows: RelatedPersonRow[]): string[] {
  return (rows || [])
    .filter((r) => r.name && r.name.trim() && !r.certNo?.trim())
    .map((r) => r.name.trim())
}

/** 提交前阻断:已绑定其他客户/集团(判重占用)的关联人,返回占用名单(空数组=通过,§6.2 前后端双重拦截) */
export function occupiedRelations(rows: RelatedPersonRow[]): { name: string; by: string }[] {
  return (rows || [])
    .filter((r) => r.checkStatus === 'occupied')
    .map((r) => ({ name: r.name?.trim() || r.certNo, by: r.occupiedBy || '已绑定其他客户/集团' }))
}

const RELATION_CODE: Record<string, string> = {
  配偶: 'SPOUSE', 直系亲属: 'DIRECT_RELATIVE', 担保人: 'GUARANTOR',
  关联企业: 'RELATED_COMPANY', 其他: 'OTHER'
}

/** 解析证件段:「类型:号码」或旧格式纯号码;无前缀默认 ID_CARD */
function splitCert(seg: string): { certType: string; certNo: string } {
  if (!seg || seg === '-') return { certType: 'ID_CARD', certNo: '' }
  const ci = seg.indexOf(':')
  if (ci > 0) {
    const prefix = seg.slice(0, ci)
    if (CERT_TYPE_TEXT[prefix]) return { certType: prefix, certNo: seg.slice(ci + 1) }
  }
  return { certType: 'ID_CARD', certNo: seg }
}

/** 从申请备注解析【关联人员】块(草稿/重提回显),返回 [关联人员行, 去除块后的备注] */
export function parseRelations(remark?: string): [RelatedPersonRow[], string] {
  const text = remark || ''
  const match = text.match(/【关联人员】([^\n]*)/)
  if (!match) return [[], text]
  const rows: RelatedPersonRow[] = match[1]
    .split(';')
    .map((seg) => seg.trim())
    .filter(Boolean)
    .map((seg) => {
      const [name = '', cert = '-', rel = '其他', customerNo = '-'] = seg.split('/')
      const { certType, certNo } = splitCert(cert)
      return {
        name,
        certType,
        certNo,
        relationType: RELATION_CODE[rel] || 'OTHER',
        customerNo: customerNo === '-' ? '' : customerNo
      }
    })
  const cleaned = text.replace(/\n?【关联人员】[^\n]*/, '').trim()
  return [rows, cleaned]
}
</script>

<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { searchCustomers } from '@/api/application'
import { checkRelation, bindRelation, listApplicationRelations } from '@/api/relation'
import { relationTypeText } from '@/utils/dict'

const props = withDefaults(defineProps<{
  /** 申请主客户号(绑定对象,§10.3.21) */
  customerNo?: string
  /** 集团号(集团场景绑定对象) */
  groupNo?: string
  /** 申请 id(建档后提供,用于 bind 自动解析绑定对象与历史展示) */
  applicationId?: number
}>(), { customerNo: '', groupNo: '', applicationId: undefined })

const rows = defineModel<RelatedPersonRow[]>({ required: true })
const history = ref<any[]>([])

function addRow() {
  rows.value.push({ name: '', certType: 'ID_CARD', certNo: '', relationType: 'SPOUSE', customerNo: '' })
}
function removeRow(i: number) {
  rows.value.splice(i, 1)
}

function mark(r: RelatedPersonRow, status: RelatedPersonRow['checkStatus'], by = '') {
  r.checkStatus = status
  r.occupiedBy = by
}

// 证件号/证件类型变更:重置判重态(旧判重结果失效)
function onCertChange(r: RelatedPersonRow) {
  if (r.checkStatus && r.checkStatus !== 'checking') {
    r.checkStatus = undefined
    r.occupiedBy = ''
  }
}

// 证件号失焦:全行判重 + 录入即绑定(§6.2/§10.3.21,前后端双重拦截)
async function onCertBlur(r: RelatedPersonRow, index: number) {
  const cert = r.certNo?.trim()
  if (!cert) { mark(r, 'idle'); return }
  // 本单列表内重复(不同行同一证件号)
  const dup = rows.value.find((x, j) => j !== index && x.certNo?.trim() === cert && x.name?.trim())
  if (dup) {
    mark(r, 'occupied', `本单重复:${dup.name}`)
    ElMessage.warning(`关联人「${dup.name}」证件号与当前行重复,同一证件号不应出现在两条记录`)
    return
  }
  mark(r, 'checking')
  try {
    const res = await checkRelation(r.certType, cert)
    if (res.bound) {
      // 已绑定:先给出占用提示,再走 bind 幂等/冲突权威判定(同目标复用,其他目标后端拒绝)
      mark(r, 'occupied', res.boundGroupNo ? `已绑定集团 ${res.boundGroupNo}` : `已绑定客户 ${res.boundCustomerNo}`)
    }
    await doBind(r)
  } catch {
    mark(r, 'error')
  }
}

// 绑定(录入即绑定留痕):后端仅按本人草稿 applicationId 解析绑定对象
async function doBind(r: RelatedPersonRow) {
  if (!props.applicationId) {
    mark(r, 'available') // 尚未确定绑定对象(未选客户),仅判重可绑定
    return
  }
  try {
    const body: Record<string, unknown> = {
      certType: r.certType,
      certNo: r.certNo,
      relationName: r.name?.trim(),
      relationType: r.relationType,
      applicationId: props.applicationId,
    }
    await bindRelation(body as any)
    mark(r, 'bound')
  } catch (e: any) {
    const msg = e?.msg || e?.message || ''
    if (/已绑定|禁止重复|冲突/.test(msg)) {
      mark(r, 'occupied', msg)
      ElMessage.warning(msg)
    } else {
      mark(r, 'error')
    }
  }
}

// 行内自动匹配客户号:姓名精确匹配数仓客户
async function matchCustomer(r: RelatedPersonRow) {
  const name = r.name?.trim()
  if (!name || r.customerNo) return
  try {
    const cands = await searchCustomers(name)
    const exact = (cands || []).find((c: any) => c.customerName === name)
    if (exact) {
      r.customerNo = exact.customerNo
    } else if (cands?.length) {
      ElMessage.info(`关联人「${name}」未精确匹配到客户号,可手工补录`)
    }
  } catch {
    // 匹配失败不阻断录入
  }
}

// 已绑定关联人历史(§11.2):申请建档后按申请反查,辅助录入/回显
async function loadHistory() {
  if (!props.applicationId) { history.value = []; return }
  try {
    history.value = (await listApplicationRelations(props.applicationId)) || []
  } catch {
    history.value = []
  }
}
watch(() => props.applicationId, loadHistory)
onMounted(loadHistory)
</script>

<style scoped>
.sub-title { font-size: 14px; font-weight: 600; margin: 0 0 8px; color: var(--color-text-main); display: flex; align-items: center; gap: 8px; }
.table { border-radius: var(--radius); overflow-x: auto; }
.req { color: var(--color-danger); }
.empty { text-align: center; color: var(--color-text-light); }
.history-block { background: var(--color-bg); border-radius: 6px; padding: 10px 12px; margin-bottom: 12px; }
.table--sm th, .table--sm td { font-size: 12px; padding: 6px 8px; }
.rel-badge { display: inline-block; font-size: 12px; border-radius: 4px; padding: 2px 6px; white-space: nowrap; }
.rel-badge--ok { background: var(--color-success-light, #f0fdf4); color: var(--color-success); }
.rel-badge--bad { background: var(--color-danger-light, #fef2f2); color: var(--color-danger); }
.rel-badge--warn { background: var(--color-warning-light, #fef3c7); color: var(--color-warning); }
.rel-badge--muted { background: var(--color-bg); color: var(--color-text-light); }
</style>
