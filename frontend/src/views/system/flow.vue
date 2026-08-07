<template>
  <div>
    <div class="section-head">
      <div class="section-title">审批流程配置</div>
      <div class="section-tip">流程定义管理(节点/审批人可配置)与审批链路阈值配置(LPR / 权限矩阵,PRD D12/§7.2)。</div>
    </div>

    <div class="tabs">
      <button
        v-for="t in tabs"
        :key="t.key"
        class="btn"
        :class="activeTab === t.key ? 'btn--primary' : 'btn--ghost'"
        @click="activeTab = t.key"
      >
        {{ t.label }}
      </button>
    </div>

    <!-- 流程定义 -->
    <div v-if="activeTab === 'flow'" class="card">
      <div class="card__head">
        <span>流程定义</span>
        <span class="badge badge--info">节点审批人:流程定义时配置(permission_flag)</span>
      </div>
      <table class="table">
        <thead>
          <tr><th>流程编码</th><th>流程名称</th><th>版本</th><th>发布状态</th><th>激活状态</th><th>操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="f in definitions" :key="f.id">
            <td>{{ f.flow_code }}</td>
            <td>{{ f.flow_name }}</td>
            <td>{{ f.version }}</td>
            <td>
              <span :class="f.is_publish === 1 ? 'badge badge--success' : 'badge badge--neutral'">
                {{ f.is_publish === 1 ? '已发布' : '未发布' }}
              </span>
            </td>
            <td>
              <span :class="f.activity_status === 1 ? 'badge badge--success' : 'badge badge--danger'">
                {{ f.activity_status === 1 ? '激活' : '挂起' }}
              </span>
            </td>
            <td>
              <button v-if="f.is_publish !== 1" class="btn btn--text" @click="publish(f.id)">发布</button>
              <button v-else class="btn btn--text" @click="unpublish(f.id)">停用</button>
            </td>
          </tr>
        </tbody>
      </table>
      <div class="section-tip" style="margin-top:12px">
        💡 审批人配置:流程定义时按节点设置处理人(permission_flag);审批链路阈值见下方 LPR / 权限矩阵。
      </div>
    </div>

    <!-- LPR 阈值 -->
    <div v-if="activeTab === 'lpr'" class="card">
      <div class="card__head">
        <span>LPR 参数(计划财务部人工维护,PRD D12)</span>
        <span class="badge badge--warning">权限阈值按 LPR±BP 自动换算</span>
      </div>
      <table class="table">
        <thead>
          <tr><th>版本</th><th>一年期 LPR</th><th>五年期以上 LPR</th><th>生效时间</th><th>状态</th></tr>
        </thead>
        <tbody>
          <tr v-for="l in lprList" :key="l.id">
            <td>{{ l.versionCode }}</td>
            <td class="num">{{ l.lpr1y }}%</td>
            <td class="num">{{ l.lpr5y }}%</td>
            <td>{{ l.effectiveFrom }}</td>
            <td><span class="badge badge--success">{{ l.status }}</span></td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 权限矩阵阈值 -->
    <div v-if="activeTab === 'matrix'" class="card">
      <div class="card__head">
        <span>权限矩阵(PRD §7.2 LPR±BP 路由阈值)</span>
        <span class="badge badge--info">每行=终审岗位 + 利率边界(可配置)</span>
      </div>
      <table class="table">
        <thead>
          <tr>
            <th>业务大类</th><th>存量/新增</th><th>客户类型</th><th>金额档</th><th>期限档</th>
            <th>终审岗位</th><th>边界</th><th>优先级</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="m in matrixList" :key="m.id">
            <td>{{ m.businessBigType }}</td>
            <td>{{ m.newOrExisting }}</td>
            <td>{{ m.customerType || '通配' }}</td>
            <td>{{ m.amountTier || '通配' }}</td>
            <td>{{ m.termTier || '通配' }}</td>
            <td>{{ m.startNodeCode }}</td>
            <td>{{ boundaryText(m) }}</td>
            <td class="num">{{ m.priority }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { get, post } from '@/api/request'

const tabs = [
  { key: 'flow', label: '流程定义' },
  { key: 'lpr', label: 'LPR 阈值' },
  { key: 'matrix', label: '权限矩阵' }
]
const activeTab = ref('flow')
const definitions = ref<any[]>([])
const lprList = ref<any[]>([])
const matrixList = ref<any[]>([])

async function load() {
  definitions.value = await get<any[]>('/system/flow/definitions')
  lprList.value = await get<any[]>('/system/flow/thresholds/lpr')
  matrixList.value = await get<any[]>('/system/flow/thresholds/matrix')
}
async function publish(id: number) {
  await post(`/system/flow/definitions/${id}/publish`, {})
  ElMessage.success('已发布')
  load()
}
async function unpublish(id: number) {
  await post(`/system/flow/definitions/${id}/unpublish`, {})
  ElMessage.success('已停用')
  load()
}
function boundaryText(m: any) {
  if (m.boundaryMinRate != null && m.boundaryBp == null) return `≥ ${m.boundaryMinRate}%`
  if (m.boundaryBp != null) return `LPR ${m.bpSign || ''}${m.boundaryBp}BP`
  return '权限内'
}
onMounted(load)
</script>

<style scoped>
.section-head { margin-bottom: 16px; }
.section-title { font-size: var(--fs-h2); font-weight: 600; margin-bottom: 6px; }
.section-tip { font-size: 13px; color: var(--color-text-sub); }
.tabs { display: flex; gap: 8px; margin-bottom: 16px; }
.card { background: var(--color-surface); border: 1px solid var(--color-border); border-radius: var(--radius); padding: 16px; box-shadow: var(--shadow-sm); }
.card__head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.table { border-radius: var(--radius); overflow: hidden; }
</style>
