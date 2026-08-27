/**
 * 展示层通用格式化工具。
 *
 * 全系统金额/日期/文件大小的统一格式化入口,替换各页面手写
 * `String(t).replace('T',' ')`、`toLocaleString()`、`toFixed()` 等散落实现。
 * 规则:
 *  - 金额一律千分位,小数最多 2 位(整数不补尾零);空值返回 fallback(默认 —)
 *  - 单位不内嵌函数,由调用方在标签内标注(如 `(万元)`),避免「值后拼单位/无单位」三套并存
 *  - 文件大小按 B/KB/MB 分级自适应
 */

/** 空值判断:null / undefined / 空串 / NaN 均为空 */
function isEmpty(v: unknown): boolean {
  return v == null || v === '' || (typeof v === 'number' && Number.isNaN(v))
}

/**
 * 金额千分位。默认最多保留 2 位小数(整数不补 .00);digits 传入 0/1/2 时固定小数位。
 * 例:fmtAmount(5000000) => '5,000,000';fmtAmount(3.5) => '3.5';fmtAmount(3.5, 2) => '3.50'
 */
export function fmtAmount(v: unknown, digits?: number, fallback = '—'): string {
  if (isEmpty(v)) return fallback
  const n = Number(v)
  if (!Number.isFinite(n)) return fallback
  const opts: Intl.NumberFormatOptions = { maximumFractionDigits: 2 }
  if (digits !== undefined) {
    opts.minimumFractionDigits = digits
    opts.maximumFractionDigits = digits
  }
  return n.toLocaleString('zh-CN', opts)
}

/** 金额(万元口径)千分位,与 fmtAmount 相同;单位由调用方在标签内标注 */
export function fmtAmountWan(v: unknown, digits?: number, fallback = '—'): string {
  return fmtAmount(v, digits, fallback)
}

/**
 * 文件大小自适应:按 B/KB/MB/GB 分级,保留 1 位小数(≥100 的整数不带小数)。
 * 例:fmtSize(512) => '512 B';fmtSize(2048) => '2 KB';fmtSize(1048576) => '1 MB'
 */
export function fmtSize(bytes: unknown, fallback = '—'): string {
  if (isEmpty(bytes)) return fallback
  const b = Number(bytes)
  if (!Number.isFinite(b) || b < 0) return fallback
  if (b < 1024) return `${Math.round(b)} B`
  const units = ['KB', 'MB', 'GB']
  let v = b
  let u = ''
  for (const unit of units) {
    v /= 1024
    u = unit
    if (v < 1024) break
  }
  const s = v >= 100 ? Math.round(v).toString() : v.toFixed(1)
  return `${s} ${u}`
}

/**
 * 日期时间展示:ISO 串/时间戳/Date 统一转 `YYYY-MM-DD HH:mm:ss`(截掉秒则传 withSeconds=false)。
 * 空值返回 fallback;已是非标准格式的串原样返回。
 */
export function fmtDateTime(v: unknown, withSeconds = true, fallback = '—'): string {
  if (isEmpty(v)) return fallback
  if (typeof v === 'string') {
    // 已是非 ISO 的展示串(如 '2026-08-26 09:30')直接返回
    if (!v.includes('T')) return v
    const d = new Date(v)
    if (Number.isNaN(d.getTime())) return v
    return fmtDateObj(d, withSeconds)
  }
  const d = v instanceof Date ? v : new Date(Number(v))
  if (Number.isNaN(d.getTime())) return fallback
  return fmtDateObj(d, withSeconds)
}

/** 仅日期:YYYY-MM-DD */
export function fmtDate(v: unknown, fallback = '—'): string {
  if (isEmpty(v)) return fallback
  const d = new Date(v)
  if (Number.isNaN(d.getTime())) return typeof v === 'string' ? v : fallback
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

function fmtDateObj(d: Date, withSeconds: boolean): string {
  const p = (n: number) => String(n).padStart(2, '0')
  const base = `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
  return withSeconds ? `${base}:${p(d.getSeconds())}` : base
}

/** 利率展示:数值保留固定小数位并带 %,空值返回 fallback */
export function fmtRate(v: unknown, digits = 2, fallback = '—'): string {
  if (isEmpty(v)) return fallback
  const n = Number(v)
  if (!Number.isFinite(n)) return String(v)
  return `${n.toFixed(digits)}%`
}
