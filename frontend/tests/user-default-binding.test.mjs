import test from 'node:test'
import assert from 'node:assert/strict'
import { syncUserDefaultBinding, syncUserDefaultForm } from '../src/utils/user-default-binding.mjs'

test('改选基本信息后默认机构岗位同步，附加绑定保留', () => {
  const form = { orgId: 253, roleCode: 'secretary' }
  const extra = { orgId: 50, postCode: 'dept_gm', isDefault: '0' }
  const rows = [extra, { orgId: 1, postCode: 'customer_manager', isDefault: '1' }]
  syncUserDefaultBinding(form, rows)
  assert.deepEqual(rows, [extra, { orgId: 253, postCode: 'secretary', isDefault: '1' }])
  assert.deepEqual(extra, { orgId: 50, postCode: 'dept_gm', isDefault: '0' })
  syncUserDefaultBinding(form, rows)
  assert.equal(rows.length, 2)
})

test('切换默认组合及编辑默认行后回填上方，缺少默认行时交由原校验处理', () => {
  const form = { orgId: 1, roleCode: 'customer_manager' }
  const rows = [{ orgId: 2, postCode: 'branch_manager', isDefault: '1' }]
  syncUserDefaultForm(form, rows)
  assert.deepEqual(form, { orgId: 2, roleCode: 'branch_manager' })
  rows[0].postCode = 'secretary'
  syncUserDefaultForm(form, rows)
  assert.equal(form.roleCode, 'secretary')
  syncUserDefaultForm(form, [])
  syncUserDefaultBinding(form, [])
  assert.deepEqual(form, { orgId: 2, roleCode: 'secretary' })
})
