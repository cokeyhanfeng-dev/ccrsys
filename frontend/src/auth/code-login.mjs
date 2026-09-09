/** 同时支持 ?code= 与旧入口 #/path?code=；重复参数拒绝，始终先移除凭证。 */
export function parseCodeCallback(href) {
  const url = new URL(href)
  const hash = url.hash.slice(1)
  const hashQuery = hash.indexOf('?')
  const hashParams = new URLSearchParams(hashQuery >= 0 ? hash.slice(hashQuery + 1) : '')
  const codes = [...url.searchParams.getAll('code'), ...hashParams.getAll('code')]
  if (!codes.length) return null
  url.searchParams.delete('code')
  hashParams.delete('code')
  if (hashQuery >= 0) {
    url.hash = hash.slice(0, hashQuery) + (hashParams.size ? '?' + hashParams.toString() : '')
  }
  const code = codes[0]
  return {
    code,
    valid: codes.length === 1 && !!code.trim() && code.length <= 2048 && !/[\x00-\x1f\x7f]/.test(code),
    cleanUrl: url.pathname + url.search + url.hash,
    target: url.pathname === '/' || url.pathname === '/login' ? '/overview' : url.pathname + url.search + url.hash
  }
}

/** 在注册路由前等待认证，避免业务页面先挂载、旧账号抢先请求或刷新重复兑换。 */
export async function bootstrapCodeLogin({ href, replace, login, clear }) {
  const callback = parseCodeCallback(href)
  if (!callback) return ''
  replace(callback.cleanUrl)
  try {
    clear()
    if (!callback.valid) throw new Error('单点登录参数无效，请从统一认证平台重新进入')
    await login(callback.code)
    replace(callback.target)
    return ''
  } catch {
    clear()
    replace('/login')
    return '单点登录失败，请从统一认证平台重新进入，或使用账号密码登录。'
  }
}
