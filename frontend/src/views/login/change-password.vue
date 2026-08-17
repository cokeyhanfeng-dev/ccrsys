<template>
  <div class="change-page">
    <section class="change-panel">
      <el-card class="change-card" shadow="never">
        <div class="change-head">
          <h2 class="change-title">修改密码</h2>
          <p class="change-sub">
            {{ isForced ? '首次登录需设置新密码后使用系统' : '设置新密码,下次登录生效' }}
          </p>
        </div>

        <el-form :model="form" @submit.prevent="onSubmit">
          <el-form-item label="原密码">
            <el-input
              v-model="form.oldPassword"
              type="password"
              placeholder="请输入原密码"
              size="large"
              show-password
            />
          </el-form-item>
          <el-form-item label="新密码">
            <el-input
              v-model="form.newPassword"
              type="password"
              placeholder="不少于8位,含大写/小写/特殊字符"
              size="large"
              show-password
              @input="newHint = pwdHint(form.newPassword)"
            />
            <span v-if="newHint" class="pwd-hint" :class="{ 'pwd-hint--ok': newHint.startsWith('✓') }">
              {{ newHint }}
            </span>
          </el-form-item>
          <el-form-item label="确认新密码">
            <el-input
              v-model="form.confirmPassword"
              type="password"
              placeholder="再次输入新密码"
              size="large"
              show-password
            />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" size="large" class="change-btn" :loading="loading" @click="onSubmit">
              {{ loading ? '提交中…' : '确认修改' }}
            </el-button>
          </el-form-item>
        </el-form>
      </el-card>
      <p class="change-page__foot">客户贡献度与利率决策系统 · CCR</p>
    </section>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/store/user'
import { changePassword } from '@/api/auth'
import { pwdHint } from '@/utils/password'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const form = ref({ oldPassword: '', newPassword: '', confirmPassword: '' })
const newHint = ref('')
const loading = ref(false)
// 首次登录强制改密(layout 之外独立页,不自带菜单);主动进入时普通提示
const isForced = userStore.userInfo?.pwdChangeFlag === '1'

async function onSubmit() {
  const { oldPassword, newPassword, confirmPassword } = form.value
  if (!oldPassword || !newPassword || !confirmPassword) {
    ElMessage.warning('请完整填写密码')
    return
  }
  if (newPassword !== confirmPassword) {
    ElMessage.warning('两次输入的新密码不一致')
    return
  }
  if (newPassword === oldPassword) {
    ElMessage.warning('新密码不能与原密码相同')
    return
  }
  if (!pwdHint(newPassword).startsWith('✓')) {
    ElMessage.warning(newHint.value || '新密码不满足强度要求(不少于8位,含大写/小写/特殊字符)')
    return
  }
  loading.value = true
  try {
    await changePassword(oldPassword, newPassword)
    userStore.markPasswordChanged()
    ElMessage.success('密码修改成功')
    const redirect = (route.query.redirect as string) || '/overview'
    router.replace(redirect)
  } catch {
    // 错误已由拦截器提示
  } finally {
    loading.value = false
  }
}
</script>

<style scoped lang="scss">
.change-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 32px 16px;
  background:
    linear-gradient(160deg, rgba(15, 32, 52, .85) 0%, rgba(0, 21, 40, .9) 100%),
    url('@/assets/img/login-background.jpg') center/cover no-repeat, #1f2d3d;
}
.change-panel {
  width: 420px;
  max-width: 100%;
  text-align: center;
}
.change-card {
  padding: 8px;
  :deep(.el-card__body) {
    padding: 28px 32px;
  }
}
.change-head { margin-bottom: 20px; }
.change-title { font-size: 22px; font-weight: 700; color: var(--color-text-main); margin-bottom: 6px; }
.change-sub { font-size: 13px; color: var(--color-text-sub); }
.pwd-hint {
  display: block;
  width: 100%;
  font-size: 12px;
  line-height: 1.6;
  color: var(--color-warning);
  margin-top: 2px;
}
.pwd-hint--ok { color: var(--color-success); }
.change-btn { width: 100%; height: 44px; font-size: 15px; letter-spacing: 4px; border-radius: 8px; }
.change-page__foot { margin-top: 20px; font-size: 12px; color: rgba(255,255,255,.45); letter-spacing: 1px; }
</style>
