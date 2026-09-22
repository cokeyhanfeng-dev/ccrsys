<template>
  <div>
    <div class="section-head"><div class="section-title">消息投递记录</div></div>
    <div class="card">
      <form class="delivery-filters" @submit.prevent="search">
        <el-input v-model="filters.recipient" placeholder="接收人姓名 / 登录名 / ID" clearable aria-label="接收人" />
        <el-select v-model="filters.recipientOrgId" placeholder="接收人当前机构" filterable clearable aria-label="接收人当前机构">
          <el-option v-for="dept in depts" :key="dept.id" :value="dept.id" :label="dept.deptName" />
        </el-select>
        <el-input v-model="filters.keyword" placeholder="消息内容 / 消息标识关键词" clearable aria-label="消息关键词" />
        <el-select v-model="filters.channel" placeholder="全部渠道" clearable aria-label="渠道">
          <el-option v-for="(label, value) in deliveryChannels" :key="value" :value="value" :label="label" />
        </el-select>
        <el-select v-model="filters.sendStatus" placeholder="全部投递状态" clearable aria-label="投递状态">
          <el-option v-for="(label, value) in deliveryStatuses" :key="value" :value="value" :label="label" />
        </el-select>
        <el-select v-model="filters.receiptStatus" placeholder="站内阅读状态" clearable aria-label="站内阅读状态">
          <el-option value="READ" label="站内已读" /><el-option value="UNREAD" label="站内未读" />
        </el-select>
        <el-date-picker v-model="timeRange" type="datetimerange" start-placeholder="生成开始时间"
          end-placeholder="生成结束时间" value-format="YYYY-MM-DDTHH:mm:ss" class="delivery-range" />
        <div class="delivery-actions">
          <button class="btn btn--primary" :disabled="loading">查询</button>
          <button class="btn btn--secondary" type="button" @click="reset">重置</button>
          <button class="btn btn--secondary" type="button" @click="load">刷新</button>
        </div>
      </form>
      <p class="section-tip">发送成功表示站内消息已生成或外部渠道已接受，不等于用户已阅读。阅读状态仅依据站内回执，企业微信等外部渠道无法确认已读。机构按接收人当前归属显示。</p>
      <div class="table-scroll">
        <table class="table table--full">
          <thead><tr><th>接收人</th><th>当前机构</th><th>渠道</th><th>消息内容</th><th>投递状态</th><th>阅读状态</th><th>重试计数</th><th>生成时间</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="row in records" :key="row.id">
              <td>{{ row.recipientName || row.recipientId || '—' }}<div class="section-tip">{{ row.recipientUsername || '' }}</div></td>
              <td>{{ row.recipientOrgName || '—' }}</td>
              <td>{{ deliveryChannels[row.channel] || row.channel || '—' }}</td>
              <td><div class="delivery-summary" :title="row.messageContent">{{ row.messageContent || '—' }}</div></td>
              <td><span :class="['badge', row.sendStatus === 'FAILED' ? 'badge--danger' : row.sendStatus === 'SUCCESS' ? 'badge--success' : 'badge--neutral']">{{ deliveryStatuses[row.sendStatus] || row.sendStatus || '—' }}</span></td>
              <td>{{ deliveryReceipt(row) }}</td><td>{{ row.retryCount ?? 0 }}</td><td>{{ time(row.createTime) }}</td>
              <td><button class="btn btn--text" @click="selected = row">详情</button></td>
            </tr>
            <tr v-if="!records.length"><td colspan="9" class="empty-line">{{ loading ? '正在查询…' : failed ? '查询失败，请重试' : '暂无符合条件的消息记录' }}</td></tr>
          </tbody>
        </table>
      </div>
      <el-pagination :current-page="pageNum" :page-size="20" :total="total"
        layout="total, prev, pager, next" @current-change="changePage" />
    </div>
    <el-drawer :model-value="!!selected" title="消息投递详情" size="min(620px, 95vw)" @close="selected = null">
      <template v-if="selected">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="接收人">{{ selected.recipientName || '—' }}（{{ selected.recipientId || '—' }}）</el-descriptions-item>
          <el-descriptions-item label="当前机构">{{ selected.recipientOrgName || '—' }}</el-descriptions-item>
          <el-descriptions-item label="渠道">{{ deliveryChannels[selected.channel] || selected.channel }}</el-descriptions-item>
          <el-descriptions-item label="投递状态">{{ deliveryStatuses[selected.sendStatus] || selected.sendStatus }}</el-descriptions-item>
          <el-descriptions-item label="阅读状态">{{ deliveryReceipt(selected) }}</el-descriptions-item>
          <el-descriptions-item label="消息标识"><span class="delivery-content">{{ selected.messageKey || '—' }}</span></el-descriptions-item>
          <el-descriptions-item label="消息内容"><span class="delivery-content">{{ selected.messageContent || '—' }}</span></el-descriptions-item>
          <el-descriptions-item label="生成时间">{{ time(selected.createTime) }}</el-descriptions-item>
          <el-descriptions-item label="发送时间">{{ time(selected.sendTime) }}</el-descriptions-item>
          <el-descriptions-item label="站内已读时间">{{ selected.channel === 'SYSTEM' ? time(selected.receiptTime) : '不适用' }}</el-descriptions-item>
          <el-descriptions-item label="重试计数">{{ selected.retryCount ?? 0 }}</el-descriptions-item>
          <el-descriptions-item label="最后失败原因"><span class="delivery-content">{{ selected.errorMessage || '—' }}</span></el-descriptions-item>
        </el-descriptions>
        <p class="section-tip">现有日志保留当前结果和最后失败原因，没有逐次发送明细。企微重试计数包含首次尝试；查看详情不会登记已读。</p>
      </template>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { listManagedNotificationLogs, type ManagedNotificationLog } from '@/api/notification'
