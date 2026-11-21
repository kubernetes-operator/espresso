import { useMemo, useState } from 'react'
import { useApi, Page, Badge, ErrorBox } from '../components/ui'
import { useSession } from '../session'

type Asg = { id: string; role: string; roleName: string; line: string; rank: number; roleActive: boolean; scopeType: string; scopeId: string | null }
type U = { id: string; loginId: string; name: string; status: string; assignments: Asg[] }
type StoreName = { id: string; code: string; name: string; region_id: string; region_name: string; status: string; site_type?: string }

function Person({ u, me, note }: { u: U; me: string | undefined; note?: string }) {
  return (
    <div className={`person ${u.id === me ? 'me' : ''}`}>
      <b>{u.name}</b> <code>{u.loginId}</code>{u.id === me && <Badge tone="accent">나</Badge>}
      {note && <small>{note}</small>}
    </div>
  )
}

export function Org() {
  const s = useSession()
  const org = useApi<{ roles: any[]; users: U[] }>('/api/auth/org')
  const stores = useApi<StoreName[]>('/api/core/stores/names')
  const [view, setView] = useState<'people' | 'roles'>('people')
  const me = s.user?.id

  const data = useMemo(() => {
    const users = org.data?.users ?? []
    const byRole = (role: string, pred?: (a: Asg) => boolean) =>
      users.filter(u => u.assignments.some(a => a.role === role && (!pred || pred(a))))
    const scopeNote = (u: U, role: string) => {
      const a = u.assignments.filter(x => x.role === role)
      if (a.some(x => x.scopeType === 'ALL')) return '전 매장 담당'
      const names = a.map(x => x.scopeType === 'REGION'
        ? (stores.data?.find(st => st.region_id === x.scopeId)?.region_name ?? '권역')
        : (stores.data?.find(st => st.id === x.scopeId)?.name ?? '매장'))
      return [...new Set(names)].join(', ') + ' 담당'
    }
    const regions = new Map<string, { name: string; stores: StoreName[] }>()
    const hqView = s.has('HQ_ADMIN', 'LOGISTICS_MGR', 'HR_MGR')
    // 본사 등급이 아니면 사람이 보이는 매장만 (자기 매장·투자 매장·다른 매장의 매장 관리자)
    const visible = (stores.data ?? []).filter(st => st.site_type !== 'HQ' && (hqView || users.some(u => u.assignments.some(a => a.scopeId === st.id))))
    for (const st of visible) {
      const r = regions.get(st.region_id) ?? { name: st.region_name ?? '미지정', stores: [] }
      r.stores.push(st); regions.set(st.region_id, r)
    }
    return { byRole, scopeNote, regions: [...regions.values()] }
  }, [org.data, stores.data])

  const atStore = (role: string, storeId: string) =>
    data.byRole(role, a => a.scopeType === 'STORE' && a.scopeId === storeId)

  return (
    <Page title="조직도" sub="본사 라인과 매장 라인, 매장별 인원 배치"
      actions={<div className="seg">
        <button className={view === 'people' ? 'on' : ''} onClick={() => setView('people')}>실제 조직</button>
        <button className={view === 'roles' ? 'on' : ''} onClick={() => setView('roles')}>등급 구조</button>
      </div>}>
      <ErrorBox error={org.error || stores.error} />
      {view === 'roles' ? (
        <div className="org-roles card">
          <div className="org-node top"><b>총괄 관리자</b><small>최상위 · 전 매장 · 물류·인사 권한 포함</small></div>
          <div className="org-arrow">▼</div>
          <div className="org-lines">
            <div className="org-branch hq">
              <div className="branch-label">본사 라인</div>
              <div className="org-node hq"><b>슈퍼바이저</b><small>권역 담당 · 물류·인사 권한 포함</small></div>
              <div className="org-arrow">▼</div>
              <div className="org-row">
                <div className="org-node hq"><b>물류 관리자</b><small>물류 승인·발송·단가</small></div>
                <div className="org-node hq"><b>인사 관리자</b><small>직원·근무·급여</small></div>
              </div>
            </div>
            <div className="org-branch store">
              <div className="branch-label">매장 라인</div>
              <div className="org-node store"><b>점주</b><small>자기 매장 전체</small></div>
              <div className="org-arrow">▼</div>
              <div className="org-node store"><b>매장 관리자</b><small>매장 운영</small></div>
              <div className="org-arrow">▼</div>
              <div className="org-node store"><b>일반 사원</b><small>요청·수령·출퇴근</small></div>
            </div>
          </div>
          <div className="org-node ext"><b>투자자</b><small>계층 밖 · 공개된 투자 정보만</small></div>
        </div>
      ) : (
        <div className="org-people">
          <section className="card">
            <h2>본사</h2>
            <div className="org-hq">
              <div className="org-col"><div className="col-label">총괄 관리자</div>
                {data.byRole('HQ_ADMIN').map(u => <Person key={u.id} u={u} me={me} />)}</div>
              <div className="org-col"><div className="col-label">물류 관리자</div>
                {data.byRole('LOGISTICS_MGR').map(u => <Person key={u.id} u={u} me={me} note={data.scopeNote(u, 'LOGISTICS_MGR')} />)}</div>
              <div className="org-col"><div className="col-label">인사 관리자</div>
                {data.byRole('HR_MGR').map(u => <Person key={u.id} u={u} me={me} note={data.scopeNote(u, 'HR_MGR')} />)}</div>
              <div className="org-col"><div className="col-label">슈퍼바이저</div>
                {data.byRole('SUPERVISOR').map(u => <Person key={u.id} u={u} me={me} note={data.scopeNote(u, 'SUPERVISOR')} />)}</div>
            </div>
          </section>
          {data.regions.map(r => (
            <section key={r.name} className="card">
              <h2>{r.name}</h2>
              <div className="org-stores">
                {r.stores.map(st => (
                  <div key={st.id} className="org-store">
                    <div className="store-title"><b>{st.name}</b> <code>{st.code}</code></div>
                    {st.site_type === 'BAKERY' && <span className="badge info">베이커리</span>}
                    {!['OWNER', 'STORE_MGR', 'STAFF', 'INVESTOR'].some(r => atStore(r, st.id).length) && <div className="muted small">배정된 인원이 없습니다.</div>}
                    {[['OWNER', '점주'], ['STORE_MGR', st.site_type === 'BAKERY' ? '베이커리 관리자' : '매장 관리자'], ['STAFF', '일반 사원'], ['INVESTOR', '투자자']].map(([role, label]) => {
                      const people = atStore(role, st.id)
                      if (!people.length) return null
                      return (
                        <div key={role} className={`store-role r-${role}`}>
                          <div className="col-label">{label}</div>
                          {people.map(u => <Person key={u.id} u={u} me={me}
                            note={role === 'OWNER' && u.assignments.filter(a => a.role === 'OWNER').length > 1 ? `점주 매장 ${u.assignments.filter(a => a.role === 'OWNER').length}곳` : undefined} />)}
                        </div>
                      )
                    })}
                  </div>
                ))}
              </div>
            </section>
          ))}
          <p className="muted small">점주·매장 관리자·사원은 본사 인원, 자기 매장 인원, 다른 매장의 매장 관리자만 봅니다. 투자자는 본사 인원과 투자한 매장 인원만 봅니다.</p>
        </div>
      )}
    </Page>
  )
}
