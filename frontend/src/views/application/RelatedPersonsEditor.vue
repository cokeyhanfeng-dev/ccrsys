<template>
  <div class="relations-editor">
    <div class="sub-title">
      关联人员(§12.4④)
      <span class="badge badge--neutral">随申请备注结构附带提交</span>
    </div>
    <div class="section-tip" style="margin-bottom:8px">
      录入与本笔业务相关的配偶/直系亲属/担保人等;姓名行内自动匹配客户号,匹配不到可手工补录。
      后端申请单暂无独立接收字段,提交时以「【关联人员】」结构附带在申请备注中。
    </div>
    <table class="table" v-if="rows.length">
      <thead>
        <tr>
          <th>姓名 <span class="req">*</span></th>
          <th>证件类型</th>
          <th>证件号 <span class="req">*</span></th>
          <th>关系类型</th>
          <th>客户号(自动匹配)</th>
          <th>操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="(r, i) in rows" :key="i">
          <td>
            <input class="form-input" v-model="r.name" placeholder="输入姓名后自动匹配" @blur="matchCustomer(r)" />
          </td>
          <td>
            <select class="form-select" v-model="r.certType" style="min-width:120px">
              <option value="USCC">统一社会信用代码(对公)</option>
              <option value="ID_CARD">居民身份证(对私)</option>
            </select>
          </td>
          <td><input class="form-input" v-model="r.certNo" placeholder="必填" @blur="onCertBlur(r, i)" /></td>
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
          <td><button class="btn btn--text" @click="rows.splice(i, 1)">删除</button></td>
        </tr>
      </tbody>
    </table>
    <div class="empty" v-else style="padding:8px">暂未录入关联人员</div>
    <button class="btn btn--secondary" style="margin-top:8px" @click="addRow">＋ 添加关联人员</button>
  </div>
</template>

<script lang="ts">
/** 关联人员行(后端无独立接收字段,序列化后随申请备注附带) */
export interface RelatedPersonRow {
  name: string
  /** 证件类型:USCC 统一社会信用代码(对公) / ID_CARD 居民身份证(对私) */
  certType: string
  certNo: string
  relationType: string
  customerNo: string
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
import { ElMessage } from 'element-plus'
import { searchCustomers } from '@/api/application'

const rows = defineModel<RelatedPersonRow[]>({ required: true })

function addRow() {
  rows.value.push({ name: '', certType: 'ID_CARD', certNo: '', relationType: 'SPOUSE', customerNo: '' })
}

// 证件号失焦本地判重(/relation/check 后端未就绪,先用列表内判重占位;对公 USCC 与对私证件号各自比较)
function onCertBlur(r: RelatedPersonRow, index: number) {
  const cert = r.certNo?.trim()
  const name = r.name?.trim()
  if (!cert || !name) return
  const dup = rows.value.find((x, j) => j !== index && x.name?.trim() && x.certNo?.trim() === cert)
  if (dup) {
    ElMessage.warning(`关联人「${dup.name}」证件号与当前行重复,请核对(同一证件号不应出现在两条记录)`)
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
</script>

<style scoped>
.sub-title { font-size: 14px; font-weight: 600; margin: 0 0 8px; color: var(--color-text-main); display: flex; align-items: center; gap: 8px; }
.section-tip { font-size: 13px; color: var(--color-text-sub); }
.table { border-radius: var(--radius); overflow: hidden; }
.req { color: var(--color-danger); }
.empty { text-align: center; color: var(--color-text-light); }
</style>
