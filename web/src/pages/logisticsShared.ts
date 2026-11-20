export const LR_STATUS: Record<string, string> = {
  DRAFT: '작성 중', REQUESTED: '요청', STORE_APPROVED: '매장 승인', LOGISTICS_APPROVED: '물류 승인', PURCHASED: '구매 완료',
  PREPARING: '준비 중', SHIPPED: '발송', DELIVERED: '도착', PARTIALLY_RECEIVED: '부분 수령', RECEIVED: '수령', COMPLETED: '완료',
  REJECTED: '반려', CANCELLED: '취소',
}
export const LR_TONE: Record<string, any> = {
  DRAFT: 'neutral', REQUESTED: 'warn', STORE_APPROVED: 'warn', LOGISTICS_APPROVED: 'info', PURCHASED: 'info', PREPARING: 'info',
  SHIPPED: 'accent', DELIVERED: 'accent', PARTIALLY_RECEIVED: 'warn', RECEIVED: 'ok', COMPLETED: 'ok', REJECTED: 'bad', CANCELLED: 'neutral',
}

const OPEN_ST = ['DRAFT', 'REQUESTED', 'STORE_APPROVED', 'LOGISTICS_APPROVED', 'PURCHASED', 'PREPARING', 'SHIPPED', 'DELIVERED']
/** 처리 기한(없으면 요청 기한)이 지났고 아직 진행 중인지 */
export function overdue(r: any) {
  const due = r.due_date ?? r.desired_date
  return !!due && OPEN_ST.includes(r.status) && String(due).slice(0, 10) < new Date(Date.now() + 9 * 3600e3).toISOString().slice(0, 10)
}
export const dueText = (r: any) => r.due_date ? `기한 ${String(r.due_date).slice(5, 10)}` : r.desired_date ? `요청 기한 ${String(r.desired_date).slice(5, 10)}` : ''
