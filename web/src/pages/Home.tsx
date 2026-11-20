import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api'
import { useSession } from '../session'
import { Page, Badge, useApi, Toast } from '../components/ui'
import { ROLE_NAMES, dt } from '../format'

// 계정(등급)별로 이 계정에서 확인해 볼 기능 안내
const GUIDE: Record<string, { text: string; to: string }[]> = {
  HQ_ADMIN: [
    { text: '조직도에서 전체 본사·매장 인원 확인', to: '/org' },
    { text: '계정·등급: 새 계정 만들기, 등급 배정/해제, 계정별 VAT 표시 지정', to: '/admin/users' },
    { text: '매장 관리: 매장별 VAT 기본값, 점주 개인별 급여 열람 허용', to: '/admin/stores' },
    { text: '결재함: 물류·결손 최종 승인', to: '/approvals' },
    { text: '매장 비교, 매출 추이, 투자 관리', to: '/compare' },
    { text: '권역(시·도) 추가, 베이커리 생성', to: '/admin/stores' },
    { text: '결산: 주 마감 · 본사 매출(상단 [본사])', to: '/settlement' },
    { text: '투자자 지급액 결정', to: '/investor-payouts' },
  ],
  LOGISTICS_MGR: [
    { text: '물류 처리 보드: 승인 → 외부 구매 → 발송 등록', to: '/logistics/board' },
    { text: '품목(코드 자동)·본사 매입가·매장별 단가', to: '/admin/catalog' },
    { text: '본사 창고 재고·본사 입고 (상단에서 [본사] 선택)', to: '/inventory' },
    { text: '리포트에서 물류 섹션만 보이는지 확인', to: '/reports' },
  ],
  HR_MGR: [
    { text: '계정 만들기 = 직원 등록(사번 자동, 연봉 등급)', to: '/admin/users' },
    { text: '연봉 테이블(직급별 20단계) 관리', to: '/admin/salary-grades' },
    { text: '급여 자동 계산 → 상여·퇴직금 → 상신', to: '/payroll' },
    { text: '매장별 근무표', to: '/schedule' },
  ],
  OWNER: [
    { text: '상단에서 매장 전환 (2개 이상 매장 점주)', to: '/' },
    { text: '결재함: 물류·결손·급여 결재', to: '/approvals' },
    { text: '결산, 본사 정산서 입금 등록', to: '/settlement' },
  ],
  STORE_MGR: [
    { text: '물류 요청 작성 (본사 물류 / 외부 URL)', to: '/logistics' },
    { text: '수령 검수: 부족·파손은 재발송 또는 손실 처리', to: '/logistics' },
    { text: '근무표 작성', to: '/schedule' },
    { text: '매출 입력, 비용 입력', to: '/sales' },
  ],
  STAFF: [
    { text: '근무 마감: 판매·결제 금액 입력', to: '/closing' },
    { text: '물류 요청 작성 → 상신', to: '/logistics' },
    { text: '결손 등록 (시음·직원 음료)', to: '/adjustments' },
    { text: '내 근무·급여명세', to: '/my' },
  ],
  INVESTOR: [{ text: '공개된 투자 금액, 월별 매출액·이익 그래프', to: '/investor' }, { text: '투자 매장과 본사 조직도', to: '/org' }],
  SUPERVISOR: [
    { text: '담당 권역 물류 처리·추적 (물류 관리자 권한 포함)', to: '/logistics/tracking' },
    { text: '담당 권역 직원·근무표·급여 (인사 관리자 권한 포함)', to: '/schedule' },
  ],
}

