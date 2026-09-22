import { defineStore } from 'pinia'
import { ref } from 'vue'
import { post, request } from '@/api/request'
import { authStorage, clearAuth, readToken, readUserInfo, saveAuth } from '@/auth/storage.mjs'

export interface UserInfo {
  userId: number
  userName: string
  nickName: string
  roles: string[]
  orgId: number
  /** 机构中文名称(登录时后端按 orgId 查 ccr_sys_dept.dept_name 带出;兼容旧缓存无字段) */
  orgName?: string
  /** 是否需强制改密:1需改密/0已改(兼容旧缓存无字段) */
  pwdChangeFlag?: string
}

// 两种登录共用本地权限信息；平台 token 不进入浏览器。
export const useUserStore = defineStore('user', () => {
  const userInfo = ref<UserInfo | null>(readUserInfo())
  const token = ref<string>(readToken())
  const ssoError = ref('')

  function acceptLogin(data: { token: string; userInfo: UserInfo }, persistent = false) {
    saveAuth(data, persistent)
    token.value = data.token
    userInfo.value = data.userInfo
    ssoError.value = ''
  }

  async function login(username: string, password: string) {
    const data = await post<{ token: string; userInfo: UserInfo }>('/auth/login', { username, password })
    acceptLogin(data)
  }

  async function loginByCode(code: string) {
    const data = await request<{ token: string; userInfo: UserInfo }>({
      url: '/auth/code-login', method: 'post', data: { code }, timeout: 30000
    })
    acceptLogin(data, true)
  }

  function logout() {
    token.value = ''
    userInfo.value = null
    clearAuth()
  }

  // 用户主动退出须先注销服务端当前会话；网络失败保留本地身份，允许重试。
  // logout() 仍用于单点登录初始化等纯本地清理，避免触发不必要的远端请求。
  async function signOut() {
    await post('/auth/logout')
    logout()
  }

  // 改密成功后标记已改,并同步持久化(sessionStorage),避免刷新后又被守卫弹回
  function markPasswordChanged() {
    if (userInfo.value) {
      userInfo.value = { ...userInfo.value, pwdChangeFlag: '0' }
      authStorage().setItem('ccr_user_info', JSON.stringify(userInfo.value))
    }
  }

  return { token, userInfo, ssoError, login, loginByCode, logout, signOut, markPasswordChanged }
})
