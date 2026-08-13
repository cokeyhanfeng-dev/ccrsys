import { get, post, request } from './request'

// 六人表决与行长决策接口封装(/ccr/vote-rounds、/ccr/vote-results、/ccr/president)
// 匿名口径:委员只提交/查询本人票,前端不调用 vote-results(仅行长/admin 可访问)

/** 委员待办:本人待表决的批次分项(无参,取登录人) */
export function listVoteTodo<T = any[]>(): Promise<T> {
  return get<T>('/ccr/vote-rounds/todo')
}

/** 本人选择与提交结果(只返回本人票型;未投返回 null) */
export function fetchMyBallot<T = any>(roundId: number | string, pricingItemId: number | string): Promise<T> {
  return get<T>(`/ccr/vote-rounds/${roundId}/ballots/my`, { pricingItemId })
}

/** 提交本人票:choice 仅 APPROVE/REJECT;pricingItemId 雪花 id 传字符串(避免 JS 精度丢失);Idempotency-Key 头可选 */
export function submitBallot(
  roundId: number | string,
  body: { pricingItemId: number | string; choice: string; comment?: string },
  idempotencyKey?: string
): Promise<void> {
  return request<void>({
    url: `/ccr/vote-rounds/${roundId}/ballots`,
    method: 'post',
    data: body,
    headers: idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined
  })
}

/** 分项计票结果(仅行长/admin 可访问,委员调用会 403) */
export function getVoteResult<T = any>(pricingItemId: number | string): Promise<T> {
  return get<T>(`/ccr/vote-results/${pricingItemId}`)
}

/** 行长待决策列表(仅行长/admin) */
export function listPresidentTodo<T = any[]>(): Promise<T> {
  return get<T>('/ccr/president/todo')
}

/** 行长决策:decision 仅 APPROVE/VETO,VETO 必填 opinion */
export function submitPresidentDecision(body: {
  pricingItemId: number
  decision: string
  opinion?: string
}): Promise<void> {
  return post<void>('/ccr/president/decisions', body)
}
