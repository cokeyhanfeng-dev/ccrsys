import { get, post, request } from './request'

// 六人表决与行长决策接口封装(/ccr/vote-rounds、/ccr/vote-results、/ccr/president)
// 匿名口径:委员只提交/查询本人票,前端不调用 vote-results(仅行长/admin 可访问)

/** 委员待办:本人待表决的批次分项(无参,取登录人) */
export function listVoteTodo<T = any[]>(): Promise<T> {
  return get<T>('/ccr/vote-rounds/todo')
}

/** 本人选择与提交结果(只返回本人票型;未投返回 null;整单化按申请查询) */
export function fetchMyBallot<T = any>(roundId: number | string, applicationId: number | string): Promise<T> {
  return get<T>(`/ccr/vote-rounds/${roundId}/ballots/my`, { applicationId })
}

/** 提交本人票(整单交付改造:一批=一申请=整单票;choice 仅 APPROVE/REJECT);Idempotency-Key 头可选 */
export function submitBallot(
  roundId: number | string,
  body: { applicationId: number | string; choice: string; comment?: string },
  idempotencyKey?: string
): Promise<void> {
  return request<void>({
    url: `/ccr/vote-rounds/${roundId}/ballots`,
    method: 'post',
    data: body,
    headers: idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined
  })
}

/** 整单计票结果(仅行长/admin 可访问,委员调用会 403;整单化按申请查询) */
export function getVoteResult<T = any>(applicationId: number | string): Promise<T> {
  return get<T>(`/ccr/vote-results/${applicationId}`)
}

/** 行长待决策列表(仅行长/admin) */
export function listPresidentTodo<T = any[]>(): Promise<T> {
  return get<T>('/ccr/president/todo')
}

/** 行长决策(整单):按申请一并决策,decision 仅 APPROVE/VETO,VETO 必填 opinion */
export function submitPresidentDecision(body: {
  applicationId: number | string
  decision: string
  opinion?: string
}): Promise<void> {
  return post<void>('/ccr/president/decisions', body)
}
