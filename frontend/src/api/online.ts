import { get, post } from './request'

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
export interface OnlineUser {
  userId: string
  username: string
  nickName: string
  orgName?: string
  roleName?: string
  clients: ('PC' | 'MOBILE')[]
  sessionCount: number
  lastLoginTime?: string
  lastAccessTime?: string
  sessions: OnlineSession[]
}
export interface OnlineResult {
  total: number
  userCount: number
  sessionCount: number
  pcUserCount: number
  mobileUserCount: number
  pcSessionCount: number
  mobileSessionCount: number
  records: OnlineUser[]
  queriedAt: string
}
export const listOnlineUsers = (params: {
  pageNum: number; pageSize: number; keyword?: string; orgId?: number; client?: string
}) => get<OnlineResult>('/system/online-users', params)

// 账号级操作：同时注销该账号全部电脑端和移动端会话。
export const kickoutOnlineUser = (userId: string) => post<void>(`/system/online-users/${userId}/kickout`)
