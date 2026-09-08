<template>
  <div>
    <div class="section-head">
      <div class="section-title">决议书查询</div>
      <InfoTip content="仅展示当前有效决议(已签发/执行中等);被否决或被关闭的决议不在此列。可下载对应决议书 PDF。" />
    </div>

    <div class="card">
      <!-- 筛选(2026-09-08:客户名称/客户号(兼集团号)/决议书编号 子串模糊,可组合) -->
      <div class="filter-bar">
        <input class="form-input" v-model="filters.customerName" placeholder="客户/集团名称" aria-label="客户名称" />
        <input class="form-input" v-model="filters.customerNo" placeholder="客户号 / 集团号" aria-label="客户号或集团号" />
        <input class="form-input" v-model="filters.resolutionNo" placeholder="决议书编号" aria-label="决议书编号" />
        <button class="btn btn--primary" @click="onSearch">查询</button>
        <button class="btn btn--secondary" @click="onReset">重置</button>
      </div>

      <table class="table table--full" v-loading="listLoading">
        <thead>
          <tr>
            <th>客户/集团</th><th>客户号</th><th>决议书编号</th><th>签发日期</th><th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in records" :key="row.id">
            <td>{{ row.customerName || '—' }}</td>
            <td>
              <span :title="row.groupNo ? '集团决议(集团号)' : '单户决议(客户号)'">
                {{ row.customerNo || row.groupNo || '—' }}
              </span>
            </td>
            <td>{{ row.resolutionNo }}</td>
            <td>{{ fmtTime(row.issueTime) }}</td>
            <td>
              <button class="btn btn--text" @click="download(row)">下载决议书</button>
            </td>
          </tr>
          <tr v-if="!records.length"><td colspan="5"><div class="empty">{{ listError ? '加载失败，请刷新' : '暂无数据' }}</div></td></tr>
        </tbody>
      </table>

      <div class="pager" v-if="total > 0">
        <el-pagination
          background
          layout="total, prev, pager, next"
          :total="total"
          :page-size="pageSize"
          :current-page="pageNum"
          @current-change="onPage"
        />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted } from 'vue'
import { pageResolutions } from '@/api/resolution'
import { downloadResolutionDoc } from '@/api/history'
import { fmtDateTime } from '@/utils/format'

const records = ref<any[]>([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = 10
const listLoading = ref(false)
const listError = ref(false)

const filters = reactive({ customerName: '', customerNo: '', resolutionNo: '' })

async function load() {
  listLoading.value = true
  listError.value = false
  try {
    const data = await pageResolutions({
      pageNum: pageNum.value,
      pageSize,
      customerName: filters.customerName || undefined,
      customerNo: filters.customerNo || undefined,
      resolutionNo: filters.resolutionNo || undefined
    })
    records.value = data.records || []
    total.value = Number(data.total) || 0
  } catch {
    records.value = []
    total.value = 0
    listError.value = true
  } finally {
    listLoading.value = false
  }
}
function onPage(p: number) {
  pageNum.value = p
  load()
}
function onSearch() {
  pageNum.value = 1
  load()
}
function onReset() {
  filters.customerName = ''
  filters.customerNo = ''
  filters.resolutionNo = ''
  pageNum.value = 1
  load()
}

function fmtTime(t: string) {
  return fmtDateTime(t, false)
}

// 下载决议书 PDF(后端按 applicationId 即时生成;resolution_query 角色走免参与校验链路)
function download(row: any) {
  downloadResolutionDoc(row.applicationId)
}

onMounted(load)
</script>

<style scoped>
.filter-bar { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; flex-wrap: wrap; }
.filter-bar .form-input { width: 180px; }
/* 768px 断点:查询条件单列占满整行 */
@media (max-width: 767px) {
  .filter-bar .form-input { width: 100%; }
}
.pager { display: flex; justify-content: flex-end; margin-top: 16px; }
</style>
