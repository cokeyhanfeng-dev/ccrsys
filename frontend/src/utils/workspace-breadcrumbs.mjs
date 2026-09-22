/** 扁平路由按菜单前缀还原目录，历史名称沿用当前角色菜单。 */
export function buildWorkspaceBreadcrumbs(route, menus) {
  const items = [{ title: '首页', path: '/overview' }]
  if (route.path === '/overview') return items
  if (route.path.startsWith('/system/')) items.push({ title: '系统管理' })
  if (route.path.startsWith('/application/')) items.push({ title: '利率申请' })
  const menu = menus.filter(item => route.path === item.path || route.path.startsWith(item.path + '/'))
    .sort((a, b) => b.path.length - a.path.length)[0]
  if (menu) items.push({ title: menu.title, path: menu.path })
  if (!menu || route.path !== menu.path) items.push({ title: String(route.meta?.title || '页面') })
  return items
}
