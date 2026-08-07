<template>
  <div class="login-page">
    <el-card class="login-card">
      <h2 class="login-title">客户贡献度与利率决策系统</h2>
      <p class="login-sub">集团授信、多成员定价审批与承诺跟踪一体化</p>
      <el-form :model="form" @submit.prevent="onSubmit">
        <el-form-item>
          <el-input v-model="form.username" placeholder="用户名" size="large">
            <template #prefix><el-icon><User /></el-icon></template>
          </el-input>
        </el-form-item>
        <el-form-item>
          <el-input
            v-model="form.password"
            type="password"
            placeholder="密码"
            size="large"
            show-password
          >
            <template #prefix><el-icon><Lock /></el-icon></template>
          </el-input>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" size="large" class="login-btn" :loading="loading" @click="onSubmit">
            登录
          </el-button>
        </el-form-item>
      </el-form>
      <p class="login-tip">首期本地账号演示;SSO 统一认证预留适配(§4.1)</p>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/store/user'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const form = reactive({ username: '', password: '' })
const loading = ref(false)

async function onSubmit() {
  if (!form.username || !form.password) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    await userStore.login(form.username, form.password)
    ElMessage.success('登录成功')
    router.push((route.query.redirect as string) || '/overview')
  } catch {
    // 错误已由拦截器提示
  } finally {
    loading.value = false
  }
}
</script>

<style scoped lang="scss">
.login-page {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #2563eb 0%, #1d4ed8 100%);
}

.login-card {
  width: 400px;
  padding: 8px 8px 24px;

  .login-title {
    text-align: center;
    color: var(--color-primary);
    margin-bottom: 4px;
  }

  .login-sub {
    text-align: center;
    color: var(--color-text-sub);
    font-size: 13px;
    margin-bottom: 24px;
  }

  .login-btn {
    width: 100%;
  }

  .login-tip {
    text-align: center;
    color: #9ca3af;
    font-size: 12px;
    margin-top: 8px;
  }
}
</style>