export function Home() {
  const s = useSession()
  const roles = [...s.baseRoles]
  return (
    <Page title={`${s.user?.name}님, 안녕하세요`} sub={roles.map(r => ROLE_NAMES[r]).join(' · ')}>
      {s.user?.initialPassword && <PasswordCard />}
      <ClockCard />
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

/** 출근·퇴근 (직원으로 등록된 모든 계정) */
function ClockCard() {
  const me = useApi<any>('/api/core/hr/me')
  const [now, setNow] = useState(Date.now())
  const [msg, setMsg] = useState<string | null>(null)
  const [err, setErr] = useState<string | null>(null)
  useEffect(() => { const t = setInterval(() => setNow(Date.now()), 30000); return () => clearInterval(t) }, [])
  const d = me.data
  if (!d || d.employees.length === 0) return null
  const open = d.open
  const mins = open ? Math.floor((now - new Date(open.clock_in).getTime()) / 60000) : 0
  const today = (d.records ?? []).filter((w: any) => w.work_date === new Date(now + 9 * 3600e3).toISOString().slice(0, 10))
  const clock = async (kind: 'in' | 'out') => {
    setErr(null)
    try { await api(`/api/core/hr/clock-${kind}`, { body: kind === 'out' ? { breakMinutes: 0 } : {} }); setMsg(kind === 'in' ? '출근했습니다' : '퇴근했습니다'); me.reload() }
    catch (e: any) { setErr(e.message) }
  }
  return (
    <section className="card clock-card" aria-label="출퇴근">
      <div>
        <small className="muted">{d.employees[0].store_name} · {d.employees[0].name} ({d.employees[0].emp_no})</small>
        {open ? <b className="big-num">근무 중 {Math.floor(mins / 60)}시간 {mins % 60}분</b>
          : <b className="big-num">{new Date(now).toLocaleTimeString('ko-KR', { timeZone: 'Asia/Seoul', hour: '2-digit', minute: '2-digit' })}</b>}
        <div className="small muted">{open ? `출근 ${dt(open.clock_in)}` : today.length ? `오늘 ${today.length}회 근무 기록` : '오늘 출근 전'}</div>
        {err && <div className="error-box">{err}</div>}
      </div>
      {open ? <button className="danger big" onClick={() => clock('out')}>퇴근</button> : <button className="primary big" onClick={() => clock('in')}>출근</button>}
      <Toast msg={msg} onDone={() => setMsg(null)} />
    </section>
  )
}

/** 초기 비밀번호 변경 */
function PasswordCard() {
  const s = useSession()
  const [f, setF] = useState({ current: '', next: '', again: '' })
  const [err, setErr] = useState<string | null>(null)
  const [done, setDone] = useState(false)
  const save = async () => {
    setErr(null)
    if (f.next.length < 8) return setErr('새 비밀번호는 8자 이상입니다.')
    if (f.next !== f.again) return setErr('새 비밀번호가 서로 다릅니다.')
    try { await api('/api/auth/me/password', { method: 'PUT', body: { current: f.current, next: f.next } }); setDone(true); s.passwordChanged() } catch (e: any) { setErr(e.message) }
  }
  if (done) return null
  return (
    <section className="card note warn">
      <b>초기 비밀번호를 바꿔 주세요</b>
      <form className="form" onSubmit={e => { e.preventDefault(); save() }}>
        <div className="row">
          <div><label htmlFor="pw-cur">현재(초기) 비밀번호</label><input id="pw-cur" type="password" autoComplete="current-password" value={f.current} onChange={e => setF({ ...f, current: e.target.value })} /></div>
          <div><label htmlFor="pw-new">새 비밀번호 (8자 이상)</label><input id="pw-new" type="password" autoComplete="new-password" value={f.next} onChange={e => setF({ ...f, next: e.target.value })} /></div>
          <div><label htmlFor="pw-again">새 비밀번호 확인</label><input id="pw-again" type="password" autoComplete="new-password" value={f.again} onChange={e => setF({ ...f, again: e.target.value })} /></div>
        </div>
        {err && <div className="error-box">{err}</div>}
        <button className="primary">비밀번호 바꾸기</button>
      </form>
    </section>
  )
}
