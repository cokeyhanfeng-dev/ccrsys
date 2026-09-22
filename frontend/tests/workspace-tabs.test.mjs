import test from 'node:test'
import assert from 'node:assert/strict'
import { openWorkspaceTab, planTabClose } from '../src/utils/workspace-tabs.mjs'

function fixture() {
  const tabs = []
  for (const path of ['/overview', '/history', '/approval', '/application/loan']) {
    openWorkspaceTab(tabs, { fullPath: path, path, meta: { title: path } }, () => ({}))
  }
  return tabs
}
test('打开新页保留旧页，同一地址只保留一个标签及其实例', () => {
  const tabs = fixture()
  const page = tabs[1].page
  openWorkspaceTab(tabs, { fullPath: '/history' }, () => { throw Error('不得重建') })
  assert.equal(tabs.length, 4)
  assert.equal(tabs[1].page, page)
})
test('不同申请参数和查询条件各自打开', () => {
  const tabs = fixture()
  for (const fullPath of ['/application/loan?edit=1', '/application/loan?edit=2']) {
    openWorkspaceTab(tabs, { fullPath, path: '/application/loan', query: { edit: fullPath.slice(-1) } }, () => ({}))
  }
  assert.equal(tabs.length, 6)
  assert.notEqual(tabs[4].title, tabs[5].title)
})
test('关闭左侧以菜单标签为准，固定工作台保留', () => {
  const plan = planTabClose(fixture(), '/history', '/approval', 'left')
  assert.deepEqual(plan.tabs.map(t => t.key), ['/overview', '/approval', '/application/loan'])
  assert.equal(plan.activeKey, '/approval')
})
test('关闭右侧不改变仍存在的当前页', () => {
  const plan = planTabClose(fixture(), '/history', '/approval', 'right')
  assert.equal(plan.activeKey, '/history')
  assert.equal(plan.tabs.length, 3)
})
test('关闭其他保留操作对象和工作台', () => {
  const plan = planTabClose(fixture(), '/application/loan', '/history', 'others')
  assert.deepEqual(plan.tabs.map(t => t.key), ['/overview', '/history'])
  assert.equal(plan.activeKey, '/history')
})
test('关闭当前选择相邻页，工作台不可关闭', () => {
  assert.equal(planTabClose(fixture(), '/history', '/history', 'close').activeKey, '/approval')
  assert.equal(planTabClose(fixture(), '/application/loan', '/application/loan', 'close').activeKey, '/approval')
  assert.equal(planTabClose(fixture(), '/overview', '/overview', 'close').tabs.length, 4)
})
test('失效菜单不删除页面', () => {
  const tabs = fixture()
  assert.equal(planTabClose(tabs, '/history', '/missing', 'others').tabs, tabs)
})
