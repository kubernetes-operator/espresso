import { useMemo, useState } from 'react'
import { api } from '../api'
import { useApi, Page, Badge, ErrorBox, Toast, Modal } from '../components/ui'
import { useSession } from '../session'
import { ROLE_NAMES, SITE_TYPES, HQ_SITE, won, dt, num } from '../format'

type Asg = { id: string; role: string; roleName: string; roleActive: boolean; scopeType: string; scopeId: string | null; line: string }
type U = { id: string; loginId: string; name: string; status: string; vatOverride: string | null; lastLoginAt: string | null; initialPassword: boolean; suspendedAt: string | null; assignments: Asg[] }

/** 소속 구분별 고를 수 있는 등급 */
const SITE_ROLES: Record<string, string[]> = {
  HQ: ['HQ_ADMIN', 'LOGISTICS_MGR', 'HR_MGR', 'SUPERVISOR'],
  STORE: ['OWNER', 'STORE_MGR', 'STAFF', 'INVESTOR'],
  BAKERY: ['STORE_MGR', 'STAFF'],
}
/** 등급 → 연봉 테이블 직급군 접두어 */
const GRADE_GROUP: Record<string, string> = { HQ_ADMIN: 'a', LOGISTICS_MGR: 'l', HR_MGR: 'h', SUPERVISOR: 'v', STORE_MGR: 'm', STAFF: 's' }

