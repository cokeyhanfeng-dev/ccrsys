/** 标签使用完整地址区分，编辑不同申请和不同查询条件不会共用实例。 */
export function openWorkspaceTab(tabs, route, createPage) {
  const existing = tabs.find(tab => tab.key === route.fullPath)
  if (existing) return existing
  const tab = {
    key: route.fullPath,
    path: route.path,
    title: String(route.meta?.title || '页面'),
    pinned: route.path === '/overview' && !Object.keys(route.query || {}).length,
    scrollY: 0,
    page: createPage(route)
  }
  const identity = route.params?.id || route.query?.edit || route.query?.reapply
  if (identity) tab.title += ' · ' + identity
  tabs.push(tab)
  return tab
}

/** 批量关闭以菜单所属标签为参照；固定工作台始终保留。 */
export function planTabClose(tabs, activeKey, targetKey, action) {
  const index = tabs.findIndex(tab => tab.key === targetKey)
  if (index < 0) return { tabs, activeKey }
  const remaining = tabs.filter((tab, position) => {
    if (tab.pinned) return true
    if (action === 'left') return position >= index
    if (action === 'right') return position <= index
    if (action === 'others') return position === index
    return position !== index
  })
  const active = remaining.find(tab => tab.key === activeKey)
    || remaining.find(tab => tab.key === targetKey)
    || remaining[Math.min(index, remaining.length - 1)]
  return { tabs: remaining, activeKey: active?.key || '/overview' }
}
