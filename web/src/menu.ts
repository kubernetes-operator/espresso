// 등급별 메뉴 (설계 §2.2 권한 매트릭스). desc = 화면 설명 (메뉴 툴팁·화면 상단 안내), tips = 화면 사용법
// all = 상단 '전체(합산)' 선택 시 이 화면이 합산/전체 목록을 보여주는지 (false 면 매장을 골라야 함)
export type MenuItem = {
  path: string; label: string; icon: string; roles: string[] | 'ALL'; group: string
  mobileTab?: 'home' | 'request' | 'approve' | 'check' | 'more'
  desc: string; tips?: string[]; all?: boolean
  fixAll?: string[]   // 이 등급은 상단 선택을 '전체'로 고정
}

const STORE_SIDE = ['OWNER', 'STORE_MGR', 'STAFF']

export const MENU: MenuItem[] = [
  { path: '/', label: '홈', icon: '⌂', roles: 'ALL', group: '일반', mobileTab: 'home', all: true,
    desc: '출근·퇴근 등록, 내 계정 정보와 이 계정으로 해 볼 일을 모아 둔 첫 화면입니다.',
    tips: ['직원으로 등록된 계정은 여기서 바로 출근·퇴근을 누릅니다.', '초기 비밀번호를 쓰는 중이면 여기서 바꿉니다.'] },
  { path: '/logistics', label: '물류 요청', icon: '▤', roles: ['HQ_ADMIN', 'LOGISTICS_MGR', ...STORE_SIDE], group: '물류', mobileTab: 'request', all: true,
    desc: '매장에서 본사 물류나 외부 구매(URL)를 요청하고 진행 상태를 봅니다.',
    tips: ['요청 기한을 넣으면 물류 담당이 처리 기한을 정할 때 참고합니다.', '도착하면 상세 화면의 [수령 검수]에서 부족·파손·불량을 재발송 또는 손실 처리합니다.'] },
  { path: '/logistics/board', label: '물류 처리', icon: '⇄', roles: ['HQ_ADMIN', 'LOGISTICS_MGR'], group: '물류', all: true,
    desc: '물류 담당이 승인된 요청을 구매·발송하는 보드입니다. 카드마다 처리 기한이 보입니다.',
    tips: ['기한이 지난 요청은 빨간 표시가 붙습니다.', '수령 문제로 생긴 재발송 요청은 [재발송] 표시로 바로 발송 대기에 들어옵니다.'] },
  { path: '/logistics/tracking', label: '물류 추적', icon: '⌖', roles: ['HQ_ADMIN', 'LOGISTICS_MGR'], group: '물류', all: true,
    desc: '모든 승인이 끝난 물류를 구매·발송·도착·수령까지 추적하고, 끝나면 [확인 완료]로 종결합니다.',
    tips: ['수령 검수까지 끝난 요청은 맨 위 "확인 대기"에 모입니다.', '확인 완료한 요청은 물류 처리 보드의 완료 칸으로 갑니다.'] },
  { path: '/inventory', label: '재고', icon: '▦', roles: ['HQ_ADMIN', 'LOGISTICS_MGR', ...STORE_SIDE], group: '물류', mobileTab: 'check', all: true,
    desc: '매장·본사 창고의 품목별 재고 수량과 금액(이동평균 단가)입니다.',
    tips: ['상단에서 [본사]를 고르면 본사 창고 재고와 [본사 입고]가 보입니다.', '매장 발송 시 본사 재고에서 자동 차감됩니다.'] },
  { path: '/adjustments', label: '손실·결손·사용', icon: '−', roles: ['HQ_ADMIN', 'LOGISTICS_MGR', ...STORE_SIDE], group: '물류', mobileTab: 'request', all: true,
    desc: '파손·유통기한·시음 등으로 재고를 줄이는 요청과 결재입니다.' },
  { path: '/approvals', label: '결재함', icon: '✓', roles: ['HQ_ADMIN', 'LOGISTICS_MGR', 'HR_MGR', 'OWNER', 'STORE_MGR'], group: '일반', mobileTab: 'approve', all: true,
    desc: '내가 승인할 차례인 문서(물류·손실·급여·비용)와 내가 올린 문서의 진행 상황입니다.' },
  { path: '/closing', label: '근무 마감', icon: '◧', roles: ['HQ_ADMIN', 'OWNER', 'STORE_MGR', 'STAFF'], group: '회계', mobileTab: 'request', all: false,
    desc: '근무를 마칠 때 판매·결제 금액을 입력해 마감합니다. 그날 마감을 모은 소계를 매장 관리자가 일 매출로 반영합니다.',
    tips: ['결제 합계(카드+현금+기타)가 판매 합계와 같아야 저장됩니다.', '반영 전 마감은 본인이 지울 수 있습니다.'] },
  { path: '/sales', label: '매출', icon: '₩', roles: ['HQ_ADMIN', 'OWNER', 'STORE_MGR'], group: '회계', all: true,
    desc: '일별 매출을 입력하거나 엑셀로 올리고 마감합니다.' },
  { path: '/expenses', label: '비용', icon: '≡', roles: ['HQ_ADMIN', 'OWNER', 'STORE_MGR'], group: '회계', all: true,
    desc: '임대료·공과금·운영비 등 매장 비용을 등록하고 결재합니다.' },
  { path: '/settlement', label: '결산', icon: 'Σ', roles: ['HQ_ADMIN', 'OWNER', 'STORE_MGR'], group: '회계',
    desc: '월 손익과 결산액, 월 마감·주 마감(월~일, 일요일 24시 자동)입니다. 상단에서 [본사]를 고르면 본사 매출(로열티 + 물류 이득)이 보입니다.',
    tips: ['주 마감은 매주 일요일 24시에 자동으로 확정되며 분개를 잠그지 않습니다.', '월 마감은 총괄이 [월 마감]을 눌러 잠급니다.', '마감 뒤 그 달 일자로 들어온 건이나 고칠 금액은 마감한 달 화면 아래 「마감 후 처리」에서 반영·조정합니다 (수정 결산, 마감 유지).'] },
  { path: '/statements', label: '본사 정산서', icon: '⎙', roles: ['HQ_ADMIN', 'OWNER'], group: '회계', all: true,
    desc: '본사가 매장에 청구하는 월 정산서(물류·로열티)와 입금 확인입니다.' },
  { path: '/tax', label: '세무', icon: '§', roles: ['HQ_ADMIN'], group: '회계', all: true,
    desc: '세금계산서와 부가세 집계, 회계 프로그램 내보내기입니다.' },
  { path: '/hr', label: '직원·근무', icon: '☺', roles: ['HQ_ADMIN', 'HR_MGR', 'OWNER', 'STORE_MGR'], group: '인사', all: true,
    desc: '직원(사번·연봉 등급·계약)과 실제 출퇴근 근무기록입니다.',
    tips: ['연봉 등급(예: s3)을 고르면 연봉과 월 급여가 표시되고 급여 계산에 쓰입니다.', '비과세 항목은 식대 외에 기타 항목을 여러 개 넣을 수 있습니다.'] },
  { path: '/schedule', label: '근무표', icon: '▥', roles: ['HQ_ADMIN', 'HR_MGR', 'OWNER', 'STORE_MGR', 'STAFF'], group: '인사', all: false,
    desc: '매장별 근무표(주간·4주). 직원이 휴무를 먼저 지정하면 [자동 로테이션]이 오픈·마감·일반 근무를 채웁니다.',
    tips: ['[근무 기준]에서 오픈·마감 시각, 최번시, 최소 인원을 정합니다. 인원이 모자라면 오픈·마감을 먼저 채웁니다.',
      '칸을 눌러 수동 조정할 때는 승인 메모가 필요합니다. 이력은 아래 "조정 이력"에 남습니다.',
      '긴급 연차는 근무 칸에서 [연차·대체]를 눌러 대체 인원을 고릅니다. 퇴사자가 있으면 빨간 표시 → [다시 배치].',
      '본사는 로테이션 없이 평일 9시~18시로 만듭니다. 사원은 자기 줄에서 휴무·연차를 신청할 수 있습니다.'] },
  { path: '/payroll', label: '급여', icon: '¤', roles: ['HQ_ADMIN', 'HR_MGR', 'OWNER'], group: '인사', all: true,
    desc: '근무기록·연봉 등급으로 월 급여를 계산하고 결재·명세서를 발행합니다.',
    tips: ['매장을 고르면 그 매장 급여를 계산·상신합니다. [전체]에서는 매장별 지급 총액 표만 봅니다.', '상여금·퇴직금은 급여 상세의 [상여·퇴직금]에서 넣습니다.'] },
  { path: '/investor-payouts', label: '투자자 지급', icon: '◈', roles: ['HQ_ADMIN', 'HR_MGR'], group: '인사', all: true,
    desc: '투자자별 이익금 배분액(월 결산액 × 출자 비율)을 보고 실제 지급액을 결정합니다.',
    tips: ['출자 비율 = 투자자 출자 ÷ (투자자 출자 + 본사 지원). 투자 관리에서 항목별 자금 출처를 정합니다.', '결정 → 지급 완료 순서로 기록되고 투자자 화면에 보입니다.'] },
  { path: '/my', label: '내 근무·급여', icon: '◔', roles: ['STAFF', 'STORE_MGR', 'OWNER', 'HR_MGR', 'LOGISTICS_MGR', 'HQ_ADMIN'], group: '인사', mobileTab: 'more', all: true,
    desc: '내 출퇴근 기록과 발행된 급여명세서입니다.' },
  { path: '/reports', label: '리포트', icon: '▧', roles: ['HQ_ADMIN', 'LOGISTICS_MGR', 'HR_MGR', 'OWNER', 'STORE_MGR', 'STAFF'], group: '분석', mobileTab: 'check', all: true,
    desc: '월간 1장 리포트입니다. 보는 사람 등급에 따라 보이는 섹션이 다릅니다.' },
  { path: '/trends', label: '매출 추이', icon: '↗', roles: ['HQ_ADMIN', 'LOGISTICS_MGR', 'HR_MGR', 'OWNER', 'STORE_MGR'], group: '분석', all: true,
    desc: '최근 12개월 월별 순매출과 1년 전 같은 달 매출을 겹쳐 비교합니다.' },
  { path: '/compare', label: '매장 비교', icon: '⫼', roles: ['HQ_ADMIN', 'LOGISTICS_MGR', 'HR_MGR', 'OWNER'], group: '분석', all: true,
    desc: '매장 간 손익·물류·인건비 지표 비교입니다.',
    tips: ['점주: 내 매장이 3개 이하면 내 매장과 전체 상위·하위 매장 1개씩을, 4개 이상이면 내 매장 평균을 상위·하위 매장과 비교합니다.'] },
  { path: '/investment', label: '투자 관리', icon: '◆', roles: ['HQ_ADMIN'], group: '분석', all: true,
    desc: '매장 구성 총액(투자자 출자 + 본사 지원)과 투자자 공개 설정입니다.' },
  { path: '/investor', label: '투자 현황', icon: '◇', roles: ['INVESTOR'], group: '분석', mobileTab: 'home', all: true,
    desc: '투자한 매장의 공개 금액, 월별 매출액·이익 그래프(1년)입니다.' },
  { path: '/org', label: '조직도', icon: '⌬', roles: 'ALL', group: '안내', mobileTab: 'more', all: true, fixAll: ['HQ_ADMIN'],
    desc: '본사와 매장 인원 배치입니다. 등급에 따라 보이는 범위가 다릅니다.' },
  { path: '/blueprint', label: '설계도', icon: '⌗', roles: ['HQ_ADMIN', 'LOGISTICS_MGR', 'HR_MGR', 'SUPERVISOR', 'OWNER', 'STORE_MGR', 'STAFF'], group: '안내', mobileTab: 'more', all: true,
    desc: '시스템 구성·업무 흐름 설계도입니다.' },
  { path: '/admin/stores', label: '매장 관리', icon: '⌂', roles: ['HQ_ADMIN'], group: '설정', all: true,
    desc: '매장·베이커리 생성과 설정(VAT 기본값·급여 열람), 권역(시·도) 관리입니다.' },
  { path: '/admin/users', label: '계정·등급', icon: '☰', roles: ['HQ_ADMIN', 'HR_MGR'], group: '설정', all: true,
    desc: '계정을 만들면 같은 정보로 직원도 등록됩니다(사번 자동). 정지·비밀번호 초기화도 여기서 합니다.',
    tips: ['초기 비밀번호는 cafedelyon 이고 첫 로그인 후 바꾸도록 안내됩니다.', '정지하면 삭제되지 않고 로그인만 막힙니다. [해제]로 되살립니다.'] },
  { path: '/admin/salary-grades', label: '연봉 테이블', icon: '₩', roles: ['HQ_ADMIN', 'HR_MGR'], group: '설정', all: true,
    desc: '직급군별 20단계 연봉표(예: 사원 s1~s20, 인사 관리자 h1~h20)입니다. 바꾸면 해당 등급 직원 급여에 반영됩니다.' },
  { path: '/admin/catalog', label: '품목·단가', icon: '▣', roles: ['HQ_ADMIN', 'LOGISTICS_MGR'], group: '설정', all: true,
    desc: '품목(코드 자동 생성)과 본사 기준가·본사 매입가, 매장별 단가·할인입니다.' },
  { path: '/admin/royalty', label: '로열티', icon: '%', roles: ['HQ_ADMIN'], group: '설정', all: true, desc: '매장별 로열티 정책(정률·정액)입니다.' },
  { path: '/admin/approval-lines', label: '결재선', icon: '↧', roles: ['HQ_ADMIN'], group: '설정', all: true, desc: '문서 종류·금액별 결재선입니다.' },
  { path: '/admin/accounts', label: '계정과목', icon: '#', roles: ['HQ_ADMIN'], group: '설정', all: true, desc: '결산에 쓰는 계정과목표입니다.' },
  { path: '/admin/payroll-rates', label: '급여 요율', icon: '‰', roles: ['HQ_ADMIN', 'HR_MGR'], group: '설정', all: true, desc: '연도별 4대보험·최저임금 등 급여 요율입니다.' },
  { path: '/admin/report-sections', label: '리포트 노출', icon: '◫', roles: ['HQ_ADMIN'], group: '설정', all: true, desc: '등급별로 리포트에 보이는 섹션입니다.' },
]

export function visibleMenu(has: (...r: string[]) => boolean) {
  return MENU.filter(m => m.roles === 'ALL' || has(...m.roles))
}

/** 현재 경로에 해당하는 메뉴 (가장 긴 경로 일치) */
export function menuFor(pathname: string) {
  return [...MENU].sort((a, b) => b.path.length - a.path.length).find(m => m.path === '/' ? pathname === '/' : pathname === m.path || pathname.startsWith(m.path + '/'))
}
