import { Link } from 'react-router-dom'
import { useSession } from '../session'
import { Page, Badge } from '../components/ui'
import { ROLE_NAMES } from '../format'

// 계정(등급)별로 이 계정에서 확인해 볼 기능 안내
const GUIDE: Record<string, { text: string; to: string }[]> = {
  HQ_ADMIN: [
    { text: '조직도에서 전체 본사·매장 인원 확인', to: '/org' },
    { text: '계정·등급: 새 계정 만들기, 등급 배정/해제, 계정별 VAT 표시 지정', to: '/admin/users' },
    { text: '매장 관리: 매장별 VAT 기본값, 점주 개인별 급여 열람 허용', to: '/admin/stores' },
    { text: '결재함: 물류·결손 최종 승인', to: '/approvals' },
    { text: '매장 비교, 투자 관리', to: '/compare' },
  ],
  LOGISTICS_MGR: [
    { text: '물류 처리 보드: 승인 → 외부 구매 → 발송 등록', to: '/logistics/board' },
    { text: '품목·매장별 단가·할인 관리', to: '/admin/catalog' },
    { text: '리포트에서 물류 섹션만 보이는지 확인', to: '/reports' },
  ],
  HR_MGR: [
    { text: '직원 등록, 근무기록', to: '/hr' },
    { text: '급여 자동 계산 → 상신', to: '/payroll' },
    { text: '매장 관리자·일반 사원 계정 만들기', to: '/admin/users' },
  ],
  OWNER: [
    { text: '상단에서 매장 전환 (2개 이상 매장 점주)', to: '/' },
    { text: '결재함: 물류·결손·급여 결재', to: '/approvals' },
    { text: '결산, 본사 정산서 입금 등록', to: '/settlement' },
  ],
  STORE_MGR: [
    { text: '물류 요청 작성 (본사 물류 / 외부 URL)', to: '/logistics' },
    { text: '수령 검수, 결손 등록', to: '/adjustments' },
    { text: '매출 입력, 비용 입력', to: '/sales' },
  ],
  STAFF: [
    { text: '물류 요청 작성 → 상신', to: '/logistics' },
    { text: '결손 등록 (시음·직원 음료)', to: '/adjustments' },
    { text: '내 근무·급여명세', to: '/my' },
  ],
  INVESTOR: [{ text: '공개된 투자 금액·회수율 확인', to: '/investor' }],
  SUPERVISOR: [],
}

export function Home() {
  const s = useSession()
  const roles = [...s.roles]
  return (
    <Page title={`${s.user?.name}님, 안녕하세요`} sub={roles.map(r => ROLE_NAMES[r]).join(' · ')}>
      <div className="grid3">
        <div className="card stat"><small>볼 수 있는 매장</small><b>{s.stores.length}곳</b>
          <span className="muted small">{s.stores.map(x => x.name).join(', ') || '없음'}</span></div>
        <div className="card stat"><small>선택 매장</small><b>{s.currentStore?.name ?? (s.stores.length > 1 ? '전체 (합산)' : '—')}</b>
          <span className="muted small">{s.currentStore?.region_name}</span></div>
        <div className="card stat"><small>부가세 표시</small><b>{s.vatMode === 'INCLUSIVE' ? 'VAT 포함' : 'VAT 별도'}</b>
          <span className="muted small">{s.user?.vatOverride ? '계정 지정값' : '매장 기본값'}</span></div>
      </div>
      <section className="card">
        <h2>이 계정으로 확인해 볼 기능</h2>
        {roles.map(r => (
          <div key={r} className="guide">
            <Badge tone="info">{ROLE_NAMES[r]}</Badge>
            <ul>{(GUIDE[r] ?? []).map(g => <li key={g.text}><Link to={g.to}>{g.text}</Link></li>)}</ul>
          </div>
        ))}
        <p className="muted small">아직 만들지 않은 화면은 "구현 단계 N 예정"으로 표시됩니다. 진행 상황은 저장소 PLAN.md 에 있습니다.</p>
      </section>
    </Page>
  )
}
