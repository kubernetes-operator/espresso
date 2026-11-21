import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '../api'
import { useApi, Page, Badge, ErrorBox, Toast, Modal } from '../components/ui'
import { useSession } from '../session'
import { SITE_TYPES, won, num } from '../format'

export function AdminStores() {
  const s = useSession()
  const nav = useNavigate()
  const list = useApi<any[]>('/api/core/stores')
  const regions = useApi<any[]>('/api/core/regions')
  const [addRegion, setAddRegion] = useState<any>(null)
  const [leaseFor, setLeaseFor] = useState<any>(null)
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
                <td><b>{st.name}</b> <code>{st.code}</code>{st.site_type !== 'HQ' && <><br /><button className="ghost small" onClick={() => setLeaseFor(st)}>임대 계약</button></>}</td>
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
      {leaseFor && <LeaseModal store={leaseFor} onClose={() => setLeaseFor(null)} onMsg={setMsg} />}
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

/** 임대 계약: 기간(시작·종료) 필수, 수정 가능. 월세·관리비 정기 비용과 주간 결산 고정비(일할)에 쓰임 */
function LeaseModal({ store, onClose, onMsg }: any) {
  const list = useApi<any[]>(`/api/core/stores/${store.id}/leases`)
  const [edit, setEdit] = useState<any>(null)
  const [err, setErr] = useState<string | null>(null)
  const blank = { lessorName: '', deposit: '', monthlyRent: '', maintenanceFee: '', rentVatType: 'TAXABLE', paymentDay: '25', startDate: '', endDate: '', memo: '' }
  const [f, setF] = useState<Record<string, string>>(blank)
  const open = (l: any) => {
    setErr(null)
    setEdit(l ?? {})
    setF(l ? { lessorName: l.lessor_name ?? '', deposit: String(l.deposit), monthlyRent: String(l.monthly_rent), maintenanceFee: String(l.maintenance_fee), rentVatType: l.rent_vat_type,
      paymentDay: String(l.payment_day ?? ''), startDate: String(l.start_date).slice(0, 10), endDate: l.end_date ? String(l.end_date).slice(0, 10) : '', memo: l.memo ?? '' } : blank)
  }
  const save = async () => {
    setErr(null)
    const body = { ...f, deposit: Number(f.deposit || 0), monthlyRent: Number(f.monthlyRent || 0), maintenanceFee: Number(f.maintenanceFee || 0), paymentDay: f.paymentDay ? Number(f.paymentDay) : null }
    try {
      if (edit.id) await api(`/api/core/stores/leases/${edit.id}`, { method: 'PUT', body }); else await api(`/api/core/stores/${store.id}/leases`, { body })
      onMsg('임대 계약을 저장했습니다'); setEdit(null); list.reload()
    } catch (e: any) { setErr(e.message) }
  }
  const del = async (l: any) => {
    if (!confirm('이 임대 계약을 지울까요? 이미 만든 월세·관리비 비용은 그대로 남습니다.')) return
    try { await api(`/api/core/stores/leases/${l.id}`, { method: 'DELETE' }); list.reload() } catch (e: any) { setErr(e.message) }
  }
  const money = (k: string, l: string, req = false) => <div><label htmlFor={`ls-${k}`}>{l}</label><input id={`ls-${k}`} required={req} inputMode="numeric" value={num(f[k])} onChange={e => setF({ ...f, [k]: e.target.value.replace(/\D/g, '') })} /></div>
  return (
    <Modal title={`${store.name} 임대 계약`} onClose={onClose} wide>
      {err && <div className="error-box">{err}</div>}
      {!edit ? <>
        <div className="table-wrap"><table className="tbl compact">
          <thead><tr><th>기간</th><th>임대인</th><th className="num">보증금</th><th className="num">월세</th><th className="num">관리비</th><th>과세</th><th>납부일</th><th></th></tr></thead>
          <tbody>{(list.data ?? []).map(l => (
            <tr key={l.id}><td>{String(l.start_date).slice(0, 10)} ~ {l.end_date ? String(l.end_date).slice(0, 10) : <span className="overdue">종료일 없음</span>}</td><td>{l.lessor_name ?? '—'}</td>
              <td className="num">{won(l.deposit)}</td><td className="num">{won(l.monthly_rent)}</td><td className="num">{won(l.maintenance_fee)}</td>
              <td>{l.rent_vat_type === 'EXEMPT' ? '면세' : '과세'}</td><td>{l.payment_day ?? '—'}일</td>
              <td className="nowrap"><button className="ghost small" onClick={() => open(l)}>수정</button><button className="ghost small" onClick={() => del(l)}>삭제</button></td></tr>))}</tbody>
        </table></div>
        {list.data?.length === 0 && <p className="muted small">임대 계약이 없습니다.</p>}
        <p className="muted small">월세·관리비는 공급가(VAT 제외)입니다. 비용 화면의 [월세·관리비 생성]과 결산 주 마감의 고정비(일할 배분)가 이 계약을 씁니다. 계약이 바뀌면 기존 계약 종료일을 바꾸고 새 계약을 추가하세요.</p>
        <button className="primary" onClick={() => open(null)}>+ 임대 계약</button>
      </> : <form className="form" onSubmit={e => { e.preventDefault(); save() }}>
        <div className="row">
          <div><label htmlFor="ls-start">계약 시작일</label><input id="ls-start" required type="date" value={f.startDate} onChange={e => setF({ ...f, startDate: e.target.value })} /></div>
          <div><label htmlFor="ls-end">계약 종료일</label><input id="ls-end" required type="date" value={f.endDate} onChange={e => setF({ ...f, endDate: e.target.value })} /></div>
          <div><label htmlFor="ls-lessor">임대인</label><input id="ls-lessor" value={f.lessorName} onChange={e => setF({ ...f, lessorName: e.target.value })} /></div>
        </div>
        <div className="row">{money('deposit', '보증금')}{money('monthlyRent', '월세 (공급가)', true)}{money('maintenanceFee', '관리비 (공급가)')}</div>
        <div className="row">
          <div><label htmlFor="ls-vat">과세</label><select id="ls-vat" value={f.rentVatType} onChange={e => setF({ ...f, rentVatType: e.target.value })}><option value="TAXABLE">과세</option><option value="EXEMPT">면세</option></select></div>
          <div><label htmlFor="ls-day">납부일</label><input id="ls-day" inputMode="numeric" value={f.paymentDay} onChange={e => setF({ ...f, paymentDay: e.target.value.replace(/\D/g, '') })} /></div>
          <div><label htmlFor="ls-memo">메모</label><input id="ls-memo" value={f.memo} onChange={e => setF({ ...f, memo: e.target.value })} /></div>
        </div>
        <div className="act-buttons"><button type="button" onClick={() => setEdit(null)}>목록</button><button className="primary">저장</button></div>
      </form>}
    </Modal>
  )
}
