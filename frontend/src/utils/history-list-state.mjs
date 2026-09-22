// 仅在当前会话内暂存历史列表，离开列表/档案链路即清理，不写入浏览器持久存储。
let saved = null

export function saveHistoryList(session, state) {
  saved = session ? { session, state } : null
}

export function takeHistoryList(session, query = {}) {
  const previous = saved
  saved = null
  if (!session || previous?.session !== session) return null
  // 工作台携新条件进入时应执行新查询；档案返回列表没有 query。
  if (Object.keys(query).length && JSON.stringify(query) !== previous.state.queryKey) return null
  return previous.state
}

export function clearHistoryListOutside(path) {
  if (path !== '/history' && !path.startsWith('/history/archive/')) saved = null
}
