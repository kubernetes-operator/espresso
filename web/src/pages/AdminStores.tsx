import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '../api'
import { useApi, Page, Badge, ErrorBox, Toast, Modal } from '../components/ui'
import { useSession } from '../session'
import { SITE_TYPES } from '../format'

export function AdminStores() {
  const s = useSession()
  const nav = useNavigate()
  const list = useApi<any[]>('/api/core/stores')
  const regions = useApi<any[]>('/api/core/regions')
  const [addRegion, setAddRegion] = useState<any>(null)
  const [msg, setMsg] = useState<string | null>(null)
  const [err, setErr] = useState<string | null>(null)

  const save = async (id: string, body: any, label: string) => {
    setErr(null)
    try { await api(`/api/core/stores/${id}`, { method: 'PUT', body }); setMsg(label); list.reload(); s.reloadStores() }
    catch (e: any) { setErr(e.message) }
  }

  return (
    <Page title="매장 관리" sub="총괄 관리자 전용" actions={<button className="primary" onClick={() => nav('/admin/stores/new')}>+ 매장 생성</button>}>
      <ErrorBox error={list.error || err} />
      <div className="table-wrap card">
        <table className="tbl">
          <thead><tr><th>매장</th><th>구분</th><th>권역</th><th>형태</th><th>상태</th><th>VAT 표시 기본값</th><th>점주 개인별 급여 열람</th><th>5인 이상 (가산)</th></tr></thead>
          <tbody>
            {(list.data ?? []).map(st => (
              <tr key={st.id}>
                <td><b>{st.name}</b> <code>{st.code}</code></td>
                <td><Badge tone={st.site_type === 'BAKERY' ? 'info' : st.site_type === 'HQ' ? 'accent' : 'neutral'}>{SITE_TYPES[st.site_type ?? 'STORE']}</Badge></td>
                <td>{st.site_type === 'HQ' ? '—' : <select aria-label="권역" value={st.region_id ?? ''} onChange={e => save(st.id, { regionId: e.target.value }, `${st.name} 권역을 바꿨습니다`)}>
                  <option value="" disabled>선택</option>
                  {(regions.data ?? []).map(r => <option key={r.id} value={r.id}>{r.level === 'SUB' ? '　· ' : ''}{r.name}</option>)}
                </select>}</td>
                <td>{st.business_type === 'DIRECT' ? '직영' : '가맹'}</td>
                <td><Badge tone={st.status === 'OPEN' ? 'ok' : 'warn'}>{st.status}</Badge></td>
                <td>
                  <select id={`vat-${st.id}`} aria-label="VAT 표시 기본값" value={st.vat_display_mode}
                    onChange={e => save(st.id, { vatDisplayMode: e.target.value }, `${st.name} VAT 표시를 바꿨습니다`)}>
                    <option value="EXCLUSIVE">VAT 별도</option><option value="INCLUSIVE">VAT 포함</option>
                  </select>
                </td>
                <td>
                  <label className="switch"><input type="checkbox" checked={st.owner_payroll_detail_visible}
                    onChange={e => save(st.id, { ownerPayrollDetailVisible: e.target.checked }, `${st.name} 점주 급여 열람 설정을 바꿨습니다`)} />
                    <span>{st.owner_payroll_detail_visible ? '허용 (개인별)' : '미허용 (총액만)'}</span></label>
                </td>
                <td>
                  <label className="switch"><input type="checkbox" checked={st.five_plus}
                    onChange={e => save(st.id, { fivePlus: e.target.checked }, `${st.name} 5인 이상 여부를 바꿨습니다`)} />
                    <span>{st.five_plus ? '적용' : '미적용'}</span></label>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="muted small">매장 관리자는 직원·근무에서 아르바이트 시급만 보고, 다른 직원의 급여는 보지 않습니다.</p>
      <section className="card">
        <div className="side-title"><h2>권역</h2><button className="small" onClick={() => setAddRegion({})}>+ 권역</button></div>
        <p className="muted small">기본은 특별시·광역시·특별자치시·도 단위입니다. 필요하면 시·도 아래에 하위 권역(예: 강남권)을 추가합니다. 매장이 있는 권역은 지울 수 없습니다.</p>
        <div className="chips">
          {(regions.data ?? []).map(r => (
            <span key={r.id} className="chip">{r.level === 'SUB' && '└ '}{r.name} <small className="muted">{r.store_count}</small>
              {Number(r.store_count) === 0 && <button className="chip-x" aria-label={`${r.name} 삭제`} title="삭제"
                onClick={async () => { setErr(null); try { await api(`/api/core/regions/${r.id}`, { method: 'DELETE' }); setMsg('권역을 지웠습니다'); regions.reload() } catch (e: any) { setErr(e.message) } }}>×</button>}</span>))}
        </div>
      </section>
      {addRegion && <RegionModal regions={regions.data ?? []} onClose={() => setAddRegion(null)} onDone={() => { setAddRegion(null); setMsg('권역을 추가했습니다'); regions.reload() }} />}
      <Toast msg={msg} onDone={() => setMsg(null)} />
    </Page>
  )
}

function RegionModal({ regions, onClose, onDone }: any) {
  const [name, setName] = useState('')
  const [parentId, setParentId] = useState('')
  const [err, setErr] = useState<string | null>(null)
  const save = async () => {
    setErr(null)
    try { await api('/api/core/regions', { body: { name, parentId: parentId || null } }); onDone() } catch (e: any) { setErr(e.message) }
  }
  return (
    <Modal title="권역 추가" onClose={onClose}>
      <form className="form" onSubmit={e => { e.preventDefault(); save() }}>
        <label htmlFor="rg-parent">상위 권역</label>
        <select id="rg-parent" value={parentId} onChange={e => setParentId(e.target.value)}>
          <option value="">없음 (시·도 단위로 추가)</option>
          {regions.filter((r: any) => r.level === 'SIDO').map((r: any) => <option key={r.id} value={r.id}>{r.name}</option>)}
        </select>
        <label htmlFor="rg-name">이름</label><input id="rg-name" required value={name} placeholder="예: 강남권" onChange={e => setName(e.target.value)} />
        {err && <div className="error-box">{err}</div>}
        <button className="primary">추가</button>
      </form>
    </Modal>
  )
}
