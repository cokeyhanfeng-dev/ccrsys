<template>
  <div v-if="resolution?.files?.length" class="resolution-files">
    <button v-for="file in resolution.files" :key="file.fileId" class="btn btn--secondary"
      type="button" @click="show(file.fileId)">预览 · {{ file.fileName }}</button>
    <span class="form-hint">可拖动预览窗口，边看决议边填写</span>
  </div>
  <el-dialog v-model="open" title="授信决议 · 填写参考" width="min(720px, 92vw)" top="8vh"
    style="margin-right:24px" :modal="false" modal-penetrable draggable :lock-scroll="false"
    append-to-body destroy-on-close>
    <div class="reference-toolbar">
      <select v-model="selected" class="form-select" aria-label="选择决议附件" @change="load">
        <option v-for="file in resolution?.files" :key="file.fileId" :value="file.fileId">{{ file.fileName }}</option>
      </select>
      <a v-if="url" class="btn btn--secondary" :href="url" :download="fileName">下载</a>
    </div>
    <p class="form-hint">{{ resolution?.resolutionNo }} · {{ resolution?.customerName }}。可拖动标题栏调整位置。</p>
    <div v-loading="busy" class="reference-content">
      <div v-if="error" role="alert" class="reference-message">
        {{ error }} <button class="btn btn--secondary" @click="load">重试</button>
      </div>
      <iframe v-else-if="url && mime === 'application/pdf'" :src="url" title="授信决议 PDF" />
      <img v-else-if="url && ['image/png', 'image/jpeg'].includes(mime)" :src="url" :alt="fileName" />
      <div v-else-if="url" class="reference-message">此格式暂不支持在线预览，请点击“下载”后查看。</div>
      <div v-else class="reference-message">{{ busy ? '正在读取决议附件…' : '请选择附件' }}</div>
    </div>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import axios from 'axios'
import type { ExternalCreditResolution } from '@/api/application'

const props = defineProps<{
  resolution?: ExternalCreditResolution
  prepare: () => Promise<string | number | null>
}>()
const open = ref(false)
const selected = ref('')
const url = ref('')
const mime = ref('')
const error = ref('')
const busy = ref(false)
let sequence = 0
let controller: AbortController | undefined
const fileName = computed(() => props.resolution?.files.find(f => f.fileId === selected.value)?.fileName || '授信决议附件')

function release() {
  sequence++
  controller?.abort()
  if (url.value) URL.revokeObjectURL(url.value)
  url.value = ''
  busy.value = false
}
async function show(id: string) {
  selected.value = id
  open.value = true
  await load()
}
async function load() {
  release()
  error.value = ''
  const current = sequence
  const resolutionId = props.resolution?.resolutionId
  const fileId = selected.value
  if (!resolutionId || !fileId) return
  busy.value = true
  try {
    // 保存当前客户上下文后，服务端按草稿归属校验预览权限。
    const applicationId = await props.prepare()
    if (current !== sequence || !open.value) return
    if (!applicationId) throw new Error('请先完成客户基本信息并保存草稿，再预览附件')
    controller = new AbortController()
    const token = sessionStorage.getItem('ccr_token')
    const response = await axios.get(`/api/ccr/external-credit-resolutions/applications/${encodeURIComponent(applicationId)}/files/${encodeURIComponent(fileId)}/preview`, {
      params: { resolutionId }, responseType: 'blob', timeout: 60000,
      signal: controller.signal, headers: token ? { Authorization: token } : {},
    })
    const blob = response.data as Blob
    if (blob.type.includes('json')) {
      const result = JSON.parse(await blob.text())
      throw new Error(result.msg || '附件读取失败，请重新登录或重试')
    }
    if (current !== sequence || !open.value) return
    mime.value = blob.type.split(';')[0]
    url.value = URL.createObjectURL(blob)
  } catch (err: any) {
    if (current !== sequence || !open.value) return
    const data = err.response?.data
    if (data instanceof Blob && data.type.includes('json')) {
      try {
        const message = JSON.parse(await data.text()).msg || '附件读取失败'
        if (current === sequence && open.value) error.value = message
      } catch { if (current === sequence && open.value) error.value = '附件读取失败' }
    } else error.value = err.message || '附件读取失败，请重试'
  } finally {
    if (current === sequence) busy.value = false
  }
}
watch(open, value => { if (!value) release() })
watch(() => props.resolution, () => { open.value = false; release() })
onBeforeUnmount(release)
</script>

<style scoped>
.resolution-files { display:flex; flex-wrap:wrap; align-items:center; gap:8px; margin-top:12px; }
.resolution-files button { max-width:100%; white-space:normal; overflow-wrap:anywhere; text-align:left; }
.reference-toolbar { display:flex; align-items:center; gap:10px; }
.reference-toolbar select { min-width:0; flex:1; }
.reference-content { height:65vh; overflow:auto; background:#f3f5f8; border:1px solid #e5e7eb; border-radius:8px; }
.reference-content iframe { width:100%; height:100%; border:0; }
.reference-content img { display:block; max-width:100%; height:auto; margin:0 auto; }
.reference-message { padding:32px 16px; text-align:center; color:#606266; }
</style>
