import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { parse as parseSfc } from '@vue/compiler-sfc'
import { parse } from '@vue/compiler-dom'

function template(path) {
  return parse(parseSfc(readFileSync(new URL(path, import.meta.url), 'utf8')).descriptor.template.content)
}
function elements(node) {
  return [node, ...(node.children || []).flatMap(elements)].filter(node => node.type === 1)
}
function attr(node, name, value) {
  return node.props?.some(prop => prop.type === 6 && prop.name === name && prop.value?.content === value)
}
test('顶栏上排目录和用户操作，下排页签，无下拉箭头且内容缓存位于顶栏外', () => {
  const layout = template('../src/layout/index.vue')
  const workspace = template('../src/layout/WorkspaceTabs.vue')
  const headers = elements(workspace).filter(node => attr(node, 'class', 'workspace-header'))
  assert.equal(headers.length, 1)
  assert.ok(elements(headers[0]).some(node => attr(node, 'role', 'tablist')))
  const top = headers[0].children.find(node => attr(node, 'class', 'workspace-header__top'))
  assert.ok(top)
  for (const name of ['breadcrumb', 'actions']) {
    assert.ok(top.children.some(node => node.tag === 'slot' && attr(node, 'name', name)))
  }
  assert.ok(headers[0].children.some(node => attr(node, 'class', 'workspace-tabs')))
  assert.equal(elements(top).filter(node => attr(node, 'role', 'tablist')).length, 0)
  assert.equal(elements(workspace).filter(node => node.tag === 'el-dropdown').length, 0)
  assert.ok(workspace.children.some(node => node.tag === 'KeepAlive'))
  assert.equal(elements(headers[0]).filter(node => node.tag === 'KeepAlive').length, 0)
  assert.equal(elements(layout).filter(node => attr(node, 'class', 'topbar')).length, 0)
  const host = elements(layout).filter(node => node.tag === 'WorkspaceTabs')
  assert.equal(host.length, 1)
  assert.ok(elements(host[0]).some(node => attr(node, 'class', 'topbar__actions')))
})
