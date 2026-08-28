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
      <!-- 工具区统一 card-toolbar:操作在左,说明提示归右侧 actions -->
      <div class="card-toolbar">
        <button class="btn btn--primary" @click="openCreate">新增缓存项</button>
        <button class="btn btn--secondary" @click="load">刷新</button>
        <div class="card-toolbar__actions">
          <InfoTip>
            TTL 修改后自下一次缓存写入生效;Redis 不可用时自动降级直查库,不影响业务。
            "刷新数据"调用所选数据加载器把最新数据写入该缓存项(带 loader 的项须为精确 key 类型)。
          </InfoTip>
        </div>
      </div>
      <!-- 表格横滚容器:窄屏横向滚动,避免列被挤压 -->
      <div class="table-scroll">
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
              <!-- §UI审查:未知编码名称兜底不再重复显示两个相同编码,名称与 key 相同时只显一行 -->
              <div v-if="itemName(c.itemKey) !== c.itemKey">{{ itemName(c.itemKey) }}</div>
              <div class="sub">{{ c.itemKey }}</div>
              <span :class="c.builtin ? 'badge badge--info' : 'badge badge--success'">
                {{ c.builtin ? '内置' : '自定义' }}
              </span>
            </td>
            <td><code class="key-code">{{ c.key || c.keyPattern }}</code></td>
            <td>
              <!-- §UI审查:描述缺省 ASCII `-` 统一为全角 `—` -->
              <div>{{ c.description || '—' }}</div>
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
              <!-- §UI审查:TTL 单元格去掉「秒」单位,与表头「TTL(秒)」不重复 -->
              <div class="ttl-cell">
                <el-input-number
                  v-model="c.editingTtl"
                  :min="1"
                  :disabled="!c.enabled || !!saving"
                  size="small"
                  :controls="false"
                  style="width: 100px"
                />
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
    </div>

    <!-- 新增/编辑弹窗 (§UI审查:el-dialog/el-form 统一为自研 .modal/.form-field 体系,与页内按钮一致) -->
    <div class="modal" v-if="dialogVisible">
      <div class="modal__card">
        <div class="modal__title">{{ editingKey ? '编辑缓存项' : '新增缓存项' }}</div>
        <div class="modal__body">
          <!-- 长表单分组:基本信息 / 写入策略 / 数据加载 -->
          <div class="form-group-title">基本信息</div>
          <div class="form-field">
            <label class="form-field__label" for="cc-itemKey">缓存项编码 <span class="req">*</span></label>
            <!-- 编辑态编码禁改,用只读值展示;新增态保留输入框 -->
            <div v-if="editingKey" class="form-static">{{ form.itemKey }}</div>
            <input v-else id="cc-itemKey" class="form-input" v-model="form.itemKey" />
            <div v-if="!editingKey" class="form-hint">如 dw-table:contribution</div>
          </div>
          <div class="form-field">
            <label class="form-field__label" for="cc-type">匹配方式 <span class="req">*</span></label>
            <div v-if="editingKey" class="form-static">{{ form.type === 'key' ? '精确 key' : 'key 前缀' }}</div>
            <select v-else id="cc-type" class="form-select" v-model="form.type" @change="onTypeChange">
              <option value="key">精确 key</option>
              <option value="pattern">key 前缀</option>
            </select>
          </div>
          <div class="form-field" v-if="form.type === 'key'">
            <label class="form-field__label" for="cc-cacheKey">精确 key <span class="req">*</span></label>
            <!-- 内置项 key 由业务代码写入,编辑时只读展示 -->
            <div v-if="editingKey && isBuiltin" class="form-static">{{ form.cacheKey }}</div>
            <input v-else id="cc-cacheKey" class="form-input" v-model="form.cacheKey" />
            <div v-if="!editingKey || !isBuiltin" class="form-hint">如 ccr:cfg:contribution:latest</div>
          </div>
          <div class="form-field" v-else>
            <label class="form-field__label" for="cc-keyPattern">key 前缀 <span class="req">*</span></label>
            <div v-if="editingKey && isBuiltin" class="form-static">{{ form.keyPattern }}</div>
            <input v-else id="cc-keyPattern" class="form-input" v-model="form.keyPattern" />
            <div v-if="!editingKey || !isBuiltin" class="form-hint">如 ccr:cfg:rate-limit:</div>
          </div>
          <div class="form-field">
            <label class="form-field__label" for="cc-desc">描述</label>
            <input id="cc-desc" class="form-input" v-model="form.description" />
            <div class="form-hint">缓存内容说明</div>
          </div>

          <div class="form-group-title">写入策略</div>
          <div class="form-field">
            <label class="form-field__label" for="cc-enabled">写入开关</label>
            <select id="cc-enabled" class="form-select" v-model="form.enabled">
              <option :value="true">启用</option>
              <option :value="false">停用</option>
            </select>
          </div>
          <div class="form-field">
            <label class="form-field__label" for="cc-ttl">TTL(秒)</label>
            <input id="cc-ttl" class="form-input" v-model.number="form.ttlSeconds" type="number" min="1" />
          </div>

          <div class="form-group-title">数据加载</div>
          <div class="form-field">
            <label class="form-field__label" for="cc-loader">数据加载器</label>
            <select id="cc-loader" class="form-select" v-model="form.dataLoader">
              <option value="">空=业务代码写缓存</option>
              <option v-for="l in loaders" :key="l.code" :value="l.code">{{ l.name }}</option>
            </select>
          </div>
          <div class="form-field" v-if="form.dataLoader">
            <label class="form-field__label" for="cc-loaderParam">加载器参数</label>
            <textarea id="cc-loaderParam" class="form-input" v-model="form.loaderParam" rows="3"></textarea>
            <div class="form-hint">如 {"table":"dw_contribution_metric","limit":5000}</div>
          </div>
        </div>
        <div class="modal__actions">
          <button class="btn btn--secondary" @click="dialogVisible = false">取消</button>
          <button class="btn btn--primary" :disabled="!!saving" @click="save">保存</button>
        </div>
      </div>
    </div>
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
.btn--sm { padding: 4px 10px; font-size: 13px; margin-right: 4px; }
.btn--danger { padding: 4px 10px; font-size: 13px; }
/* 表格横滚:列多时窄屏横向滚动 */
.table-scroll { overflow-x: auto; }
.table-scroll .table--full { min-width: 760px; }
</style>