export function AdminUsers() {
  const s = useSession()
  const isAdmin = s.has('HQ_ADMIN')
  const users = useApi<U[]>('/api/auth/users')
  const stores = useApi<any[]>('/api/core/stores/names')
  const regions = useApi<any[]>('/api/core/regions')
  const [msg, setMsg] = useState<string | null>(null)
  const [err, setErr] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)
  const [grantFor, setGrantFor] = useState<U | null>(null)
  const [q, setQ] = useState('')
  const [onlySite, setOnlySite] = useState(s.storeId !== 'ALL')

  const siteOf = (id: string | null) => stores.data?.find(st => st.id === id)
  const scopeName = (a: Asg) => a.scopeType === 'ALL' ? '전체'
    : a.scopeType === 'REGION' ? (regions.data?.find(r => r.id === a.scopeId)?.name ?? '권역')
    : (siteOf(a.scopeId)?.name ?? '매장')

  // 상단에서 고른 사이트의 계정만 (본사 = 본사 라인 계정)
  const filtered = useMemo(() => (users.data ?? []).filter(u => {
    if (q && !u.name.includes(q) && !u.loginId.includes(q)) return false
    if (!onlySite || s.storeId === 'ALL') return true
    if (s.storeId === HQ_SITE) return u.assignments.some(a => a.line === 'HQ')
    return u.assignments.some(a => a.scopeId === s.storeId)
  }), [users.data, q, onlySite, s.storeId])

  const run = async (fn: () => Promise<any>, ok: string) => {
    setErr(null)
    try { await fn(); setMsg(ok); users.reload() } catch (e: any) { setErr(e.message) }
  }

  return (
    <Page title="계정·등급" sub={isAdmin ? '계정 생성(= 직원 등록), 등급 배정·해제, 정지·비밀번호 초기화, 계정별 VAT 표시' : '인사 관리자: 매장·베이커리 직원 계정 생성'}
      actions={<><input id="user-q" placeholder="이름·아이디 검색" value={q} onChange={e => setQ(e.target.value)} />
        {s.storeId !== 'ALL' && <label className="switch"><input type="checkbox" checked={onlySite} onChange={e => setOnlySite(e.target.checked)} /><span>{s.currentStore?.name} 계정만</span></label>}
        <button className="primary" onClick={() => setCreating(true)}>계정 만들기</button></>}>
      <ErrorBox error={users.error || err} />
      <div className="table-wrap card">
        <table className="tbl">
          <thead><tr><th>이름</th><th>아이디</th><th>등급 · 범위</th><th>VAT 표시</th><th>상태</th><th></th></tr></thead>
          <tbody>
            {filtered.map(u => (
              <tr key={u.id} className={u.status === 'ACTIVE' ? '' : 'inactive'}>
                <td><b>{u.name}</b>{u.initialPassword && <> <Badge tone="warn">초기 비밀번호</Badge></>}</td>
                <td><code>{u.loginId}</code></td>
                <td>
                  <div className="chips">
                    {u.assignments.map(a => (
                      <span key={a.id} className={`chip ${a.roleActive ? '' : 'off'}`}>
                        {a.roleName} · {scopeName(a)}
                        {isAdmin && <button className="chip-x" aria-label="등급 해제" title="등급 해제"
                          onClick={() => run(() => api(`/api/auth/assignments/${a.id}`, { method: 'DELETE' }), '등급을 해제했습니다')}>×</button>}
                      </span>
                    ))}
                    {isAdmin && <button className="ghost small" onClick={() => setGrantFor(u)}>+ 등급</button>}
                  </div>
                </td>
                <td>
                  {isAdmin ? (
                    <select id={`uvat-${u.id}`} aria-label="VAT 표시" value={u.vatOverride ?? ''}
                      onChange={e => run(() => api(`/api/auth/users/${u.id}`, { method: 'PUT', body: { vatOverride: e.target.value } }), 'VAT 표시를 바꿨습니다 (다음 로그인부터 적용)')}>
                      <option value="EXCLUSIVE">VAT 별도</option><option value="INCLUSIVE">VAT 포함</option><option value="">매장 기본값</option>
                    </select>
                  ) : (u.vatOverride === 'INCLUSIVE' ? 'VAT 포함' : u.vatOverride === 'EXCLUSIVE' ? 'VAT 별도' : '매장 기본값')}
                </td>
                <td>{u.status === 'ACTIVE' ? <Badge tone="ok">사용</Badge>
                  : <><Badge tone="bad">정지</Badge><div className="small muted">{u.suspendedAt ? `${dt(u.suspendedAt)} · 로그인 차단` : '로그인 차단'}</div></>}</td>
                <td className="nowrap">
                  {isAdmin && u.id !== s.user?.id && (
                    <button className="ghost small" onClick={() => {
                      if (u.status === 'ACTIVE' && !confirm(`${u.name} 계정을 정지할까요? 삭제되지 않고 로그인만 막힙니다.`)) return
                      run(() => api(`/api/auth/users/${u.id}`, { method: 'PUT', body: { status: u.status === 'ACTIVE' ? 'SUSPENDED' : 'ACTIVE' } }),
                        u.status === 'ACTIVE' ? '계정을 정지했습니다 (로그인 차단)' : '정지를 해제했습니다')
                    }}>{u.status === 'ACTIVE' ? '정지' : '정지 해제'}</button>)}
                  {u.id !== s.user?.id && <button className="ghost small" onClick={() => {
                    if (!confirm(`${u.name} 비밀번호를 초기 비밀번호로 바꿀까요?`)) return
                    run(() => api(`/api/auth/users/${u.id}/reset-password`, { body: {} }), '초기 비밀번호로 바꿨습니다 (첫 로그인 후 변경 안내)')
                  }}>비밀번호 초기화</button>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="muted small">정지된 계정은 목록에 남고 로그인과 기존 접속이 막힙니다(최대 30초 안에 모든 화면에서 차단). 새 계정과 초기화한 계정의 비밀번호는 <b>cafedelyon</b> 이며 첫 로그인 후 바꾸도록 안내됩니다.</p>
      {creating && <CreateUser isAdmin={isAdmin} stores={stores.data ?? []} regions={regions.data ?? []} defaultSite={s.storeId}
        onClose={() => setCreating(false)} onDone={(r: any) => { setCreating(false); setMsg(`계정을 만들었습니다${r.empNo ? ` · 사번 ${r.empNo}` : ''}`); users.reload() }} />}
      {grantFor && <GrantRole user={grantFor} stores={stores.data ?? []} regions={regions.data ?? []}
        onClose={() => setGrantFor(null)} onDone={() => { setGrantFor(null); setMsg('등급을 배정했습니다'); users.reload() }} />}
      <Toast msg={msg} onDone={() => setMsg(null)} />
    </Page>
  )
}

function CreateUser({ isAdmin, stores, regions, defaultSite, onClose, onDone }: any) {
  const pre = stores.find((x: any) => x.id === defaultSite)
  const [site, setSite] = useState<string>(isAdmin ? (pre?.site_type ?? 'STORE') : (pre?.site_type === 'BAKERY' ? 'BAKERY' : 'STORE'))
  const roles = SITE_ROLES[site].filter(r => isAdmin || ['STORE_MGR', 'STAFF'].includes(r))
  const [role, setRole] = useState(site === 'HQ' ? 'LOGISTICS_MGR' : 'STAFF')
  const [scopeId, setScopeId] = useState(pre && pre.site_type === site && site !== 'HQ' ? pre.id : '')
  const [regionMode, setRegionMode] = useState(false)
  const [f, setF] = useState({ loginId: '', name: '', email: '', phone: '' })
  const [emp, setEmp] = useState({ register: true, employmentType: 'REGULAR', salaryGrade: '', baseWage: '', weeklyHours: '40', hireDate: new Date(Date.now() + 9 * 3600e3).toISOString().slice(0, 10) })
  const grades = useApi<any[]>('/api/core/hr/salary-grades')
  const [err, setErr] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const sites = stores.filter((x: any) => (x.site_type ?? 'STORE') === site)
  const noEmployee = role === 'INVESTOR'
  const hourly = emp.employmentType === 'PART_TIME'
  const group = emp.employmentType === 'FREELANCER' ? 'f' : GRADE_GROUP[role]
  const gradeList = (grades.data ?? []).filter(g => g.grade_group === group)
  const grade = (grades.data ?? []).find(g => g.code === emp.salaryGrade)
  const changeSite = (v: string) => { setSite(v); setRole(v === 'HQ' ? 'LOGISTICS_MGR' : 'STAFF'); setScopeId(''); setRegionMode(false) }
  const changeRole = (r: string) => { setRole(r); setEmp({ ...emp, salaryGrade: '', register: r !== 'INVESTOR' && r !== 'OWNER', employmentType: r === 'STAFF' ? emp.employmentType : 'REGULAR' }) }

  const submit = async () => {
    setErr(null); setBusy(true)
    try {
      const scopeType = site === 'HQ' ? (regionMode ? 'REGION' : 'ALL') : 'STORE'
      const r = await api('/api/core/hr/accounts', { body: {
        ...f, site, role, scopeType, scopeId: scopeType === 'ALL' ? null : scopeId,
        employee: noEmployee ? { register: false } : {
          register: emp.register, employmentType: emp.employmentType, hireDate: emp.hireDate, weeklyHours: Number(emp.weeklyHours),
          salaryGrade: hourly ? null : emp.salaryGrade || null, payType: hourly ? 'HOURLY' : 'MONTHLY', baseWage: hourly ? Number(emp.baseWage) : null,
        } } })
      onDone(r)
    } catch (e: any) { setErr(e.message) } finally { setBusy(false) }
  }
  const needScope = site !== 'HQ' || regionMode
  const empOk = noEmployee || !emp.register || (hourly ? !!emp.baseWage : !!emp.salaryGrade)
  return (
    <Modal title="계정 만들기" onClose={onClose} wide>
      <form className="form" onSubmit={e => { e.preventDefault(); submit() }}>
        <div className="row">
          <div><label htmlFor="nu-site">소속 구분</label>
            <select id="nu-site" value={site} onChange={e => changeSite(e.target.value)}>
              {(isAdmin ? ['HQ', 'STORE', 'BAKERY'] : ['STORE', 'BAKERY']).map(k => <option key={k} value={k}>{SITE_TYPES[k]}</option>)}
            </select></div>
          <div><label htmlFor="nu-role">등급</label>
            <select id="nu-role" value={role} onChange={e => changeRole(e.target.value)}>
              {roles.map(r => <option key={r} value={r}>{site === 'BAKERY' && r === 'STORE_MGR' ? '베이커리 관리자' : ROLE_NAMES[r]}</option>)}
            </select></div>
          {site === 'HQ' ? (
            <div><label htmlFor="nu-scope">담당 범위</label>
              <select id="nu-scope" value={regionMode ? 'REGION' : 'ALL'} onChange={e => { setRegionMode(e.target.value === 'REGION'); setScopeId('') }}>
                <option value="ALL">전체 매장</option><option value="REGION">권역 지정</option>
              </select></div>
          ) : null}
          {needScope && <div><label htmlFor="nu-scope-id">{site === 'HQ' ? '권역' : SITE_TYPES[site]}</label>
            <select id="nu-scope-id" required value={scopeId} onChange={e => setScopeId(e.target.value)}>
              <option value="">선택</option>
              {(site === 'HQ' ? regions : sites).map((x: any) => <option key={x.id} value={x.id}>{x.name}</option>)}
            </select></div>}
        </div>
        <div className="row">
          <div><label htmlFor="nu-login">아이디</label><input id="nu-login" required autoComplete="off" value={f.loginId} onChange={e => setF({ ...f, loginId: e.target.value.trim() })} /></div>
          <div><label htmlFor="nu-name">이름</label><input id="nu-name" required value={f.name} onChange={e => setF({ ...f, name: e.target.value })} /></div>
          <div><label htmlFor="nu-email">이메일 (선택)</label><input id="nu-email" type="email" value={f.email} onChange={e => setF({ ...f, email: e.target.value })} /></div>
          <div><label htmlFor="nu-phone">전화 (선택)</label><input id="nu-phone" inputMode="tel" value={f.phone} onChange={e => setF({ ...f, phone: e.target.value })} /></div>
        </div>
        <p className="muted small">초기 비밀번호는 <b>cafedelyon</b> 입니다. 첫 로그인 후 홈 화면에서 바꾸도록 안내됩니다.</p>

        {noEmployee ? <p className="muted small">투자자는 직원으로 등록하지 않습니다.</p> : <>
          <label className="switch"><input type="checkbox" checked={emp.register} onChange={e => setEmp({ ...emp, register: e.target.checked })} />
            <span>같은 정보로 직원 등록 (사번 자동 · 소속 {site === 'HQ' ? '본사' : (sites.find((x: any) => x.id === scopeId)?.name ?? SITE_TYPES[site])})</span></label>
          {emp.register && <div className="row">
            <div><label htmlFor="ne-type">고용형태</label>
              <select id="ne-type" value={emp.employmentType} onChange={e => setEmp({ ...emp, employmentType: e.target.value, salaryGrade: '' })}>
                <option value="REGULAR">정규직</option><option value="CONTRACT">계약직</option>{role === 'STAFF' && <option value="PART_TIME">아르바이트(시급)</option>}<option value="FREELANCER">프리랜서(3.3%)</option>
              </select></div>
            {hourly ? <div><label htmlFor="ne-wage">시급 (원)</label><input id="ne-wage" required inputMode="numeric" value={num(emp.baseWage)} onChange={e => setEmp({ ...emp, baseWage: e.target.value.replace(/\D/g, '') })} /></div>
              : <div><label htmlFor="ne-grade">연봉 등급</label>
                <select id="ne-grade" required value={emp.salaryGrade} onChange={e => setEmp({ ...emp, salaryGrade: e.target.value })}>
                  <option value="">선택</option>
                  {gradeList.map(g => <option key={g.code} value={g.code}>{g.code} · {group === 'f' ? `월 ${won(g.monthly)}` : `연 ${won(g.annual_salary)}`}</option>)}
                </select></div>}
            <div><label htmlFor="ne-wh">주 소정근로 (시간)</label><input id="ne-wh" required inputMode="decimal" value={emp.weeklyHours} onChange={e => setEmp({ ...emp, weeklyHours: e.target.value })} /></div>
            <div><label htmlFor="ne-hire">입사일</label><input id="ne-hire" required type="date" value={emp.hireDate} onChange={e => setEmp({ ...emp, hireDate: e.target.value })} /></div>
          </div>}
          {emp.register && grade && <div className="note">{group === 'f' ? <>월 <b>{won(grade.monthly)}원</b> · 3.3% 원천징수 후 약 <b>{won(Math.floor(grade.monthly * 0.967))}원</b></> : <>연봉 <b>{won(grade.annual_salary)}원</b> · 월 급여 <b>{won(grade.monthly)}원</b></>} ({grade.group_name} {grade.step}단계)</div>}
        </>}
        {err && <div className="error-box">{err}</div>}
        <button className="primary" disabled={busy}>{busy ? '만드는 중…' : '만들기'}</button>
      </form>
    </Modal>
  )
}

function GrantRole({ user, stores, regions, onClose, onDone }: any) {
  const [role, setRole] = useState('STAFF')
  const [scopeType, setScopeType] = useState('STORE')
  const [scopeId, setScopeId] = useState('')
  const [err, setErr] = useState<string | null>(null)
  const hq = ['HQ_ADMIN', 'LOGISTICS_MGR', 'HR_MGR', 'SUPERVISOR'].includes(role)
  const submit = async () => {
    setErr(null)
    try { await api(`/api/auth/users/${user.id}/assignments`, { body: { role, scopeType, scopeId: scopeType === 'ALL' ? null : scopeId } }); onDone() }
    catch (e: any) { setErr(e.message) }
  }
  return (
    <Modal title={`${user.name} 등급 배정`} onClose={onClose}>
      <form className="form" onSubmit={e => { e.preventDefault(); submit() }}>
        <label htmlFor="g-role">등급</label>
        <select id="g-role" value={role} onChange={e => { const r = e.target.value; setRole(r); setScopeType(['HQ_ADMIN', 'LOGISTICS_MGR', 'HR_MGR'].includes(r) ? 'ALL' : r === 'SUPERVISOR' ? 'REGION' : 'STORE'); setScopeId('') }}>
          {Object.keys(ROLE_NAMES).map(r => <option key={r} value={r}>{ROLE_NAMES[r]}</option>)}
        </select>
        <label htmlFor="scope-type">범위</label>
        <select id="scope-type" value={scopeType} onChange={e => { setScopeType(e.target.value); setScopeId('') }}>
          {hq && <option value="ALL">전체 매장</option>}
          {hq && <option value="REGION">권역</option>}
          {!hq && <option value="STORE">매장·베이커리</option>}
        </select>
        {scopeType !== 'ALL' && (<>
          <label htmlFor="scope-id">{scopeType === 'REGION' ? '권역' : '매장·베이커리'}</label>
          <select id="scope-id" required value={scopeId} onChange={e => setScopeId(e.target.value)}>
            <option value="">선택</option>
            {scopeType === 'REGION' ? regions.map((x: any) => <option key={x.id} value={x.id}>{x.name}</option>)
              : ['STORE', 'BAKERY'].map(t => <optgroup key={t} label={SITE_TYPES[t]}>
                  {stores.filter((x: any) => (x.site_type ?? 'STORE') === t).map((x: any) => <option key={x.id} value={x.id}>{x.name}</option>)}
                </optgroup>)}
          </select>
        </>)}
        {err && <div className="error-box">{err}</div>}
        <button className="primary">배정</button>
      </form>
    </Modal>
  )
}
