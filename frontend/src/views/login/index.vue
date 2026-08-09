<template>
  <div class="login-page">
    <!-- 左侧品牌区:深蓝渐变 + 抽象装饰(纯 CSS/SVG,无外部图片) -->
    <section class="login-brand">
      <div class="login-brand__decor login-brand__decor--ring1"></div>
      <div class="login-brand__decor login-brand__decor--ring2"></div>
      <div class="login-brand__decor login-brand__decor--grid"></div>

      <div class="login-brand__inner">
        <div class="login-brand__logo">
          <span class="login-brand__logo-mark">
            <svg viewBox="0 0 32 32" fill="none" xmlns="http://www.w3.org/2000/svg">
              <rect x="3" y="14" width="5" height="13" rx="1.5" fill="#93c5fd"/>
              <rect x="13.5" y="8" width="5" height="19" rx="1.5" fill="#bfdbfe"/>
              <rect x="24" y="3" width="5" height="24" rx="1.5" fill="#ffffff"/>
            </svg>
          </span>
          <span class="login-brand__logo-text">客户贡献度与利率决策系统</span>
        </div>

        <h1 class="login-brand__slogan">贡献可度量<br />定价有依据</h1>
        <p class="login-brand__desc">
          集团授信、多成员定价审批与承诺跟踪一体化,覆盖申请、审批、表决、决策到履约跟踪的全流程利率管理。
        </p>

        <ul class="login-brand__points">
          <li class="login-brand__point">
            <el-icon><TrendCharts /></el-icon>
            <span>客户贡献度双维度测算与承诺落差预警</span>
          </li>
          <li class="login-brand__point">
            <el-icon><Stamp /></el-icon>
            <span>多级审批、六人表决与行长决策全流程留痕</span>
          </li>
          <li class="login-brand__point">
            <el-icon><Lock /></el-icon>
            <span>匿名表决、审计反查,满足内控合规要求</span>
          </li>
        </ul>
      </div>

      <div class="login-brand__footer">银行内部系统 · 请使用行内账号登录</div>
    </section>

    <!-- 右侧登录卡片 -->
    <section class="login-panel">
      <el-card class="login-card" shadow="never">
        <h2 class="login-title">欢迎登录</h2>
        <p class="login-sub">客户贡献度与利率决策系统</p>
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
              {{ loading ? '登录中…' : '登 录' }}
            </el-button>
          </el-form-item>
        </el-form>
        <p class="login-tip">首期本地账号演示;SSO 统一认证预留适配(§4.1)</p>
      </el-card>
      <p class="login-panel__foot">客户贡献度与利率决策系统 · CCR</p>
    </section>
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
  min-height: 100vh;
  display: flex;
  background: var(--color-bg);
}

