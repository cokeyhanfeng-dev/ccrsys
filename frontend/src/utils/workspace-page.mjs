import { defineComponent, h, markRaw, provide } from 'vue'
import { RouterView, routeLocationKey } from 'vue-router'

/** 每个缓存页面独立提供路由，后台表单不会读到其他标签的申请号和查询条件。 */
export function createWorkspacePage(location, name) {
  const snapshot = { ...location, params: { ...location.params }, query: { ...location.query }, meta: { ...location.meta } }
  return markRaw(defineComponent({
    name,
    setup() {
      provide(routeLocationKey, snapshot)
      return () => h('div', { class: 'page-shell' }, [h(RouterView, { route: snapshot })])
    }
  }))
}
