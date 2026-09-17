/** 精确提取一次 OA 回调票据，并在发出业务请求前清除地址栏中的凭证。 */
export function consumeOaCallback(location, history) {
  const url = new URL(location.href);
  const hashQuestion = url.hash.indexOf('?');
  const hashQuery = hashQuestion >= 0 ? new URLSearchParams(url.hash.slice(hashQuestion + 1)) : null;
  const names = ['ticket', 'oaToken', 'token'];
  const paramsList=[url.searchParams,hashQuery].filter(Boolean);
  const credentials=paramsList.flatMap(params=>names.flatMap(name=>params.getAll(name).map(value=>({name,value}))));
  const tickets=credentials.map(entry=>entry.value);
  const sources=paramsList.flatMap(params=>params.getAll('source'));
  const isToken=credentials.some(entry=>entry.name==='token');
  if (!tickets.length) return {present: false};
  for (const name of names) {
    url.searchParams.delete(name);
    hashQuery?.delete(name);
  }
  if(isToken){url.searchParams.delete('source');hashQuery?.delete('source');}
  if (hashQuery) url.hash = url.hash.slice(0, hashQuestion) + (hashQuery.size ? '?' + hashQuery.toString() : '');
  history.replaceState(history.state, '', url.pathname + url.search + url.hash);
  if (tickets.length !== 1 || !tickets[0].trim() || tickets[0].length > 4096 || (isToken&&(sources.length>1||(sources.length===1&&sources[0]!=='oa')||/[\s\x00-\x1f\x7f]/.test(tickets[0]))))
    return {present: true, error: 'OA 回调票据无效，请从 OA 工作台重新打开应用'};
  return isToken?{present:true,token:tickets[0]}:{present: true, ticket: tickets[0]};
}

/** URL 中的系统令牌必须经 CCR 会话接口校验；失败清除临时会话。 */
export async function restoreOaToken(token,{session,request}){
  session.clear();
  session.set(token,'oa');
  try{return await request('/session');}
  catch(error){session.clear();throw error;}
}
