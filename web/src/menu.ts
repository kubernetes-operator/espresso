// 등급별 메뉴 (설계 §2.2 권한 매트릭스). stage = 구현 단계(PLAN.md), 미구현 메뉴는 안내 화면
export type MenuItem = { path: string; label: string; icon: string; roles: string[] | 'ALL'; group: string; mobileTab?: 'home' | 'request' | 'approve' | 'check' | 'more' }

const STORE_SIDE = ['OWNER', 'STORE_MGR', 'STAFF']

export const MENU: MenuItem[] = [
  { path: '/', label: '홈', icon: '⌂', roles: 'ALL', group: '일반', mobileTab: 'home' },
  { path: '/logistics', label: '물류 요청', icon: '▤', roles: ['HQ_ADMIN', 'LOGISTICS_MGR', ...STORE_SIDE], group: '물류', mobileTab: 'request' },
  { path: '/logistics/board', label: '물류 처리', icon: '⇄', roles: ['HQ_ADMIN', 'LOGISTICS_MGR'], group: '물류' },
  { path: '/inventory', label: '재고', icon: '▦', roles: ['HQ_ADMIN', 'LOGISTICS_MGR', ...STORE_SIDE], group: '물류', mobileTab: 'check' },
  { path: '/adjustments', label: '손실·결손·사용', icon: '−', roles: ['HQ_ADMIN', 'LOGISTICS_MGR', ...STORE_SIDE], group: '물류', mobileTab: 'request' },
  { path: '/approvals', label: '결재함', icon: '✓', roles: ['HQ_ADMIN', 'LOGISTICS_MGR', 'HR_MGR', 'OWNER', 'STORE_MGR'], group: '일반', mobileTab: 'approve' },
  { path: '/sales', label: '매출', icon: '₩', roles: ['HQ_ADMIN', 'OWNER', 'STORE_MGR'], group: '회계' },
  { path: '/expenses', label: '비용', icon: '≡', roles: ['HQ_ADMIN', 'OWNER', 'STORE_MGR'], group: '회계' },
  { path: '/settlement', label: '결산', icon: 'Σ', roles: ['HQ_ADMIN', 'OWNER', 'STORE_MGR'], group: '회계' },
  { path: '/statements', label: '본사 정산서', icon: '⎙', roles: ['HQ_ADMIN', 'OWNER'], group: '회계' },
  { path: '/tax', label: '세무', icon: '§', roles: ['HQ_ADMIN'], group: '회계' },
  { path: '/hr', label: '직원·근무', icon: '☺', roles: ['HQ_ADMIN', 'HR_MGR', 'OWNER', 'STORE_MGR'], group: '인사' },
  { path: '/payroll', label: '급여', icon: '¤', roles: ['HQ_ADMIN', 'HR_MGR', 'OWNER'], group: '인사' },
  { path: '/my', label: '내 근무·급여', icon: '◔', roles: ['STAFF', 'STORE_MGR', 'OWNER', 'HR_MGR', 'LOGISTICS_MGR', 'HQ_ADMIN'], group: '인사', mobileTab: 'more' },
  { path: '/reports', label: '리포트', icon: '▧', roles: ['HQ_ADMIN', 'LOGISTICS_MGR', 'HR_MGR', 'OWNER', 'STORE_MGR', 'STAFF'], group: '분석', mobileTab: 'check' },
  { path: '/compare', label: '매장 비교', icon: '⫼', roles: ['HQ_ADMIN', 'LOGISTICS_MGR', 'HR_MGR', 'OWNER'], group: '분석' },
  { path: '/investment', label: '투자 관리', icon: '◆', roles: ['HQ_ADMIN'], group: '분석' },
  { path: '/investor', label: '투자 현황', icon: '◇', roles: ['INVESTOR'], group: '분석', mobileTab: 'home' },
  { path: '/org', label: '조직도', icon: '⌬', roles: 'ALL', group: '안내', mobileTab: 'more' },
  { path: '/blueprint', label: '설계도', icon: '⌗', roles: 'ALL', group: '안내', mobileTab: 'more' },
  { path: '/admin/stores', label: '매장 관리', icon: '⌂', roles: ['HQ_ADMIN'], group: '설정' },
  { path: '/admin/users', label: '계정·등급', icon: '☰', roles: ['HQ_ADMIN', 'HR_MGR'], group: '설정' },
  { path: '/admin/catalog', label: '품목·단가', icon: '▣', roles: ['HQ_ADMIN', 'LOGISTICS_MGR'], group: '설정' },
  { path: '/admin/royalty', label: '로열티', icon: '%', roles: ['HQ_ADMIN'], group: '설정' },
  { path: '/admin/approval-lines', label: '결재선', icon: '↧', roles: ['HQ_ADMIN'], group: '설정' },
  { path: '/admin/accounts', label: '계정과목', icon: '#', roles: ['HQ_ADMIN'], group: '설정' },
  { path: '/admin/payroll-rates', label: '급여 요율', icon: '‰', roles: ['HQ_ADMIN', 'HR_MGR'], group: '설정' },
  { path: '/admin/report-sections', label: '리포트 노출', icon: '◫', roles: ['HQ_ADMIN'], group: '설정' },
]

export function visibleMenu(has: (...r: string[]) => boolean) {
  return MENU.filter(m => m.roles === 'ALL' || has(...m.roles))
}