import { listDepts, type SysDept } from '@/api/system'
import { deliveryStatuses, deliveryChannels, deliveryReceipt } from '@/utils/notification-delivery.mjs'

const filters = reactive({ recipient: '', recipientOrgId: undefined as number | undefined, keyword: '', channel: '', sendStatus: '', receiptStatus: '' })
const timeRange = ref<string[]>([])
const depts = ref<SysDept[]>([])
const records = ref<ManagedNotificationLog[]>([])
const selected = ref<ManagedNotificationLog | null>(null)
const pageNum = ref(1)
const total = ref(0)
const loading = ref(false)
const failed = ref(false)
let requestId = 0
const time = (value?: string) => value ? value.replace('T', ' ') : '—'
async function load() {
  const id = ++requestId
  loading.value = true
  failed.value = false
  selected.value = null
  try {
    const result = await listManagedNotificationLogs({
      pageNum: pageNum.value, pageSize: 20, recipient: filters.recipient.trim() || undefined,
      recipientOrgId: filters.recipientOrgId || undefined, keyword: filters.keyword.trim() || undefined,
      channel: filters.channel || undefined, sendStatus: filters.sendStatus || undefined,
      receiptStatus: filters.receiptStatus || undefined,
      startTime: timeRange.value?.[0], endTime: timeRange.value?.[1]
    })
    if (id !== requestId) return
    records.value = result.records || []
    total.value = Number(result.total) || 0
  } catch {
    if (id !== requestId) return
    records.value = []
    total.value = 0
    failed.value = true
  } finally { if (id === requestId) loading.value = false }
}
function search() { pageNum.value = 1; load() }
function changePage(value: number) { pageNum.value = value; load() }
function reset() {
  Object.assign(filters, { recipient: '', recipientOrgId: undefined, keyword: '', channel: '', sendStatus: '', receiptStatus: '' })
  timeRange.value = []
  search()
}
onMounted(() => {
  load()
  listDepts().then(value => { depts.value = value }).catch(() => { depts.value = [] })
})
</script>
<style scoped>
.delivery-filters { display: grid; grid-template-columns: repeat(3, minmax(180px, 1fr)); gap: 12px; margin-bottom: 12px; }
.delivery-range { grid-column: span 2; width: 100% !important; }
.delivery-actions { display: flex; gap: 8px; }
.delivery-summary { max-width: 300px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.delivery-content { white-space: pre-wrap; overflow-wrap: anywhere; }
.el-pagination { margin-top: 16px; }
@media (max-width: 900px) { .delivery-filters { grid-template-columns: 1fr; } .delivery-range { grid-column: auto; } }
</style>
