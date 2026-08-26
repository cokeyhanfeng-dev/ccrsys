<template>
  <div>
    <div class="section-head">
      <div class="section-title">缓存配置</div>
      <InfoTip>
        Redis 缓存项定义管理:缓存项可<strong>动态增删改</strong>(精确 key / key 前缀、
        TTL、写入开关、描述),并可配置<strong>数据加载器</strong>把数仓等数据手动/定时写入 Redis。
        内置项(lpr/matrix/rate-limit)由业务代码写入,不可删除、不可改 key;修改后<strong>立即生效不重启</strong>。
      </InfoTip>
    </div>

    <div class="card">
      <div class="card__head" style="justify-content:flex-start;gap:8px">
        <button class="btn btn--primary" @click="openCreate">新增缓存项</button>
        <button class="btn btn--secondary" @click="load">刷新</button>
        <InfoTip style="margin-left:auto">
          TTL 修改后自下一次缓存写入生效;Redis 不可用时自动降级直查库,不影响业务。
          "刷新数据"调用所选数据加载器把最新数据写入该缓存项(带 loader 的项须为精确 key 类型)。
        </InfoTip>
      </div>
      <table class="table table--full">
        <thead>
          <tr>
            <th>缓存项</th>
            <th>匹配范围</th>
            <th>描述 / 数据加载器</th>
            <th>写入开关</th>
            <th>TTL(秒)</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="c in items" :key="c.itemKey">
            <td>
              <div>{{ itemName(c.itemKey) }}</div>
              <div class="sub">{{ c.itemKey }}</div>
              <span :class="c.builtin ? 'badge badge--info' : 'badge badge--success'">
                {{ c.builtin ? '内置' : '自定义' }}
              </span>
            </td>
            <td><code class="key-code">{{ c.key || c.keyPattern }}</code></td>
            <td>
              <div>{{ c.description || '-' }}</div>
              <div class="sub">{{ c.dataLoader ? `${c.dataLoader}${loaderName(c.dataLoader) ? ' · ' + loaderName(c.dataLoader) : ''}` : '业务代码写入' }}</div>
            </td>
            <td>
              <el-switch
                :model-value="c.enabled"
                :loading="saving === c.itemKey"
                :disabled="!!saving"
                @change="(v: any) => toggleEnabled(c, v)"
              />
            </td>
            <td>
              <div class="ttl-cell">
                <el-input-number
                  v-model="c.editingTtl"
                  :min="1"
                  :disabled="!c.enabled || !!saving"
                  size="small"
                  :controls="false"
                  style="width: 100px"
                />
                <span class="unit">秒</span>
              </div>
            </td>
            <td>
              <button
                class="btn btn--primary btn--sm"
                :disabled="!c.enabled || !!saving"
                @click="applyTtl(c)"
              >
                应用 TTL
              </button>
              <button
                v-if="c.dataLoader"
                class="btn btn--secondary btn--sm"
                :disabled="!!saving"
                @click="doRefresh(c)"
              >
                刷新数据
              </button>
              <button
                class="btn btn--secondary btn--sm"
                :disabled="!!saving"
                @click="openEdit(c)"
              >
                编辑
              </button>
              <button
                class="btn btn--danger btn--sm"
                :disabled="c.builtin || !!saving"
                :title="c.builtin ? '内置项不可删除' : ''"
                @click="doDelete(c)"
              >
                删除
              </button>
            </td>
          </tr>
          <tr v-if="!items.length"><td colspan="6" class="empty-cell">暂无数据</td></tr>
        </tbody>
      </table>
    </div>

    <!-- 新增/编辑弹窗 -->
    <el-dialog v-model="dialogVisible" :title="editingKey ? '编辑缓存项' : '新增缓存项'" width="560px">
      <el-form label-width="120px">
        <el-form-item label="缓存项编码" required>
          <el-input v-model="form.itemKey" :disabled="!!editingKey" placeholder="如 dw-table:contribution" />
        </el-form-item>
        <el-form-item label="匹配方式" required>
          <el-radio-group v-model="form.type" @change="onTypeChange" :disabled="!!editingKey">
            <el-radio-button value="key">精确 key</el-radio-button>
            <el-radio-button value="pattern">key 前缀</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="form.type === 'key'" label="精确 key" required>
          <el-input v-model="form.cacheKey" :disabled="editingKey ? isBuiltin : false" placeholder="如 ccr:cfg:contribution:latest" />
        </el-form-item>
        <el-form-item v-else label="key 前缀" required>
          <el-input v-model="form.keyPattern" :disabled="editingKey ? isBuiltin : false" placeholder="如 ccr:cfg:rate-limit:" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" placeholder="缓存内容说明" />
        </el-form-item>
        <el-form-item label="写入开关">
          <el-switch v-model="form.enabled" />
        </el-form-item>
        <el-form-item label="TTL(秒)">
          <el-input-number v-model="form.ttlSeconds" :min="1" :controls="false" style="width: 160px" />
        </el-form-item>
        <el-form-item label="数据加载器">
          <el-select v-model="form.dataLoader" clearable placeholder="空=业务代码写缓存" style="width: 100%">
            <el-option v-for="l in loaders" :key="l.code" :label="l.name" :value="l.code" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.dataLoader" label="加载器参数">
          <el-input
            v-model="form.loaderParam"
            type="textarea"
            :rows="3"
            placeholder='如 {"table":"dw_contribution_metric","limit":5000}'
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <button class="btn btn--secondary" @click="dialogVisible = false">取消</button>
        <button class="btn btn--primary" :disabled="saving" @click="save">保存</button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listCacheConfigs,
  createCacheConfig,
  updateCacheConfig,
  deleteCacheConfig,
  refreshCacheConfig,
  listCacheLoaders,
  type CacheConfigItem,
  type CacheLoaderInfo
} from '@/api/system'

