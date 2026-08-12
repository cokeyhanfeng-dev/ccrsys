<template>
  <div>
    <div class="section-head">
      <div class="section-title">利率审批小组成员工作台 · 待我表决</div>
      <InfoTip content="仅提交本人&quot;赞成/反对&quot;意见,后台自动计票;不展示其他成员票型或汇总票数,本人意见提交后不可修改。" />
    </div>

    <div class="card">
      <div class="card__head">
        <span>待我表决</span>
        <span class="badge badge--warning">本人待表决 {{ pendingCount }} 项</span>
      </div>
      <table class="table">
        <thead>
          <tr><th>定价分项</th><th>担保类型</th><th>申请金额(万元)</th><th>申请利率</th><th>本人票型</th><th>表决意见</th><th>状态</th><th>操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="(row, idx) in rows" :key="row.roundId + '-' + row.pricingItemId">
            <td>
              <div>{{ row.pricingItemNo }}</div>
              <div class="section-tip">{{ productName(row.productCode) }}</div>
            </td>
            <td><span class="badge badge--info">{{ guaranteeTypeText(row.guaranteeType, '—') }}</span></td>
            <td class="num">{{ row.pricingAmount ?? '—' }}</td>
            <td class="num">{{ row.requestedRate }}%</td>
            <td>
              <template v-if="!row.submitted">
                <label class="vote-option"><input type="radio" :name="'vote-'+idx" value="APPROVE" v-model="row.choice" /> 赞成</label>
                <label class="vote-option"><input type="radio" :name="'vote-'+idx" value="REJECT" v-model="row.choice" /> 反对</label>
              </template>
              <span v-else class="badge" :class="row.myChoice === 'APPROVE' ? 'badge--success' : 'badge--danger'">
                {{ row.myChoice === 'APPROVE' ? '本人已投:赞成' : row.myChoice === 'REJECT' ? '本人已投:反对' : '已提交' }}
              </span>
            </td>
            <td>
              <el-input v-if="!row.submitted" v-model="row.comment" size="small" placeholder="意见(可选)" style="width:180px" />
              <span v-else class="stat-card__sub">{{ row.myComment || '—' }}</span>
            </td>
            <td><span :class="row.submitted ? 'badge badge--success' : 'badge badge--neutral'">{{ row.submitted ? '已提交' : '未提交' }}</span></td>
            <td><button class="btn btn--text" @click="goDetail(row)">查看详情</button></td>
          </tr>
          <tr v-if="!rows.length"><td colspan="8" class="empty">暂无待表决分项</td></tr>
        </tbody>
      </table>
      <div style="margin-top:16px;display:flex;align-items:center;gap:12px" v-if="pendingCount > 0">
        <button class="btn btn--primary" :disabled="submitting" @click="submitAll">提交全部已选票</button>
        <span class="section-tip">提交后不可修改;表决全程匿名,不展示他人票型</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listVoteTodo, fetchMyBallot, submitBallot } from '@/api/vote'
import { newIdempotencyKey } from '@/api/approval'
import { productName, guaranteeTypeText } from '@/utils/dict'

const router = useRouter()
const rows = ref<any[]>([])
const submitting = ref(false)

const pendingCount = computed(() => rows.value.filter((r) => !r.submitted).length)

async function load() {
  try {
    const data = await listVoteTodo<any[]>()
    const list = (data || []).map((p) => ({
      roundId: p.roundId,
      pricingItemId: p.pricingItemId,
      productCode: p.productCode || '—',
      pricingItemNo: p.pricingItemNo || '',
      guaranteeType: p.guaranteeType || '',
      pricingAmount: p.pricingAmount,
      requestedRate: p.requestedRate,
      choice: '',
      comment: '',
      submitted: false,
      myChoice: '',
      myComment: ''
    }))
    // 逐项查询本人已投票型(只返回本人票,不泄露他人信息)
    await Promise.all(list.map(async (row) => {
      try {
        const my = await fetchMyBallot(row.roundId, row.pricingItemId)
        if (my && my.voteChoice) {
          row.submitted = true
          row.myChoice = my.voteChoice
          row.myComment = my.voteComment || ''
        }
      } catch { /* 单项查询失败不阻断列表 */ }
    }))
    rows.value = list
  } catch {
    rows.value = []
  }
}

// 查看分项审批详情(只读,委员无操作权限)
function goDetail(row: any) {
  router.push(`/approval/detail/${row.pricingItemId}`)
}

async function submitAll() {
  const targets = rows.value.filter((r) => !r.submitted && r.choice)
  if (!targets.length) {
    ElMessage.warning('请先选择票型')
    return
  }
  // P2-1:反对未填意见时提示补充(建议性,不阻断投票)
  const noCommentRejects = targets.filter((r) => r.choice === 'REJECT' && !r.comment?.trim())
  if (noCommentRejects.length) {
    try {
      await ElMessageBox.confirm(
        `${noCommentRejects.length} 项反对票未填写意见。建议补充意见以便后续环节参考,仍确认提交?`,
        '反对意见缺失', { type: 'warning', confirmButtonText: '仍提交', cancelButtonText: '返回补充' }
      )
    } catch {
      return
    }
  }
  submitting.value = true
  for (const row of targets) {
    try {
      await submitBallot(row.roundId, {
        pricingItemId: row.pricingItemId,
        choice: row.choice,
        comment: row.comment || undefined
      }, newIdempotencyKey())
      row.submitted = true
      row.myChoice = row.choice
      row.myComment = row.comment
    } catch { /* 拦截器提示,继续其余分项 */ }
  }
  submitting.value = false
  ElMessage.success('已提交本人选票')
  load()
}
onMounted(load)
</script>

<style scoped>
.vote-option { margin-right: 12px; font-size: 13px; display: inline-flex; align-items: center; gap: 4px; }
.table { border-radius: var(--radius-sm); overflow-x: auto; }
</style>
