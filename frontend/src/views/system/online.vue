<template>
  <div>
    <div class="section-head">
      <div class="section-title">在线用户</div>
      <InfoTip content="每人一行，展开可查看各次有效登录会话。关闭页面后，会话仍可能有效。" />
    </div>
    <div class="card">
      <div class="filter-bar">
        <el-input v-model="filters.keyword" placeholder="工号 / 姓名" aria-label="工号或姓名" clearable maxlength="64" @keyup.enter="search" />
        <el-select v-model="filters.orgId" placeholder="全部机构" aria-label="机构" clearable filterable>
          <el-option v-for="dept in depts" :key="dept.id" :label="dept.deptName" :value="dept.id" />
        </el-select>
        <el-select v-model="filters.client" placeholder="全部终端" aria-label="终端" clearable>
          <el-option label="全部终端" value="" />
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
        <span>有效会话数：<b>{{ failed ? '—' : sessionCount }}</b></span>
        <span class="section-tip">查询时间：{{ fmtTime(queriedAt) }}</span>
      </div>
      <div class="online-summary">
        <span class="section-tip">按姓名和机构统计全部终端：</span>
        <button class="btn btn--secondary" :disabled="loading || failed" @click="filterClient('PC')">
          电脑端 {{ failed ? '—' : terminalCounts.pcUsers }} 人 / {{ failed ? '—' : terminalCounts.pcSessions }} 会话
        </button>
        <button class="btn btn--secondary" :disabled="loading || failed" @click="filterClient('MOBILE')">
          移动端 {{ failed ? '—' : terminalCounts.mobileUsers }} 人 / {{ failed ? '—' : terminalCounts.mobileSessions }} 会话
        </button>
      </div>
      <div class="online-table" v-loading="loading">
        <table class="table table--full">
          <thead><tr><th>工号</th><th>姓名</th><th>机构</th><th>主角色</th><th>在线终端</th><th>最近登录时间</th><th>最近访问时间</th><th>有效会话</th><th>操作</th></tr></thead>
          <tbody>
            <template v-for="row in records" :key="row.userId">
              <tr>
                <td>{{ row.username }}</td><td>{{ row.nickName }}</td><td>{{ row.orgName || '—' }}</td><td>{{ row.roleName || '—' }}</td>
                <td>{{ row.clients.map(clientName).join('、') }}</td>
                <td>{{ fmtTime(row.lastLoginTime) }}</td><td>{{ fmtTime(row.lastAccessTime) }}</td>
                <td><button class="btn btn--text" :aria-expanded="expanded.has(row.userId)" @click="toggle(row.userId)">
                  {{ row.sessionCount }} 次 · {{ expanded.has(row.userId) ? '收起' : '展开' }}
                </button></td>
                <td><button class="btn btn--text"
                  :disabled="loading || !!kickingUserId || String(userStore.userInfo?.userId) === row.userId"
                  @click="kickout(row)">{{ kickingUserId === row.userId ? '处理中…' : '强制下线' }}</button></td>
              </tr>
              <tr v-if="expanded.has(row.userId)">
                <td colspan="9">
                  <table class="table table--full">
                    <thead><tr><th>终端</th><th>登录时间</th><th>最近访问时间</th><th>登录 IP</th></tr></thead>
                    <tbody><tr v-for="(entry, index) in row.sessions" :key="index">
                      <td>{{ clientName(entry.client) }}</td><td>{{ fmtTime(entry.loginTime) }}</td>
                      <td>{{ fmtTime(entry.lastAccessTime) }}</td><td>{{ entry.loginIp || '—' }}</td>
                    </tr></tbody>
                  </table>
                </td>
              </tr>
            </template>
            <tr v-if="!records.length"><td colspan="9"><div class="empty">{{ failed ? '加载失败，请点击刷新重试' : loading ? '正在查询…' : '暂无符合条件的在线会话' }}</div></td></tr>
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
import { listOnlineUsers, kickoutOnlineUser, type OnlineUser } from '@/api/online'
import { listDepts, type SysDept } from '@/api/system'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@/store/user'
import { fmtDateTime } from '@/utils/format'

const userStore = useUserStore()
const kickingUserId = ref('')
async function kickout(row: OnlineUser) {
  if (kickingUserId.value) return
  kickingUserId.value = row.userId
  try {
    try {
      await ElMessageBox.confirm(
        `确认将账号 ${row.username}（${row.nickName}）强制下线？将注销全部电脑端和移动端会话，包括当前筛选未显示的会话。用户需重新认证，未保存的内容可能丢失。`,
        '强制下线', { type: 'warning', confirmButtonText: '确认全部下线', cancelButtonText: '取消' })
    } catch { return }
    try {
      await kickoutOnlineUser(row.userId)
      ElMessage.success('该账号的全部现有会话已下线')
    } catch {
      // 请求层已提示；结果不确定时也刷新清单，不自动重发下线请求。
    }
    await load()
  } finally { kickingUserId.value = '' }
}
const filters = reactive({ keyword: '', orgId: undefined as number | undefined, client: '' })
const depts = ref<SysDept[]>([])
const deptError = ref(false)
const records = ref<OnlineUser[]>([])
const pageNum = ref(1)
const pageSize = 20
const total = ref(0)
const userCount = ref(0)
const sessionCount = ref(0)
const terminalCounts = reactive({ pcUsers: 0, mobileUsers: 0, pcSessions: 0, mobileSessions: 0 })
const expanded = ref(new Set<string>())
function clientName(client: string) { return client === 'MOBILE' ? '移动端' : '电脑端' }
function toggle(userId: string) {
  if (expanded.value.has(userId)) expanded.value.delete(userId)
  else expanded.value.add(userId)
}
function filterClient(client: string) { filters.client = client; search() }
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
    sessionCount.value = data.sessionCount
    Object.assign(terminalCounts, { pcUsers: data.pcUserCount, mobileUsers: data.mobileUserCount,
      pcSessions: data.pcSessionCount, mobileSessions: data.mobileSessionCount })
    expanded.value = new Set([...expanded.value].filter(id => data.records.some(row => row.userId === id)))
    queriedAt.value = data.queriedAt
  } catch {
    if (turn !== requestNo) return
    records.value = []; total.value = 0; userCount.value = 0; sessionCount.value = 0
    Object.assign(terminalCounts, { pcUsers: 0, mobileUsers: 0, pcSessions: 0, mobileSessions: 0 })
    expanded.value.clear(); queriedAt.value = ''; failed.value = true
  } finally {
    if (turn === requestNo) loading.value = false
  }
}
function search() { pageNum.value = 1; expanded.value.clear(); load() }
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
