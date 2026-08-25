import { defineStore } from 'pinia'
import { ref } from 'vue'
import { post } from '@/api/request'

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

// 用户状态:token 与登录信息存 sessionStorage(会话级,关闭浏览器/标签即清空需重新登录;
// 页面刷新 F5 保留,路由守卫/数据权限仍可用)
export const useUserStore = defineStore('user', () => {
  const token = ref<string>(sessionStorage.getItem('ccr_token') || '')
  const userInfo = ref<UserInfo | null>(
    JSON.parse(sessionStorage.getItem('ccr_user_info') || 'null')
  )

  async function login(username: string, password: string) {
    const data = await post<{ token: string; userInfo: UserInfo }>('/auth/login', { username, password })
    token.value = data.token
    userInfo.value = data.userInfo
    sessionStorage.setItem('ccr_token', data.token)
    sessionStorage.setItem('ccr_user_info', JSON.stringify(data.userInfo))
  }

  function logout() {
    token.value = ''
    userInfo.value = null
    sessionStorage.removeItem('ccr_token')
    sessionStorage.removeItem('ccr_user_info')
  }

  // 改密成功后标记已改,并同步持久化(sessionStorage),避免刷新后又被守卫弹回
  function markPasswordChanged() {
    if (userInfo.value) {
      userInfo.value = { ...userInfo.value, pwdChangeFlag: '0' }
      sessionStorage.setItem('ccr_user_info', JSON.stringify(userInfo.value))
    }
  }

  return { token, userInfo, login, logout, markPasswordChanged }
})
