import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import { transformSync } from 'esbuild'
import { parse, compileScript } from '@vue/compiler-sfc'

function onlinePage(api, actions = {}) {
  const descriptor = parse(readFileSync(new URL('../src/views/system/online.vue', import.meta.url), 'utf8')).descriptor
  const ast = compileScript(descriptor, { id: 'online-test' }).scriptSetupAst
  let source = descriptor.scriptSetup.content
  for (const node of [...ast].reverse()) {
    if (node.type === 'ImportDeclaration') source = source.slice(0, node.start) + source.slice(node.end)
  }
  return runInNewContext(transformSync(source, { loader: 'ts' }).code
    + '\n({filters,records,total,userCount,sessionCount,terminalCounts,expanded,toggle,filterClient,failed,pageNum,load,reset,search,kickout,kickingUserId})', {
    ref: value => ({ value }), reactive: value => value, onMounted() {}, listOnlineUsers: api,
    listDepts: async () => [], fmtDateTime: value => value,
    useUserStore: () => ({ userInfo: { userId: 'admin-test' } }),
    ElMessage: { success: actions.success || (() => {}) },
    ElMessageBox: { confirm: actions.confirm || (async () => {}) },
    kickoutOnlineUser: actions.kickout || (async () => {})
  })
}

test('在线清单发送组合筛选，展示人数与会话数，重置回第一页', async () => {
  const calls = []
  const view = onlinePage(async params => {
    calls.push(params)
    return { total: 1, userCount: 1, sessionCount: 2, pcUserCount: 1, mobileUserCount: 1,
      pcSessionCount: 1, mobileSessionCount: 2,
      records: [{ userId: '42', username: 'test', clients: ['MOBILE'], sessionCount: 2,
        sessions: [{ client: 'MOBILE', loginTime: 'first' }, { client: 'MOBILE', loginTime: 'second' }] }], queriedAt: 'now' }
  })
  Object.assign(view.filters, { keyword: ' 测试 ', orgId: 10, client: 'MOBILE' })
  await view.load()
  assert.equal(calls[0].keyword, '测试'); assert.equal(calls[0].orgId, 10); assert.equal(calls[0].client, 'MOBILE')
  assert.equal(view.total.value, 1); assert.equal(view.userCount.value, 1)
  assert.equal(view.sessionCount.value, 2)
  assert.equal(view.terminalCounts.pcUsers, 1); assert.equal(view.terminalCounts.mobileUsers, 1)
  view.toggle('42'); assert.equal(view.expanded.value.has('42'), true)
  assert.equal(view.records.value[0].sessions.length, 2)
  view.toggle('42'); assert.equal(view.expanded.value.has('42'), false)
  view.pageNum.value = 3
  view.reset(); await Promise.resolve()
  assert.equal(calls[1].pageNum, 1); assert.equal(calls[1].keyword, undefined); assert.equal(calls[1].client, undefined)
})

test('默认查询全部终端，跨页终端统计可见，点击移动端快捷筛选回第一页', async () => {
  const calls = []
  const view = onlinePage(async params => {
    calls.push(params)
    return { total: 25, userCount: 25, sessionCount: 27, records: [], pcUserCount: 24,
      mobileUserCount: 2, pcSessionCount: 25, mobileSessionCount: 2, queriedAt: 'now' }
  })
  await view.load()
  assert.equal(calls[0].client, undefined)
  assert.equal(view.terminalCounts.mobileUsers, 2)
  view.pageNum.value = 2; view.filterClient('MOBILE'); await Promise.resolve()
  assert.equal(calls[1].client, 'MOBILE'); assert.equal(calls[1].pageNum, 1)
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

test('强制下线明确全终端范围，确认后发送一次请求并刷新，取消不执行', async () => {
  const calls = []
  let confirm
  const view = onlinePage(async () => { calls.push('load'); return { total: 0, userCount: 0, records: [] } },
    { confirm: async message => { confirm = message }, kickout: async id => calls.push(id) })
  view.filters.client = 'MOBILE'
  await view.kickout({ userId: '42', username: 'test', nickName: '测试' })
  assert.match(confirm, /全部电脑端和移动端/); assert.match(confirm, /筛选未显示/)
  assert.deepEqual(calls, ['42', 'load'])
  assert.equal(view.kickingUserId.value, '')
  const cancelled = onlinePage(async () => { throw Error('不得查询') },
    { confirm: async () => { throw Error('cancel') }, kickout: async () => { throw Error('不得执行') } })
  await cancelled.kickout({ userId: '42' })
  assert.equal(cancelled.kickingUserId.value, '')
})

test('强制下线期间重复点击只请求一次，失败刷新核对且不重发', async () => {
  let finish, count = 0, reads = 0
  const view = onlinePage(async () => { reads++; return { total: 0, userCount: 0, records: [] } },
    { kickout: () => { count++; return new Promise((resolve, reject) => { finish = reject }) } })
  const pending = view.kickout({ userId: '42' }); await Promise.resolve()
  await view.kickout({ userId: '42' })
  assert.equal(count, 1)
  finish(Error('network')); await pending
  assert.equal(count, 1); assert.equal(reads, 1); assert.equal(view.kickingUserId.value, '')
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
