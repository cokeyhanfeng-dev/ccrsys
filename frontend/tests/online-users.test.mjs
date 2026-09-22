import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import { transformSync } from 'esbuild'
import { parse, compileScript } from '@vue/compiler-sfc'

function onlinePage(api) {
  const descriptor = parse(readFileSync(new URL('../src/views/system/online.vue', import.meta.url), 'utf8')).descriptor
  const ast = compileScript(descriptor, { id: 'online-test' }).scriptSetupAst
  let source = descriptor.scriptSetup.content
  for (const node of [...ast].reverse()) {
    if (node.type === 'ImportDeclaration') source = source.slice(0, node.start) + source.slice(node.end)
  }
  return runInNewContext(transformSync(source, { loader: 'ts' }).code
    + '\n({filters,records,total,userCount,failed,pageNum,load,reset,search})', {
    ref: value => ({ value }), reactive: value => value, onMounted() {}, listOnlineUsers: api,
    listDepts: async () => [], fmtDateTime: value => value
  })
}

test('在线清单发送组合筛选，展示人数与会话数，重置回第一页', async () => {
  const calls = []
  const view = onlinePage(async params => {
    calls.push(params)
    return { total: 2, userCount: 1, records: [{ username: 'test', client: 'PC' }], queriedAt: 'now' }
  })
  Object.assign(view.filters, { keyword: ' 测试 ', orgId: 10, client: 'MOBILE' })
  await view.load()
  assert.equal(calls[0].keyword, '测试'); assert.equal(calls[0].orgId, 10); assert.equal(calls[0].client, 'MOBILE')
  assert.equal(view.total.value, 2); assert.equal(view.userCount.value, 1)
  view.pageNum.value = 3
  view.reset(); await Promise.resolve()
  assert.equal(calls[1].pageNum, 1); assert.equal(calls[1].keyword, undefined); assert.equal(calls[1].client, undefined)
})

test('会话减少时退回最后一页，服务不可用显示失败状态', async () => {
  const calls = []
  const view = onlinePage(async params => {
    calls.push(params.pageNum)
    return { total: 1, userCount: 1, records: [], queriedAt: 'now' }
  })
  view.pageNum.value = 3
  await view.load()
  assert.deepEqual(calls, [3, 1])
  const failed = onlinePage(async () => { throw new Error('unavailable') })
  await failed.load()
  assert.equal(failed.failed.value, true)
  assert.equal(failed.records.value.length, 0)
})

test('较早的查询响应不能覆盖新的筛选结果', async () => {
  const finish = []
  const view = onlinePage(() => new Promise(resolve => finish.push(resolve)))
  const first = view.load(), second = view.load()
  finish[1]({ total: 1, userCount: 1, records: [{ username: 'new' }], queriedAt: 'new' })
  await second
  finish[0]({ total: 5, userCount: 5, records: [{ username: 'old' }], queriedAt: 'old' })
  await first
  assert.equal(view.records.value[0].username, 'new')
  assert.equal(view.total.value, 1)
})

function userStore(post) {
  const source = readFileSync(new URL('../src/store/user.ts', import.meta.url), 'utf8').replace(/^import .*\n/gm, '')
  const module = { exports: {} }
  let clears = 0
  runInNewContext(transformSync(source, { loader: 'ts', format: 'cjs' }).code, {
    module, exports: module.exports, defineStore: (_name, factory) => factory, ref: value => ({ value }), post,
    readToken: () => 'test-session', readUserInfo: () => ({ userId: 42 }), clearAuth: () => { clears++ }
  })
  return { store: module.exports.useUserStore(), clears: () => clears }
}

test('PC主动退出先注销当前服务端会话，成功后才清理本地身份', async () => {
  let finish
  const calls = []
  const { store, clears } = userStore(url => { calls.push(url); return new Promise(resolve => { finish = resolve }) })
  const pending = store.signOut()
  assert.deepEqual(calls, ['/auth/logout'])
  assert.equal(store.token.value, 'test-session'); assert.equal(clears(), 0)
  finish(); await pending
  assert.equal(store.token.value, ''); assert.equal(store.userInfo.value, null); assert.equal(clears(), 1)
})

test('注销网络失败保留身份供重试，单点初始化本地清理不调用远端', async () => {
  let calls = 0
  const { store, clears } = userStore(async () => { calls++; throw new Error('offline') })
  await assert.rejects(store.signOut(), /offline/)
  assert.equal(store.token.value, 'test-session'); assert.equal(clears(), 0)
  store.logout()
  assert.equal(calls, 1); assert.equal(clears(), 1); assert.equal(store.token.value, '')
})
