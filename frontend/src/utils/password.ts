// 强密码规则:不少于8位,必须含大写字母、小写字母、特殊字符(非字母数字且非空白),不强制数字
// 与后端 PasswordUtil.STRONG 保持一致
export const PWD_REG = /^(?=.*[A-Z])(?=.*[a-z])(?=.*[^A-Za-z0-9\s]).{8,}$/

/** 逐步提示密码还缺什么;空/满足时返回空串 */
export function pwdHint(pw: string): string {
  if (!pw) return ''
  if (PWD_REG.test(pw)) return '✓ 密码强度符合要求'
  const tips: string[] = []
  if (pw.length < 8) tips.push(`长度 ${pw.length}/8`)
  if (!/[A-Z]/.test(pw)) tips.push('缺大写字母')
  if (!/[a-z]/.test(pw)) tips.push('缺小写字母')
  if (!/[^A-Za-z0-9\s]/.test(pw)) tips.push('缺特殊字符')
  return `密码需:${tips.join('、')}`
}
