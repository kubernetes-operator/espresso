// 금액·날짜 표기 규칙 (설계 §7.2, SCREENS.md)
export const won = (n: number | null | undefined) =>
  n === null || n === undefined ? '-' : Math.round(Number(n)).toLocaleString('ko-KR')

export function dt(v?: string | null) {
  if (!v) return '—'
  const d = new Date(v)
  if (isNaN(d.getTime())) return v
  return d.toLocaleString('ko-KR', { timeZone: 'Asia/Seoul', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false })
}
export function day(v?: string | null) {
  if (!v) return '—'
  return String(v).slice(0, 10)
}

export type VatMode = 'EXCLUSIVE' | 'INCLUSIVE'

/** 표시 금액: 별도 모드 = 공급가, 포함 모드 = 공급가+세액 */
export function shown(supply: number, vat: number, mode: VatMode) {
  return mode === 'INCLUSIVE' ? supply + vat : supply
}
export function vatText(vat: number, taxType?: string) {
  return taxType === 'EXEMPT' ? '0 (면세)' : won(vat)
}

export const ROLE_NAMES: Record<string, string> = {
  HQ_ADMIN: '총괄 관리자', LOGISTICS_MGR: '물류 관리자', HR_MGR: '인적 관리자', SUPERVISOR: '슈퍼바이저',
  OWNER: '점주', STORE_MGR: '매장 관리자', STAFF: '일반 사원', INVESTOR: '투자자',
}

/** 물류 구분 (요구 28: 음료 / 디저트 / 식기·포장 / 관리) */
export const DIVISIONS: Record<string, string> = { BEVERAGE: '음료', DESSERT: '디저트', TABLEWARE: '식기·포장', MANAGEMENT: '관리' }
