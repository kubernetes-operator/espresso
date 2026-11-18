export const LR_STATUS: Record<string, string> = {
  DRAFT: '작성 중', REQUESTED: '요청', STORE_APPROVED: '매장 승인', LOGISTICS_APPROVED: '물류 승인', PURCHASED: '구매 완료',
  PREPARING: '준비 중', SHIPPED: '발송', DELIVERED: '도착', PARTIALLY_RECEIVED: '부분 수령', RECEIVED: '수령', COMPLETED: '완료',
  REJECTED: '반려', CANCELLED: '취소',
}
export const LR_TONE: Record<string, any> = {
  DRAFT: 'neutral', REQUESTED: 'warn', STORE_APPROVED: 'warn', LOGISTICS_APPROVED: 'info', PURCHASED: 'info', PREPARING: 'info',
  SHIPPED: 'accent', DELIVERED: 'accent', PARTIALLY_RECEIVED: 'warn', RECEIVED: 'ok', COMPLETED: 'ok', REJECTED: 'bad', CANCELLED: 'neutral',
}