const items = ref<(CacheConfigItem & { editingTtl?: number })[]>([])
const loaders = ref<CacheLoaderInfo[]>([])
const saving = ref('')

interface FormState {
  itemKey: string
  type: 'key' | 'pattern'
  cacheKey: string
  keyPattern: string
  description: string
  enabled: boolean
  ttlSeconds?: number
  dataLoader: string
  loaderParam: string
}

const dialogVisible = ref(false)
const editingKey = ref('')
const form = reactive<FormState>({
  itemKey: '',
  type: 'key',
  cacheKey: '',
  keyPattern: '',
  description: '',
  enabled: true,
  ttlSeconds: undefined,
  dataLoader: '',
  loaderParam: ''
})
const isBuiltin = computed(() => editingKey.value ? items.value.find(i => i.itemKey === editingKey.value)?.builtin : false)

function itemName(code: string) {
  const map: Record<string, string> = {
    'lpr-effective': 'LPR 当前生效版本',
    'matrix-effective': '利率矩阵生效行',
    'rate-limit': '产品硬边界限流'
  }
  return map[code] || code
}

function loaderName(code: string) {
  return loaders.value.find(l => l.code === code)?.name
}

async function load() {
  try {
    const data = await listCacheConfigs()
    items.value = data.map((i) => ({ ...i, editingTtl: i.ttlSeconds ?? 300 }))
  } catch {
    items.value = []
  }
}

async function loadLoaders() {
  try {
    loaders.value = await listCacheLoaders()
  } catch {
    loaders.value = []
  }
}

async function toggleEnabled(c: CacheConfigItem, v: boolean) {
  saving.value = c.itemKey
  try {
    await updateCacheConfig(c.itemKey, { enabled: v })
    c.enabled = v
    ElMessage.success(`已${v ? '开启' : '关闭'}写入 ${itemName(c.itemKey)}`)
  } catch {
    // 失败保持原值(el-switch 受控,由 model-value 回弹)
  } finally {
    saving.value = ''
  }
}

async function applyTtl(c: CacheConfigItem) {
  const ttl = Number(c.editingTtl)
  if (!ttl || ttl <= 0) {
    ElMessage.warning('TTL 必须大于 0 秒')
    return
  }
  saving.value = c.itemKey
  try {
    await updateCacheConfig(c.itemKey, { ttlSeconds: ttl })
    c.ttlSeconds = ttl
    ElMessage.success(`TTL 已更新为 ${ttl} 秒,立即生效`)
  } catch {
    c.editingTtl = c.ttlSeconds ?? 300
  } finally {
    saving.value = ''
  }
}

