import { get, post } from './request'

// 系统管理/参数管理接口封装(W3-C)
// 注意:/system/** 后端仅 admin 角色可访问(非 admin 403,由拦截器统一提示)

// ---------- 用户/角色 ----------
export interface SysRole {
  id: number
  roleCode: string
  roleName: string
  menuIds?: string
  remark?: string
  status?: string
}

export const listRoles = () => get<SysRole[]>('/system/roles')

// ---------- LPR 阈值(版本生命周期:DRAFT→REVIEW→EFFECTIVE→INVALID,发布双人复核) ----------
export const listLpr = (status?: string) =>
  get<any[]>('/system/flow/thresholds/lpr', status ? { status } : {})
export const createLpr = (data: object) => post<number>('/system/flow/thresholds/lpr', data)
export const submitLpr = (id: number) => post(`/system/flow/thresholds/lpr/${id}/submit`)
export const publishLpr = (id: number) => post(`/system/flow/thresholds/lpr/${id}/publish`)
export const disableLpr = (id: number) => post(`/system/flow/thresholds/lpr/${id}/disable`)

// ---------- 权限矩阵阈值(同构生命周期) ----------
export const listMatrix = (status?: string) =>
  get<any[]>('/system/flow/thresholds/matrix', status ? { status } : {})
export const createMatrix = (data: object) => post<number>('/system/flow/thresholds/matrix', data)
export const submitMatrix = (id: number) => post(`/system/flow/thresholds/matrix/${id}/submit`)
export const publishMatrix = (id: number) => post(`/system/flow/thresholds/matrix/${id}/publish`)
export const disableMatrix = (id: number) => post(`/system/flow/thresholds/matrix/${id}/disable`)

// ---------- 利率规则集(同构生命周期;发布前连续性校验) ----------
export const listRuleSets = (status?: string) =>
  get<any[]>('/ccr/rule/set/list', status ? { status } : {})
export const createRuleSet = (data: object) => post<number>('/ccr/rule/set', data)
export const submitRuleSet = (id: number) => post(`/ccr/rule/set/${id}/submit`)
export const publishRuleSet = (id: number) => post(`/ccr/rule/set/${id}/publish`)
export const disableRuleSet = (id: number) => post(`/ccr/rule/set/${id}/disable`)

// ---------- 权限矩阵路由试算 ----------
export const matrixRoute = (data: object) => post<any>('/ccr/rule/matrix-route', data)
