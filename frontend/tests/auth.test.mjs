import test from 'node:test'
import assert from 'node:assert/strict'
import { parseCodeCallback, bootstrapCodeLogin } from '../src/auth/code-login.mjs'
import { clearAuth, readToken, readUserInfo, saveAuth } from '../src/auth/storage.mjs'

test('无 code 保持密码登录，不将其他名称误认作 code', async () => {
  assert.equal(parseCodeCallback('https://ccr.example/login?decode=abc'), null)
  assert.equal(await bootstrapCodeLogin({ href: 'https://ccr.example/login' }), '')
})

test('精确解码并移除 code，保留业务路由和其他参数', () => {
  const result = parseCodeCallback('https://ccr.example/approval/12?code=a%2Bb%2F%26%3D&view=detail#section')
  assert.equal(result.code, 'a+b/&=')
  assert.equal(result.cleanUrl, '/approval/12?view=detail#section')
  assert.equal(result.target, result.cleanUrl)
  assert.equal(result.valid, true)
})

test('兼容 hash 携码并拒绝重复、冲突、空白及超长 code', () => {
  assert.equal(parseCodeCallback('https://ccr.example/#/login?code=abc').code, 'abc')
  for (const suffix of ['?code=', '?code=%20', '?code=%0Aabc', '?code=a&code=a', '?code=a#/login?code=b', '?code=' + 'a'.repeat(2049)]) {
    const result = parseCodeCallback('https://ccr.example/' + suffix)
    assert.equal(result.valid, false)
    assert.ok(!result.cleanUrl.includes('code='))
  }
})

test('先清理地址和旧身份，等待兑换成功后再进入业务页；刷新不重复兑换', async () => {
  const events = []
  let href = 'https://ccr.example/?code=one-use'
  let finish
  const options = {
    href, replace: url => { href = 'https://ccr.example' + url; events.push(url) },
    clear: () => events.push('clear'), login: code => { events.push(code); return new Promise(resolve => { finish = resolve }) }
  }
  const pending = bootstrapCodeLogin(options)
  assert.deepEqual(events, ['/', 'clear', 'one-use'])
  finish()
  assert.equal(await pending, '')
  assert.deepEqual(events, ['/', 'clear', 'one-use', '/overview'])
  await bootstrapCodeLogin({ ...options, href })
  assert.equal(events.length, 4)
})

test('兑换失败清除旧会话，提示重新进入，禁止重试或带 code 跳转', async () => {
  const events = []
  const result = await bootstrapCodeLogin({
    href: 'https://ccr.example/approval?code=expired',
    replace: value => events.push(value), clear: () => events.push('clear'),
    login: async () => { events.push('login'); throw new Error('sensitive-token') }
  })
  assert.deepEqual(events, ['/approval', 'clear', 'login', 'clear', '/login'])
  assert.ok(result.includes('单点登录失败'))
  assert.ok(!result.includes('sensitive-token'))
})

test('冲突 code 不调用兑换接口', async () => {
  let called = false
  await bootstrapCodeLogin({ href: 'https://ccr.example/?code=a&code=b', replace() {}, clear() {}, login() { called = true } })
  assert.equal(called, false)
})

function storage() {
  const map = new Map()
  return { getItem: key => map.get(key) ?? null, setItem: (key, value) => map.set(key, value), removeItem: key => map.delete(key) }
}

test('code 登录持久化，密码登录沿用会话存储，退出同时清理两种存储', () => {
  globalThis.localStorage = storage(); globalThis.sessionStorage = storage()
  saveAuth({ token: 'ccr-sso', userInfo: { userName: '001234' } }, true)
  assert.equal(localStorage.getItem('ccr_token'), 'ccr-sso')
  assert.equal(sessionStorage.getItem('ccr_token'), null)
  assert.equal(readToken(), 'ccr-sso')
  assert.equal(readUserInfo().userName, '001234')
  saveAuth({ token: 'password-session', userInfo: { userName: 'admin' } })
  assert.equal(localStorage.getItem('ccr_token'), null)
  assert.equal(readToken(), 'password-session')
  clearAuth()
  assert.equal(readToken(), '')
  assert.equal(readUserInfo(), null)
})

test('损坏缓存及存储写入失败不会留下残缺身份', () => {
  globalThis.localStorage = storage(); globalThis.sessionStorage = storage()
  localStorage.setItem('ccr_token', 'stale')
  localStorage.setItem('ccr_user_info', '{')
  assert.equal(readUserInfo(), null)
  assert.equal(readToken(), '')
  localStorage.setItem = () => { throw new Error('quota') }
  assert.throws(() => saveAuth({ token: 'new', userInfo: {} }, true))
  assert.equal(readToken(), '')
})
