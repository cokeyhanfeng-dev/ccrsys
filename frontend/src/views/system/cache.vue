<template>
  <div>
    <div class="section-head">
      <div class="section-title">缓存配置</div>
      <div class="section-tip">
        Redis 缓存项级配置(详设 §3.6):每项可单独控制写入开关与 TTL,修改后<strong>立即生效不重启</strong>。
        关闭写入开关后,对应业务接口直接查询数据库(不再读写 Redis);来源 YML=application.yml 静态默认,
        DB=运行期覆盖值(优先级更高)。
      </div>
    </div>

    <div class="card">
      <div class="card__head">
        <span>缓存项</span>
        <button class="btn btn--secondary" @click="load">刷新</button>
      </div>
      <table class="table">
        <thead>
          <tr>
            <th>缓存项</th>
            <th>匹配范围</th>
            <th>来源</th>
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
            </td>
            <td><code class="key-code">{{ c.key || c.keyPattern }}</code></td>
            <td>
              <span :class="c.source === 'DB' ? 'badge badge--success' : 'badge badge--info'">{{ c.source }}</span>
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
                  style="width: 110px"
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
            </td>
          </tr>
          <tr v-if="!items.length"><td colspan="6" class="empty-cell">暂无数据</td></tr>
        </tbody>
      </table>
      <div class="section-tip" style="margin-top: 10px">
        说明:TTL 修改后自下一次缓存写入生效;Redis 不可用时自动降级直查库,不影响业务。
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { listCacheConfigs, updateCacheConfig, type CacheConfigItem } from '@/api/system'

const items = ref<(CacheConfigItem & { editingTtl?: number })[]>([])
const saving = ref('')

function itemName(code: string) {
  const map: Record<string, string> = {
    'lpr-effective': 'LPR 当前生效版本',
    'matrix-effective': '利率矩阵生效行',
    'rate-limit': '产品硬边界限流'
  }
  return map[code] || code
}

async function load() {
  try {
    const data = await listCacheConfigs()
    items.value = data.map((i) => ({ ...i, editingTtl: i.ttlSeconds ?? 300 }))
  } catch {
    items.value = []
  }
}

async function toggleEnabled(c: any, v: boolean) {
  saving.value = c.itemKey
  try {
    await updateCacheConfig(c.itemKey, { enabled: v })
    c.enabled = v
    c.source = 'DB'
    ElMessage.success(`已${v ? '开启' : '关闭'}写入 ${itemName(c.itemKey)}`)
  } catch {
    // 失败保持原值(el-switch 受控,由 model-value 回弹)
  } finally {
    saving.value = ''
  }
}

async function applyTtl(c: any) {
  const ttl = Number(c.editingTtl)
  if (!ttl || ttl <= 0) {
    ElMessage.warning('TTL 必须大于 0 秒')
    return
  }
  saving.value = c.itemKey
  try {
    await updateCacheConfig(c.itemKey, { ttlSeconds: ttl })
    c.ttlSeconds = ttl
    c.source = 'DB'
    ElMessage.success(`TTL 已更新为 ${ttl} 秒,立即生效`)
  } catch {
    c.editingTtl = c.ttlSeconds ?? 300
  } finally {
    saving.value = ''
  }
}

onMounted(load)
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
.btn--sm { padding: 4px 12px; font-size: 13px; }
</style>
