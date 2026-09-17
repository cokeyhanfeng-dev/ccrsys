<template>
  <div>
    <div class="section-head">
      <div class="section-title">决议书查询</div>
      <InfoTip content="仅展示当前有效决议(已签发/执行中等);被否决或被关闭的决议不在此列。可预览或下载对应决议书 PDF。" />
    </div>

    <div class="card">
      <!-- 筛选(2026-09-08:客户名称/客户号(兼集团号)/决议书编号 子串模糊,可组合;
           2026-09-16 增证件号码,精确匹配) -->
      <div class="filter-bar">
        <input class="form-input" v-model="filters.customerName" placeholder="客户/集团名称" aria-label="客户名称" />
        <input class="form-input" v-model="filters.customerNo" placeholder="客户号 / 集团号" aria-label="客户号或集团号" />
        <input class="form-input" v-model="filters.certNo" placeholder="证件号码" aria-label="证件号码" @keyup.enter="onSearch" />
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
              <button class="btn btn--text" @click="previewResolution(row)">预览</button>
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

    <!-- 决议书预览(2026-09-16:与档案页/审批详情附件预览同口径,弹窗内联 PDF,免下载后再打开)
         必须留在根 div 内部:layout 用 <transition mode="out-in"> 包路由组件,本页一旦成为多根节点,
         过渡的 leave 回调不再触发、状态机卡死,离开本页后所有菜单都渲染空白(2026-09-16 修复) -->
    <el-dialog
      v-model="previewOpen"
      :title="previewRow?.resolutionNo ? '决议书预览 · ' + previewRow.resolutionNo : '决议书预览'"
      width="min(860px, 92vw)"
      top="6vh"
      @closed="releasePreview"
    >
      <div v-loading="previewBusy" class="preview-content">
        <div v-if="previewError" role="alert" class="preview-message">
          {{ previewError }}<button class="btn btn--secondary" style="margin-left:10px" @click="retryPreview">重试</button>
        </div>
        <iframe v-else-if="previewUrl" :src="previewUrl" title="决议书 PDF" />
        <div v-else class="preview-message">{{ previewBusy ? '正在读取决议书…' : '' }}</div>
      </div>
      <template #footer>
        <button v-if="previewUrl" class="btn btn--secondary" @click="download(previewRow)">下载</button>
        <button class="btn btn--primary" @click="previewOpen = false">关闭</button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted, onUnmounted } from 'vue'
import axios from 'axios'
import { pageResolutions } from '@/api/resolution'
import { downloadResolutionDoc } from '@/api/history'
import { readToken } from '@/auth/storage.mjs'
import { fmtDateTime } from '@/utils/format'

const records = ref<any[]>([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = 10
const listLoading = ref(false)
const listError = ref(false)

const filters = reactive({ customerName: '', customerNo: '', resolutionNo: '', certNo: '' })

async function load() {
  listLoading.value = true
  listError.value = false
  try {
    const data = await pageResolutions({
      pageNum: pageNum.value,
      pageSize,
      customerName: filters.customerName || undefined,
      customerNo: filters.customerNo || undefined,
      resolutionNo: filters.resolutionNo || undefined,
      certNo: filters.certNo.trim() || undefined
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
  filters.certNo = ''
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

// ---- 决议书预览(2026-09-16:弹窗内联 PDF,免下载后再打开) ----
// 同一个后端下载接口,此处自行取 blob 而非走 download() 封装(后者直接触发保存)。
// token 用 readToken():SSO/code 登录的令牌存 localStorage,裸取 sessionStorage 会 401。
// 决议书 PDF 为「仅打印」加密(打开口令为空),浏览器阅读器可直接渲染,不弹密码框。
const previewOpen = ref(false)
const previewBusy = ref(false)
const previewUrl = ref('')
const previewError = ref('')
const previewRow = ref<any>(null)
// 序号防竞态:连点不同行时,先返回的旧响应不得覆盖新预览(releasePreview 递增使旧响应作废)
let previewSeq = 0

function releasePreview() {
  previewSeq++
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
  previewUrl.value = ''
  previewBusy.value = false
}

async function loadPreview(row: any) {
  if (!row?.applicationId) return
  releasePreview()
  previewError.value = ''
  previewRow.value = row
  previewOpen.value = true
  previewBusy.value = true
  const current = previewSeq
  try {
    const token = readToken()
    const resp = await axios.get(`/api/ccr/approval/history/${row.applicationId}/resolution-doc`, {
      responseType: 'blob',
      timeout: 60000,
      headers: token ? { Authorization: token } : {}
    })
    const blob = resp.data as Blob
    // 后端出错返回 R JSON 包装(与 download 封装同判定)
    if (blob.type.includes('json')) {
      const result = JSON.parse(await blob.text())
      throw new Error(result.msg || '决议书读取失败，请重新登录或重试')
    }
    if (current !== previewSeq || !previewOpen.value) return
    previewUrl.value = URL.createObjectURL(blob)
  } catch (err: any) {
    if (current !== previewSeq || !previewOpen.value) return
    const data = err.response?.data
    if (data instanceof Blob && data.type.includes('json')) {
      try {
        previewError.value = JSON.parse(await data.text()).msg || '决议书读取失败'
      } catch {
        previewError.value = '决议书读取失败，请重试'
      }
    } else {
      previewError.value = err.message || '决议书读取失败，请重试'
    }
  } finally {
    if (current === previewSeq) previewBusy.value = false
  }
}

function previewResolution(row: any) {
  loadPreview(row)
}

async function retryPreview() {
  if (previewRow.value) await loadPreview(previewRow.value)
}

onMounted(load)
// 离开页面时释放 objectURL,防内存泄漏(弹窗若开着未走 @closed)
onUnmounted(releasePreview)
</script>

<style scoped>
.filter-bar { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; flex-wrap: wrap; }
.filter-bar .form-input { width: 180px; }
/* 768px 断点:查询条件单列占满整行 */
@media (max-width: 767px) {
  .filter-bar .form-input { width: 100%; }
}
.pager { display: flex; justify-content: flex-end; margin-top: 16px; }
/* 预览容器(与档案页/审批详情同口径:定高内滚动,PDF 铺满) */
.preview-content { height: 65vh; overflow: auto; background: #f3f5f8; border: 1px solid #e5e7eb; border-radius: 8px; }
.preview-content iframe { width: 100%; height: 100%; border: 0; }
.preview-message { padding: 32px 16px; text-align: center; color: #606266; }
</style>