function resetForm() {
  form.itemKey = ''
  form.type = 'key'
  form.cacheKey = ''
  form.keyPattern = ''
  form.description = ''
  form.enabled = true
  form.ttlSeconds = undefined
  form.dataLoader = ''
  form.loaderParam = ''
}

function openCreate() {
  editingKey.value = ''
  resetForm()
  dialogVisible.value = true
}

function openEdit(c: CacheConfigItem) {
  editingKey.value = c.itemKey
  form.itemKey = c.itemKey
  form.type = c.key ? 'key' : 'pattern'
  form.cacheKey = c.key || ''
  form.keyPattern = c.keyPattern || ''
  form.description = c.description || ''
  form.enabled = c.enabled
  form.ttlSeconds = c.ttlSeconds
  form.dataLoader = c.dataLoader || ''
  form.loaderParam = c.loaderParam || ''
  dialogVisible.value = true
}

function onTypeChange() {
  if (form.type === 'key') form.keyPattern = ''
  else form.cacheKey = ''
}

async function save() {
  if (!form.itemKey.trim()) {
    ElMessage.warning('请填写缓存项编码')
    return
  }
  if (editingKey.value) {
    // 编辑:传当前表单值(null 字段省略,后端视为不改);内置项 key 只读不传
    const payload: Record<string, unknown> = {
      description: form.description,
      dataLoader: form.dataLoader,
      loaderParam: form.loaderParam,
      ttlSeconds: form.ttlSeconds
    }
    if (!isBuiltin.value) {
      if (form.type === 'key') payload.cacheKey = form.cacheKey
      else payload.keyPattern = form.keyPattern
    }
    saving.value = editingKey.value
    try {
      await updateCacheConfig(editingKey.value, payload)
      ElMessage.success('缓存项已更新')
      dialogVisible.value = false
      await load()
    } finally {
      saving.value = ''
    }
  } else {
    const payload: Record<string, unknown> = {
      itemKey: form.itemKey.trim(),
      enabled: form.enabled,
      description: form.description,
      dataLoader: form.dataLoader,
      loaderParam: form.loaderParam,
      ttlSeconds: form.ttlSeconds
    }
    if (form.type === 'key') payload.cacheKey = form.cacheKey
    else payload.keyPattern = form.keyPattern
    if (!payload.cacheKey && !payload.keyPattern) {
      ElMessage.warning('请填写精确 key 或 key 前缀')
      return
    }
    saving.value = 'create'
    try {
      await createCacheConfig(payload)
      ElMessage.success('缓存项已创建')
      dialogVisible.value = false
      await load()
    } finally {
      saving.value = ''
    }
  }
}

async function doRefresh(c: CacheConfigItem) {
  saving.value = c.itemKey
  try {
    const r = await refreshCacheConfig(c.itemKey)
    ElMessage.success(`已刷新 ${itemName(c.itemKey)},写入 ${r.count} 条`)
  } catch {
    // 拦截器已提示
  } finally {
    saving.value = ''
  }
}

async function doDelete(c: CacheConfigItem) {
  if (c.builtin) return
  try {
    await ElMessageBox.confirm(
      `确认删除缓存项「${itemName(c.itemKey)}」?删除后该 key 将不受配置管理。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  saving.value = c.itemKey
  try {
    await deleteCacheConfig(c.itemKey)
    ElMessage.success('缓存项已删除')
    await load()
  } catch {
    // 拦截器已提示
  } finally {
    saving.value = ''
  }
}

onMounted(() => {
  load()
  loadLoaders()
})
</script>

<style scoped>
.sub { font-size: 12px; color: var(--color-text-light); }
.key-code {
  background: var(--color-bg, #f8fafc);
  border: 1px solid var(--color-border);
  border-radius: 4px;
  padding: 2px 6px;
  font-size: 12px;
}
.ttl-cell { display: flex; gap: 6px; align-items: center; }
.unit { color: var(--color-text-sub); font-size: 13px; }
.btn--sm { padding: 4px 10px; font-size: 13px; margin-right: 4px; }
.btn--danger { padding: 4px 10px; font-size: 13px; }
</style>
