import test from 'node:test'
import assert from 'node:assert/strict'
import { saveHistoryList, takeHistoryList, clearHistoryListOutside } from '../src/utils/history-list-state.mjs'
import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import { transformSync } from 'esbuild'
import { parse, compileScript } from '@vue/compiler-sfc'

// 执行页面真实的查询、路由离开和挂载逻辑，接口使用计数桩。
function page(query = {}) {
  const descriptor = parse(readFileSync(new URL('../src/views/history/index.vue', import.meta.url), 'utf8')).descriptor
  const ast = compileScript(descriptor, { id: 'history-test' }).scriptSetupAst
  let source = descriptor.scriptSetup.content
  for (const node of [...ast].reverse()) {
    if (node.type === 'ImportDeclaration') source = source.slice(0, node.start) + source.slice(node.end)
  }
  let mounted, leave
  const calls = []
  const state = runInNewContext(transformSync(source, { loader: 'ts' }).code
    + '\n({filters,pageNum,records,total,load,onSearch,onReset,approvalNodes})', {
    ref: value => ({ value }), reactive: value => value,
    computed: getter => ({ get value() { return getter() } }), nextTick: async () => {},
    onMounted: callback => { mounted = callback }, onBeforeRouteLeave: callback => { leave = callback },
    useRoute: () => ({ query }), useRouter: () => ({}),
    useUserStore: () => ({ token: 'test-session', userInfo: { roles: ['customer_manager'] } }),
    pageHistory: async params => { calls.push(params); return { records: [{ id: '9001' }], total: 31 } },
    saveHistoryList, takeHistoryList,
    document: { querySelector: () => ({ scrollTop: 100 }) },
    window: { scrollY: 0, scrollTo() {} }
  })
  return { state, calls, mount: () => mounted(), leave: path => leave({ path }) }
}

test('列表与档案往返保留筛选、页码和原查询结果，恢复后消费缓存', () => {
  const state = { filters: { currentNodeCode: 'BRANCH_MANAGER', keyword: '测试', status: 'ROUTING' },
    pageNum: 3, records: [{ id: '9001' }], total: 25, queryKey: '{}', scrollTop: 250 }
  saveHistoryList('test-session', state)
  clearHistoryListOutside('/history/archive/9001')
  clearHistoryListOutside('/history/archive/9002')
  clearHistoryListOutside('/history')
  assert.equal(takeHistoryList('test-session'), state)
  assert.equal(takeHistoryList('test-session'), null)
})

test('换账号、退出或离开历史功能后不恢复旧数据', () => {
  saveHistoryList('session-a', { queryKey: '{}' })
  assert.equal(takeHistoryList('session-b'), null)
  for (const path of ['/login', '/overview', '/application/loan']) {
    saveHistoryList('session-a', { queryKey: '{}' })
    clearHistoryListOutside(path)
    assert.equal(takeHistoryList('session-a'), null)
  }
})

test('档案返回无查询参数仍恢复原条件，工作台携新条件进入则重新查询', () => {
  const state = { queryKey: JSON.stringify({ status: 'ROUTING' }) }
  saveHistoryList('session', state)
  assert.equal(takeHistoryList('session', {}), state)
  saveHistoryList('session', state)
  assert.equal(takeHistoryList('session', { status: 'FINAL' }), null)
})

test('实际页面岗位条件送到分页接口，重置清空岗位并回到第一页', async () => {
  clearHistoryListOutside('/overview')
  const view = page({ currentNodeCode: 'BRANCH_MANAGER' })
  await view.mount()
  await Promise.resolve()
  assert.equal(view.calls[0].currentNodeCode, 'BRANCH_MANAGER')
  assert.equal(view.state.approvalNodes.length, 7)
  view.state.pageNum.value = 3
  await view.state.load()
  assert.equal(view.calls[1].pageNum, 3)
  view.state.onReset()
  await Promise.resolve()
  assert.equal(view.calls[2].pageNum, 1)
  assert.equal(view.calls[2].currentNodeCode, undefined)
})

test('实际页面从档案返回恢复第三页且不再发送查询请求', async () => {
  clearHistoryListOutside('/overview')
  const before = page()
  await before.mount()
  await Promise.resolve()
  before.state.filters.currentNodeCode = 'SECRETARY'
  before.state.filters.keyword = '测试客户'
  before.state.pageNum.value = 3
  await before.state.load()
  before.leave('/history/archive/9001')
  const after = page()
  await after.mount()
  assert.equal(after.calls.length, 0)
  assert.equal(after.state.pageNum.value, 3)
  assert.equal(after.state.filters.currentNodeCode, 'SECRETARY')
  assert.equal(after.state.filters.keyword, '测试客户')
  assert.equal(after.state.records.value[0].id, '9001')
  assert.equal(after.state.total.value, 31)
})
