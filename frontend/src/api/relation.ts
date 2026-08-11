import { get, post } from './request'

// 关联人唯一绑定接口封装(§6.2/§10.3.21/§11.2)

/** 判重查询(全行判重):返回是否已绑定及绑定对象(certType: USCC 对公/ID_CARD 对私) */
export const checkRelation = (certType: string, certNo: string) =>
  get<{ bound: boolean; boundCustomerNo?: string; boundGroupNo?: string; relationName?: string }>(
    '/ccr/relations/check', { certType, certNo })

/** 绑定(录入即绑定;同客户/集团幂等,已绑定其他目标由后端拒绝留痕) */
export const bindRelation = (body: {
  certType: string
  certNo: string
  relationName?: string
  relationType?: string
  customerNo?: string
  groupNo?: string
  applicationId?: number
}) => post<{ created: boolean; id: number }>('/ccr/relations/bind', body)

/** 申请已绑定关联人历史(重提/编辑回显参考,§11.2 application/{id}/relations) */
export const listApplicationRelations = (applicationId: number) =>
  get<any[]>('/ccr/relations/application/' + applicationId)
