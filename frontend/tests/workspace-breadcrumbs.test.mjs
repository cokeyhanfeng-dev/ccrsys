import test from 'node:test'
import assert from 'node:assert/strict'
import { buildWorkspaceBreadcrumbs } from '../src/utils/workspace-breadcrumbs.mjs'

test('首页不重复，系统页面展示系统目录，未知路由采用标题', () => {
  assert.deepEqual(buildWorkspaceBreadcrumbs({ path: '/overview' }, []), [{ title: '首页', path: '/overview' }])
  const menus = [{ path: '/system/online', title: '在线用户' }]
  assert.deepEqual(buildWorkspaceBreadcrumbs({ path: '/system/online' }, menus).map(x => x.title), ['首页', '系统管理', '在线用户'])
  assert.deepEqual(buildWorkspaceBreadcrumbs({ path: '/other', meta: { title: '其他页面' } }, menus).map(x => x.title), ['首页', '其他页面'])
})
test('档案目录沿用角色名称，详情保留返回列表路径，前缀匹配遵守路径边界', () => {
  const menus = [{ path: '/history', title: '历史审批' }]
  const items = buildWorkspaceBreadcrumbs({ path: '/history/archive/123', meta: { title: '申请档案' } }, menus)
  assert.deepEqual(items.map(x => x.title), ['首页', '历史审批', '申请档案'])
  assert.equal(items[1].path, '/history')
  assert.deepEqual(buildWorkspaceBreadcrumbs({ path: '/history-other', meta: { title: '其他' } }, menus).map(x => x.title), ['首页', '其他'])
  assert.deepEqual(buildWorkspaceBreadcrumbs({ path: '/application/loan' }, [{ path: '/application/loan', title: '贷款利率申请' }]).map(x => x.title), ['首页', '利率申请', '贷款利率申请'])
})