/* ── 左侧品牌区 ── */
.login-brand {
  position: relative;
  flex: 1.2;
  display: flex;
  flex-direction: column;
  justify-content: center;
  overflow: hidden;
  padding: 64px 72px;
  color: #fff;
  background: linear-gradient(160deg, #163a75 0%, #0f2a5c 55%, #0a1c40 100%);

  &__inner {
    position: relative;
    z-index: 1;
    max-width: 520px;
  }

  /* 抽象装饰:同心圆环 + 细网格,纯 CSS */
  &__decor {
    position: absolute;
    pointer-events: none;

    &--ring1 {
      width: 520px;
      height: 520px;
      right: -160px;
      top: -160px;
      border-radius: 50%;
      border: 1px solid rgba(147, 197, 253, 0.18);
      box-shadow:
        0 0 0 60px rgba(147, 197, 253, 0.05),
        0 0 0 120px rgba(147, 197, 253, 0.03);
    }

    &--ring2 {
      width: 360px;
      height: 360px;
      left: -120px;
      bottom: -120px;
      border-radius: 50%;
      border: 1px dashed rgba(147, 197, 253, 0.22);
      box-shadow: 0 0 0 48px rgba(147, 197, 253, 0.04);
    }

    &--grid {
      inset: 0;
      background-image:
        linear-gradient(rgba(147, 197, 253, 0.05) 1px, transparent 1px),
        linear-gradient(90deg, rgba(147, 197, 253, 0.05) 1px, transparent 1px);
      background-size: 56px 56px;
      mask-image: radial-gradient(ellipse 90% 80% at 30% 40%, #000 30%, transparent 75%);
    }
  }

  &__logo {
    display: flex;
    align-items: center;
    gap: 12px;
    margin-bottom: 56px;
  }

  &__logo-mark {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 40px;
    height: 40px;
    border-radius: 10px;
    background: rgba(255, 255, 255, 0.1);
    box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.16);

    svg {
      width: 22px;
      height: 22px;
    }
  }

  &__logo-text {
    font-size: 15px;
    font-weight: 600;
    letter-spacing: 1px;
    color: rgba(255, 255, 255, 0.92);
  }

  &__slogan {
    font-size: 40px;
    font-weight: 700;
    line-height: 1.3;
    letter-spacing: 2px;
    margin-bottom: 20px;
  }

  &__desc {
    font-size: 14px;
    line-height: 1.9;
    color: rgba(255, 255, 255, 0.66);
    margin-bottom: 40px;
  }

  &__points {
    list-style: none;
    display: flex;
    flex-direction: column;
    gap: 14px;
  }

  &__point {
    display: flex;
    align-items: center;
    gap: 12px;
    font-size: 13px;
    color: rgba(255, 255, 255, 0.82);

    .el-icon {
      flex: none;
      width: 30px;
      height: 30px;
      border-radius: 8px;
      align-items: center;
      justify-content: center;
      font-size: 16px;
      color: #bfdbfe;
      background: rgba(147, 197, 253, 0.12);
      box-shadow: inset 0 0 0 1px rgba(147, 197, 253, 0.2);
    }
  }

  &__footer {
    position: absolute;
    left: 72px;
    bottom: 28px;
    font-size: 12px;
    color: rgba(255, 255, 255, 0.4);
    letter-spacing: 1px;
  }
}

/* ── 右侧登录面板 ── */
.login-panel {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 48px 32px;

  &__foot {
    margin-top: 28px;
    font-size: 12px;
    color: var(--color-text-light);
    letter-spacing: 1px;
  }
}

.login-card {
  width: 400px;
  max-width: 100%;
  padding: 12px 12px 20px;

  :deep(.el-card__body) {
    padding: 12px;
  }

  .login-title {
    text-align: center;
    font-size: 24px;
    font-weight: 700;
    color: var(--color-text-main);
    margin-bottom: 6px;
  }

  .login-sub {
    text-align: center;
    color: var(--color-text-sub);
    font-size: 13px;
    margin-bottom: 32px;
    letter-spacing: 1px;
  }

  :deep(.el-input__wrapper) {
    padding: 4px 14px;
    border-radius: 8px;
  }

  :deep(.el-form-item) {
    margin-bottom: 22px;
  }

  .login-btn {
    width: 100%;
    height: 44px;
    font-size: 15px;
    letter-spacing: 6px;
    border-radius: 8px;
    background: var(--grad-primary);
    border: none;
    box-shadow: var(--shadow-primary);

    &:hover {
      box-shadow: 0 6px 16px rgba(37, 99, 235, 0.34);
    }
  }

  .login-tip {
    text-align: center;
    color: #9ca3af;
    font-size: 12px;
    margin-top: 4px;
  }
}

/* ── 响应式:窄屏折叠为单列 ── */
@media (max-width: 900px) {
  .login-page {
    flex-direction: column;
  }

  .login-brand {
    flex: none;
    padding: 40px 32px 56px;

    &__slogan {
      font-size: 28px;
    }

    &__logo {
      margin-bottom: 32px;
    }

    &__desc,
    &__points,
    &__footer {
      display: none;
    }
  }

  .login-panel {
    flex: 1;
    padding: 40px 24px;
  }
}
</style>
