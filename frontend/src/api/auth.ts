import { post } from '@/api/request'

/** 修改密码(需登录;首次登录强制改密入口) */
export function changePassword(oldPassword: string, newPassword: string): Promise<void> {
  return post('/auth/change-password', { oldPassword, newPassword })
}
