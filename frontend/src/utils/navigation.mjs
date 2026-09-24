/** 服务端已过滤授权；所有编号按字符串处理，保留雪花编号精度。 */
export function menuTree(rows, visibleOnly = false) {
  const index = new Map(rows.map(row => [String(row.id), { ...row, id: String(row.id), children: [] }]))
  const roots = []
  for (const item of index.values()) {
    const parent = index.get(String(item.parentId))
    if (parent) parent.children.push(item)
    else if (!item.parentId || String(item.parentId) === '0') roots.push(item)
  }
  function prune(nodes, ancestors = new Set()) {
    return nodes.filter(n => !ancestors.has(n.id) && (!visibleOnly || (n.visible !== 'HIDE' && n.status === 'ENABLE')))
      .map(n => ({ ...n, children: prune(n.children, new Set([...ancestors, n.id])) }))
      .filter(n => !visibleOnly || n.menuType !== 'M' || n.children.length)
      .sort((a,b) => a.sortNo-b.sortNo || a.id.localeCompare(b.id))
  }
  return prune(roots)
}
export function matchingMenu(path, rows) {
  const target = path === '/president' ? '/approval' : path
  return rows.filter(m => m.menuType === 'C' && m.path && (target === m.path || target.startsWith(m.path + '/')))
    .sort((a,b) => b.path.length-a.path.length)[0]
}
export function navigationTitle(menu, roles) {
  return menu.path === '/history' && menu.menuName === '历史' ? (roles.some(r => ['branch_manager','dept_gm','vice_president','secretary','committee_member','president'].includes(r)) ? '历史审批' : '历史申请') : menu.menuName
}
export function navigationBreadcrumbs(path, rows, roles, fallback) {
  const items = [{ title: '首页', path: '/overview' }]
  if(path === '/overview') return items
  const current = matchingMenu(path, rows)
  if (!current) return [...items, { title: fallback || '页面' }]
  const chain=[]; const seen=new Set(); let cursor=current
  while(cursor && !seen.has(String(cursor.id))) {
    seen.add(String(cursor.id)); chain.unshift({ title:navigationTitle(cursor,roles), path:cursor.menuType === 'C' ? cursor.path : undefined })
    cursor=rows.find(m => String(m.id)===String(cursor.parentId))
  }
  if(path!==current.path) chain.push({title:fallback || '详情'})
  return [...items,...chain]
}
