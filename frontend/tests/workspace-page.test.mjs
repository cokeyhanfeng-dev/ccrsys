import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer, defineComponent, h, KeepAlive, nextTick, onUnmounted, shallowRef } from 'vue'
import { createRouter, createMemoryHistory, RouterView, useRoute } from 'vue-router'
import { createWorkspacePage } from '../src/utils/workspace-page.mjs'

// 内存渲染器运行真实 Vue KeepAlive/RouterView 生命周期，无浏览器或后端依赖。
const renderer = createRenderer({
  createElement: tag => ({ tag, children: [] }), createText: text => ({ text }),
  createComment: text => ({ text }), setText: (node, text) => { node.text = text },
  setElementText: (node, text) => { node.text = text }, patchProp() {},
  parentNode: node => node.parent, nextSibling: () => null,
  insert(node, parent, anchor) {
    if (node.parent) { const i = node.parent.children.indexOf(node); if (i >= 0) node.parent.children.splice(i, 1) }
    node.parent = parent
    const index = anchor ? parent.children.indexOf(anchor) : -1
    if (index < 0) parent.children.push(node); else parent.children.splice(index, 0, node)
  },
  remove(node) { const index = node.parent?.children.indexOf(node); if (index >= 0) node.parent.children.splice(index, 1) }
})

test('真实路由缓存保留表单、隔离参数，刷新及关闭只销毁指定标签', async () => {
  const instances = []
  const pages = shallowRef([])
  const current = shallowRef(null)
  const Page = defineComponent({
    setup() {
      const route = useRoute()
      const state = { value: '', route, destroyed: false }
      instances.push(state)
      onUnmounted(() => { state.destroyed = true })
      return () => h('p', state.value + ':' + route.params.id)
    }
  })
  const Layout = defineComponent({
    setup() {
      return () => h(KeepAlive, { include: pages.value.map(page => page.name) },
        { default: () => current.value ? h(current.value, { key: current.value.name }) : null })
    }
  })
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/', component: Layout, children: [{ path: 'item/:id', component: Page }] }
  ] })
  await router.push('/item/1')
  const first = createWorkspacePage(router.currentRoute.value, 'TestTab1')
  pages.value = [first]; current.value = first
  const app = renderer.createApp({ render: () => h(RouterView) })
  app.use(router); app.mount({ children: [] })
  await nextTick()
  instances[0].value = '未保存内容'
  await router.push('/item/2')
  const second = createWorkspacePage(router.currentRoute.value, 'TestTab2')
  pages.value = [first, second]; current.value = second
  await nextTick()
  assert.equal(instances.length, 2)
  assert.equal(instances[0].route.params.id, '1')
  assert.equal(instances[1].route.params.id, '2')
  assert.equal(instances[0].destroyed, false)
  await router.push('/item/1'); current.value = first
  await nextTick()
  assert.equal(instances.length, 2)
  assert.equal(instances[0].value, '未保存内容')
  const refreshed = createWorkspacePage(router.currentRoute.value, 'TestTab1Refresh')
  pages.value = [refreshed, second]; current.value = refreshed
  await nextTick(); await nextTick()
  assert.equal(instances[0].destroyed, true)
  assert.equal(instances[1].destroyed, false)
  assert.equal(instances[2].value, '')
  pages.value = [refreshed]
  await nextTick(); await nextTick()
  assert.equal(instances[1].destroyed, true)
  app.unmount()
})
