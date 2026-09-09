/** 精确提取一次 OA 回调票据，并在发出业务请求前清除地址栏中的凭证。 */
export function consumeOaCallback(location, history) {
  const url = new URL(location.href);
  const hashQuestion = url.hash.indexOf('?');
  const hashQuery = hashQuestion >= 0 ? new URLSearchParams(url.hash.slice(hashQuestion + 1)) : null;
  const names = ['ticket', 'oaToken'];
  const tickets = [url.searchParams, hashQuery].filter(Boolean)
    .flatMap(params => names.flatMap(name => params.getAll(name)));
  if (!tickets.length) return {present: false};
  for (const name of names) {
    url.searchParams.delete(name);
    hashQuery?.delete(name);
  }
  if (hashQuery) url.hash = url.hash.slice(0, hashQuestion) + (hashQuery.size ? '?' + hashQuery.toString() : '');
  history.replaceState(history.state, '', url.pathname + url.search + url.hash);
  if (tickets.length !== 1 || !tickets[0].trim() || tickets[0].length > 4096)
    return {present: true, error: 'OA 回调票据无效，请从 OA 工作台重新打开应用'};
  return {present: true, ticket: tickets[0]};
}
