import { get } from './request'

export interface OnlineSession {
  userId: string
  username: string
  nickName: string
  orgName?: string
  roleName?: string
  client: 'PC' | 'MOBILE'
  loginTime?: string
  lastAccessTime?: string
  loginIp?: string
}
export interface OnlineResult {
  total: number
  userCount: number
  records: OnlineSession[]
  queriedAt: string
}
export const listOnlineUsers = (params: {
  pageNum: number; pageSize: number; keyword?: string; orgId?: number; client?: string
}) => get<OnlineResult>('/system/online-users', params)
