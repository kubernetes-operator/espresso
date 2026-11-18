import { useMemo, useState } from 'react'
import { api } from '../api'
import { useApi, Page, Badge, ErrorBox, Toast, Modal } from '../components/ui'
import { useSession } from '../session'
import { ROLE_NAMES } from '../format'

type Asg = { id: string; role: string; roleName: string; roleActive: boolean; scopeType: string; scopeId: string | null }
type U = { id: string; loginId: string; name: string; status: string; vatOverride: string | null; lastLoginAt: string | null; assignments: Asg[] }

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

  const scopeName = (a: Asg) => a.scopeType === 'ALL' ? '전체'
    : a.scopeType === 'REGION' ? (regions.data?.find(r => r.id === a.scopeId)?.name ?? '권역')
    : (stores.data?.find(st => st.id === a.scopeId)?.name ?? '매장')

  const filtered = useMemo(() => (users.data ?? []).filter(u => !q || u.name.includes(q) || u.loginId.includes(q)), [users.data, q])

  const run = async (fn: () => Promise<any>, ok: string) => {
    setErr(null)
    try { await fn(); setMsg(ok); users.reload() } catch (e: any) { setErr(e.message) }
  }

  return (
    <Page title="계정·등급" sub={isAdmin ? '계정 생성, 등급 배정·해제, 계정별 VAT 표시 지정' : '인적 관리자: 매장 관리자·일반 사원 계정 생성'}
      actions={<><input id="user-q" placeholder="이름·아이디 검색" value={q} onChange={e => setQ(e.target.value)} />
        <button className="primary" onClick={() => setCreating(true)}>계정 만들기</button></>}>
      <ErrorBox error={users.error || err} />
      <div className="table-wrap card">
        <table className="tbl">
          <thead><tr><th>이름</th><th>아이디</th><th>등급 · 범위</th><th>VAT 표시</th><th>상태</th><th></th></tr></thead>
          <tbody>
            {filtered.map(u => (
              <tr key={u.id}>
                <td><b>{u.name}</b></td>
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
                      <option value="">매장 기본값</option><option value="EXCLUSIVE">VAT 별도</option><option value="INCLUSIVE">VAT 포함</option>
                    </select>
                  ) : (u.vatOverride ?? '매장 기본값')}
                </td>
                <td><Badge tone={u.status === 'ACTIVE' ? 'ok' : 'bad'}>{u.status === 'ACTIVE' ? '사용' : u.status}</Badge></td>
                <td>{isAdmin && u.id !== s.user?.id && (
                  <button className="ghost small" onClick={() => run(() => api(`/api/auth/users/${u.id}`, { method: 'PUT', body: { status: u.status === 'ACTIVE' ? 'SUSPENDED' : 'ACTIVE' } }),
                    u.status === 'ACTIVE' ? '계정을 정지했습니다' : '계정을 다시 사용하게 했습니다')}>
                    {u.status === 'ACTIVE' ? '정지' : '해제'}</button>)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {creating && <CreateUser isAdmin={isAdmin} stores={stores.data ?? []} regions={regions.data ?? []}
        onClose={() => setCreating(false)} onDone={() => { setCreating(false); setMsg('계정을 만들었습니다'); users.reload() }} />}
      {grantFor && <GrantRole user={grantFor} stores={stores.data ?? []} regions={regions.data ?? []}
        onClose={() => setGrantFor(null)} onDone={() => { setGrantFor(null); setMsg('등급을 배정했습니다'); users.reload() }} />}
      <Toast msg={msg} onDone={() => setMsg(null)} />
    </Page>
  )
}

function ScopeFields({ role, scopeType, setScopeType, scopeId, setScopeId, stores, regions }: any) {
  const hq = ['HQ_ADMIN', 'LOGISTICS_MGR', 'HR_MGR', 'SUPERVISOR'].includes(role)
  return (
    <>
      <label htmlFor="scope-type">범위</label>
      <select id="scope-type" value={scopeType} onChange={e => { setScopeType(e.target.value); setScopeId('') }}>
        {hq && <option value="ALL">전체 매장</option>}
        {hq && <option value="REGION">권역</option>}
        <option value="STORE">매장</option>
      </select>
      {scopeType !== 'ALL' && (<>
        <label htmlFor="scope-id">{scopeType === 'REGION' ? '권역' : '매장'}</label>
        <select id="scope-id" value={scopeId} onChange={e => setScopeId(e.target.value)}>
          <option value="">선택</option>
          {(scopeType === 'REGION' ? regions : stores).map((x: any) => <option key={x.id} value={x.id}>{x.name}</option>)}
        </select>
      </>)}
    </>
  )
}

function CreateUser({ isAdmin, stores, regions, onClose, onDone }: any) {
  const [f, setF] = useState({ loginId: '', name: '', password: '', email: '', phone: '' })
  const [role, setRole] = useState('STAFF')
  const [scopeType, setScopeType] = useState('STORE')
  const [scopeId, setScopeId] = useState('')
  const [err, setErr] = useState<string | null>(null)
  const roles = isAdmin ? Object.keys(ROLE_NAMES) : ['STORE_MGR', 'STAFF']
  const submit = async () => {
    setErr(null)
    try {
      await api('/api/auth/users', { body: { ...f, assignments: [{ role, scopeType, scopeId: scopeType === 'ALL' ? null : scopeId }] } })
      onDone()
    } catch (e: any) { setErr(e.message) }
  }
  return (
    <Modal title="계정 만들기" onClose={onClose}>
      <form className="form" onSubmit={e => { e.preventDefault(); submit() }}>
        <label htmlFor="nu-login">아이디</label><input id="nu-login" value={f.loginId} onChange={e => setF({ ...f, loginId: e.target.value })} />
        <label htmlFor="nu-name">이름</label><input id="nu-name" value={f.name} onChange={e => setF({ ...f, name: e.target.value })} />
        <label htmlFor="nu-pw">초기 비밀번호 (8자 이상)</label><input id="nu-pw" type="password" value={f.password} onChange={e => setF({ ...f, password: e.target.value })} />
        <label htmlFor="nu-role">등급</label>
        <select id="nu-role" value={role} onChange={e => { setRole(e.target.value); setScopeType(['HQ_ADMIN', 'LOGISTICS_MGR', 'HR_MGR'].includes(e.target.value) ? 'ALL' : 'STORE'); setScopeId('') }}>
          {roles.map(r => <option key={r} value={r}>{ROLE_NAMES[r]}</option>)}
        </select>
        <ScopeFields role={role} scopeType={scopeType} setScopeType={setScopeType} scopeId={scopeId} setScopeId={setScopeId} stores={stores} regions={regions} />
        {err && <div className="error-box">{err}</div>}
        <button className="primary" disabled={!f.loginId || !f.name || f.password.length < 8 || (scopeType !== 'ALL' && !scopeId)}>만들기</button>
      </form>
    </Modal>
  )
}

function GrantRole({ user, stores, regions, onClose, onDone }: any) {
  const [role, setRole] = useState('STAFF')
  const [scopeType, setScopeType] = useState('STORE')
  const [scopeId, setScopeId] = useState('')
  const [err, setErr] = useState<string | null>(null)
  const submit = async () => {
    setErr(null)
    try { await api(`/api/auth/users/${user.id}/assignments`, { body: { role, scopeType, scopeId: scopeType === 'ALL' ? null : scopeId } }); onDone() }
    catch (e: any) { setErr(e.message) }
  }
  return (
    <Modal title={`${user.name} 등급 배정`} onClose={onClose}>
      <form className="form" onSubmit={e => { e.preventDefault(); submit() }}>
        <label htmlFor="g-role">등급</label>
        <select id="g-role" value={role} onChange={e => { setRole(e.target.value); setScopeType(['HQ_ADMIN', 'LOGISTICS_MGR', 'HR_MGR'].includes(e.target.value) ? 'ALL' : 'STORE'); setScopeId('') }}>
          {Object.keys(ROLE_NAMES).map(r => <option key={r} value={r}>{ROLE_NAMES[r]}</option>)}
        </select>
        <ScopeFields role={role} scopeType={scopeType} setScopeType={setScopeType} scopeId={scopeId} setScopeId={setScopeId} stores={stores} regions={regions} />
        {err && <div className="error-box">{err}</div>}
        <button className="primary" disabled={scopeType !== 'ALL' && !scopeId}>배정</button>
      </form>
    </Modal>
  )
}
