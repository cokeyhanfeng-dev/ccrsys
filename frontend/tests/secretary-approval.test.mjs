import test from 'node:test'
import { menuTree, navigationTitle } from '../src/utils/navigation.mjs'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import { parse, compileScript } from '@vue/compiler-sfc'

// 执行实际页面中的角色条件，避免测试另写一套允许名单。
function declarations(path, names, roles) {
  const source = readFileSync(new URL(path, import.meta.url), 'utf8')
  const { descriptor } = parse(source)
  const script = compileScript(descriptor, { id: 'secretary-regression' })
  const selected = script.scriptSetupAst
    .filter(node => node.type === 'VariableDeclaration')
    .flatMap(node => node.declarations)
    .filter(node => names.includes(node.id.name))
  assert.equal(selected.length, names.length)
  const code = selected.map(node =>
    `const ${node.id.name} = ${descriptor.scriptSetup.content.slice(node.init.start, node.init.end)};`
  ).join('\n')
  return runInNewContext(`${code}\n({${names.join(',')}})`, {
    userStore: { userInfo: { roles } },
    computed: getter => ({ get value() { return getter() } })
  })
}

test('秘书服务端授权菜单能显示审批入口和历史审批名称', () => {
  const menus = menuTree([
    {id:'10',parentId:'0',menuType:'C',path:'/approval',menuName:'利率审批',status:'ENABLE',visible:'SHOW'},
    {id:'5',parentId:'0',menuType:'C',path:'/history',menuName:'历史',status:'ENABLE',visible:'SHOW'}
  ], true)
  assert.ok(menus.some(menu => menu.path === '/approval'))
  assert.equal(navigationTitle(menus.find(menu => menu.path === '/history'), ['secretary']), '历史审批')
})

test('秘书工作台满足待办加载和待我审批统计的角色条件', () => {
  const state = declarations('../src/views/overview/index.vue', ['APPROVAL_ROLES'], ['secretary'])
  assert.ok(state.APPROVAL_ROLES.includes('secretary'))
  for (const role of ['customer_manager', 'auditor', 'committee_member']) {
    assert.equal(state.APPROVAL_ROLES.includes(role), false)
  }
})

test('纯秘书及兼岗秘书请求普通审批待办，委员兼岗同时保留表决待办', () => {
  for (const roles of [['secretary'], ['dept_gm', 'secretary'], ['committee_member', 'secretary']]) {
    const state = declarations('../src/views/approval/index.vue', ['isApprovalRole', 'isCommitteeMember'], roles)
    assert.equal(state.isApprovalRole.value, true)
    assert.equal(state.isCommitteeMember.value, roles.includes('committee_member'))
  }
})

test('普通审批原有角色保持可见，未登录和非审批角色不加载普通待办', () => {
  for (const role of ['branch_manager', 'dept_gm', 'vice_president']) {
    assert.equal(declarations('../src/views/approval/index.vue', ['isApprovalRole'], [role]).isApprovalRole.value, true)
  }
  for (const roles of [[], ['customer_manager'], ['auditor'], ['committee_member']]) {
    assert.equal(declarations('../src/views/approval/index.vue', ['isApprovalRole'], roles).isApprovalRole.value, false)
  }
})
