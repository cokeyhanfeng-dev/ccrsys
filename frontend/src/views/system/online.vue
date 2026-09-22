<template>
  <div>
    <div class="section-head">
      <div class="section-title">在线用户</div>
      <InfoTip content="按有效登录会话统计。同一人员登录电脑和手机会产生多个会话；关闭页面后，会话仍可能有效。" />
    </div>
    <div class="card">
      <div class="filter-bar">
        <el-input v-model="filters.keyword" placeholder="工号 / 姓名" aria-label="工号或姓名" clearable maxlength="64" @keyup.enter="search" />
        <el-select v-model="filters.orgId" placeholder="全部机构" aria-label="机构" clearable filterable>
          <el-option v-for="dept in depts" :key="dept.id" :label="dept.deptName" :value="dept.id" />
        </el-select>
        <el-select v-model="filters.client" placeholder="全部终端" aria-label="终端" clearable>
          <el-option label="电脑端" value="PC" />
          <el-option label="移动端" value="MOBILE" />
        </el-select>
        <button class="btn btn--primary" :disabled="loading" @click="search">查询</button>
        <button class="btn btn--secondary" :disabled="loading" @click="reset">重置</button>
        <button class="btn btn--secondary" :disabled="loading" @click="load">刷新</button>
      </div>
      <p v-if="deptError" class="section-tip">机构选项加载失败，<button class="btn btn--text" @click="loadDepts">重试</button></p>
      <div class="online-summary" aria-live="polite">
        <span>符合条件的在线人数：<b>{{ failed ? '—' : userCount }}</b></span>
        <span>有效会话数：<b>{{ failed ? '—' : total }}</b></span>
        <span class="section-tip">查询时间：{{ fmtTime(queriedAt) }}</span>
      </div>
      <div class="online-table" v-loading="loading">
        <table class="table table--full">
          <thead><tr><th>工号</th><th>姓名</th><th>机构</th><th>主角色</th><th>终端</th><th>登录时间</th><th>最近访问时间</th><th>登录 IP</th></tr></thead>
          <tbody>
            <tr v-for="(row, index) in records" :key="index">
              <td>{{ row.username }}</td><td>{{ row.nickName }}</td><td>{{ row.orgName || '—' }}</td><td>{{ row.roleName || '—' }}</td>
              <td>{{ row.client === 'MOBILE' ? '移动端' : '电脑端' }}</td>
              <td>{{ fmtTime(row.loginTime) }}</td><td>{{ fmtTime(row.lastAccessTime) }}</td><td>{{ row.loginIp || '—' }}</td>
            </tr>
            <tr v-if="!records.length"><td colspan="8"><div class="empty">{{ failed ? '加载失败，请点击刷新重试' : loading ? '正在查询…' : '暂无符合条件的在线会话' }}</div></td></tr>
          </tbody>
        </table>
      </div>
      <div class="pager" v-if="total > 0">
        <el-pagination background layout="total, prev, pager, next" :total="total" :page-size="pageSize" :current-page="pageNum" @current-change="page" />
      </div>
      <p class="section-tip">最近访问包含页面自动请求，不能据此判断人员是否正在操作。功能上线前建立的会话可能缺少登录时间或 IP，将显示“—”；重新登录后补齐。</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { listOnlineUsers, type OnlineSession } from '@/api/online'
import { listDepts, type SysDept } from '@/api/system'
import { fmtDateTime } from '@/utils/format'

const filters = reactive({ keyword: '', orgId: undefined as number | undefined, client: '' })
const depts = ref<SysDept[]>([])
const deptError = ref(false)
const records = ref<OnlineSession[]>([])
const pageNum = ref(1)
const pageSize = 20
const total = ref(0)
const userCount = ref(0)
const queriedAt = ref('')
const loading = ref(false)
const failed = ref(false)
let requestNo = 0
function fmtTime(value?: string) { return value ? fmtDateTime(value) : '—' }

async function load() {
  const turn = ++requestNo
  loading.value = true
  failed.value = false
  try {
    const data = await listOnlineUsers({ pageNum: pageNum.value, pageSize,
      keyword: filters.keyword.trim() || undefined, orgId: filters.orgId || undefined, client: filters.client || undefined })
    if (turn !== requestNo) return
    // 会话退出后总页数可能缩小，退回最后一页。
    const lastPage = Math.max(1, Math.ceil(data.total / pageSize))
    if (pageNum.value > lastPage) { pageNum.value = lastPage; return await load() }
    records.value = data.records
    total.value = data.total
    userCount.value = data.userCount
    queriedAt.value = data.queriedAt
  } catch {
    if (turn !== requestNo) return
    records.value = []; total.value = 0; userCount.value = 0; queriedAt.value = ''; failed.value = true
  } finally {
    if (turn === requestNo) loading.value = false
  }
}
function search() { pageNum.value = 1; load() }
function reset() { filters.keyword = ''; filters.orgId = undefined; filters.client = ''; search() }
function page(value: number) { pageNum.value = value; load() }
async function loadDepts() {
  try { depts.value = await listDepts(); deptError.value = false }
  catch { deptError.value = true }
}
onMounted(() => { load(); loadDepts() })
</script>

<style scoped>
.filter-bar, .online-summary { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; margin-bottom: 16px; }
.filter-bar .el-input, .filter-bar .el-select { width: 190px; }
.online-summary b { color: var(--color-primary); }
.online-table { overflow-x: auto; }
.online-table td, .online-table th { white-space: nowrap; }
.pager { display: flex; justify-content: flex-end; margin: 16px 0; }
</style>
