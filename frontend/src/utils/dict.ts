/**
 * 担保类型字典(V1.0 附录 A.3):后端/路由统一使用编码,页面展示中文
 * CREDIT信用/GUARANTEE保证/MORTGAGE抵押/PLEDGE质押/BILL_MARGIN银票保证金/CREDIT_MARGIN信用证保证金/CERTIFICATE_DEPOSIT存单质押
 */

export interface DictItem {
  code: string
  name: string
}

export const GUARANTEE_TYPES: DictItem[] = [
  { code: 'MORTGAGE', name: '抵押' },
  { code: 'PLEDGE', name: '质押' },
  { code: 'GUARANTEE', name: '保证' },
  { code: 'CREDIT', name: '信用' },
  { code: 'BILL_MARGIN', name: '银票保证金' },
  { code: 'CREDIT_MARGIN', name: '信用证保证金' },
  { code: 'CERTIFICATE_DEPOSIT', name: '存单质押' }
]

const NAME_MAP: Record<string, string> = Object.fromEntries(GUARANTEE_TYPES.map((t) => [t.code, t.name]))
// 兼容存量中文值(早期表单直存中文)
const LEGACY_MAP: Record<string, string> = { 抵押: 'MORTGAGE', 质押: 'PLEDGE', 保证: 'GUARANTEE', 信用: 'CREDIT' }

/** 编码→中文展示(中文原样返回,空值返回兜底) */
export function guaranteeTypeText(code?: string, fallback = '—'): string {
  if (!code) return fallback
  return NAME_MAP[code] || (LEGACY_MAP[code] ? NAME_MAP[LEGACY_MAP[code]] : code)
}

/** 中文/编码→标准编码(提交后端用) */
export function guaranteeTypeCode(v?: string): string {
  if (!v) return ''
  return LEGACY_MAP[v] || v
}
