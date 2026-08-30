import { get } from './request'

// 贡献度跟踪(承诺)接口封装(W3-C)——v2 简化(docs/28):一张跟踪表三种读法
// 列表 TRACKING 行实时算完成度/终态行读定案;数据范围由服务端按登录人角色确定,前端不传 operatorId/roleCode

/** 承诺跟踪列表(orgId/managerId/customerNo/status 可选过滤;读前惰性结算) */
export const listCommitmentTracks = (params?: {
  orgId?: number
  managerId?: number
  customerNo?: string
  status?: string
}) => get<any[]>('/ccr/commitments/tracks', params || {})

/** 单条承诺跟踪详情(承诺要素 + 实时/定案信息 + 所属申请摘要) */
export const getCommitmentTrackDetail = (trackId: number | string) =>
  get<any>(`/ccr/commitments/tracks/${trackId}`)
