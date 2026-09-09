// 密码登录沿用标签页会话；code 登录按接入要求持久化到 localStorage。
export function authStorage() {
  return sessionStorage.getItem('ccr_token') ? sessionStorage : localStorage
}

export function readToken() {
  return authStorage().getItem('ccr_token') || ''
}

export function readUserInfo() {
  try {
    return JSON.parse(authStorage().getItem('ccr_user_info') || 'null')
  } catch {
    clearAuth()
    return null
  }
}

export function clearAuth() {
  for (const storage of [sessionStorage, localStorage]) {
    storage.removeItem('ccr_token')
    storage.removeItem('ccr_user_info')
  }
}

export function saveAuth(data, persistent = false) {
  clearAuth()
  const storage = persistent ? localStorage : sessionStorage
  try {
    storage.setItem('ccr_user_info', JSON.stringify(data.userInfo))
    storage.setItem('ccr_token', data.token)
  } catch (error) {
    clearAuth()
    throw error
  }
}
