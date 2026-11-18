import { useState } from 'react'
import { api } from '../api'
import { useApi, Page, Badge, ErrorBox, Toast, Modal } from '../components/ui'
import { useSession } from '../session'
import { won, day } from '../format'

const TYPE: Record<string, string> = { RATE: '매출 %', FIXED: '정액', RATE_PLUS_FIXED: '% + 정액', TIERED: '구간별 %', NONE: '없음 (직영 등)' }
const BASE: Record<string, string> = { NET_SALES_EXCL_VAT: '순매출 (VAT 제외)', NET_SALES_INCL_VAT: '순매출 (VAT 포함)', GROSS_SALES_EXCL_VAT: '총매출 (VAT 제외)', GROSS_SALES_INCL_VAT: '총매출 (VAT 포함)' }

export function AdminRoyalty() {
  const s = useSession()
  const list = useApi<any[]>('/api/stl/royalty-policies')
  const [edit, setEdit] = useState<any>(null)
  const [msg, setMsg] = useState<string | null>(null)
  const name = (id: string) => s.stores.find(x => x.id === id)?.name
  return (
    <Page title="로열티" sub="매장별 로열티 정책 · 기본 기준은 VAT 제외 순매출 · 월 마감 때 계산되어 정산서에 청구"
      actions={<button className="primary" onClick={() => setEdit({ store_id: s.stores[0]?.id, type: 'RATE', base: 'NET_SALES_EXCL_VAT' })}>+ 정책</button>}>
      <ErrorBox error={list.error} />
      <div className="card table-wrap">
        <table className="tbl">
          <thead><tr><th>매장</th><th>유형</th><th>기준</th><th className="num">요율</th><th className="num">정액</th><th className="num">최소/최대</th><th>적용 기간</th><th></th></tr></thead>
          <tbody>{(list.data ?? []).map(p => (
            <tr key={p.id} className={p.valid_to ? 'inactive' : ''}>
              <td>{name(p.store_id)}</td><td>{TYPE[p.type]}</td><td>{BASE[p.base]}</td>
              <td className="num">{p.rate != null ? `${(Number(p.rate) * 100).toFixed(2)}%` : '—'}</td><td className="num">{p.fixed_amount != null ? won(p.fixed_amount) : '—'}</td>
              <td className="num">{p.min_amount != null || p.max_amount != null ? `${won(p.min_amount)} / ${won(p.max_amount)}` : '—'}</td>
              <td>{day(p.valid_from)} ~ {p.valid_to ? day(p.valid_to) : <Badge tone="ok">적용 중</Badge>}</td>
              <td>{!p.valid_to && <button className="ghost small" onClick={() => setEdit(p)}>변경</button>}</td>
            </tr>))}</tbody>
        </table>
      </div>
      {edit && <PolicyModal p={edit} stores={s.stores} onClose={() => setEdit(null)} onDone={() => { setEdit(null); setMsg('저장했습니다 (적용 시작일부터)'); list.reload() }} />}
      <Toast msg={msg} onDone={() => setMsg(null)} />
    </Page>
  )
}

function PolicyModal({ p, stores, onClose, onDone }: any) {
  const firstOfMonth = new Date(Date.now() + 9 * 3600e3).toISOString().slice(0, 8) + '01'
  const [f, setF] = useState({ storeId: p.store_id, type: p.type, base: p.base, rate: p.rate != null ? String(Number(p.rate) * 100) : '', fixedAmount: p.fixed_amount != null ? String(p.fixed_amount) : '',
    minAmount: p.min_amount != null ? String(p.min_amount) : '', maxAmount: p.max_amount != null ? String(p.max_amount) : '', tiers: p.tiers_text ?? '[{"from":0,"to":30000000,"rate":0.03},{"from":30000000,"rate":0.02}]', validFrom: firstOfMonth })
  const [err, setErr] = useState<string | null>(null)
  const num = (v: string) => v === '' ? null : Number(v)
  const save = async () => {
    setErr(null)
    try {
      await api('/api/stl/royalty-policies', { body: { storeId: f.storeId, type: f.type, base: f.base, rate: f.rate === '' ? null : Number(f.rate) / 100,
        fixedAmount: num(f.fixedAmount), minAmount: num(f.minAmount), maxAmount: num(f.maxAmount), tiers: f.type === 'TIERED' ? f.tiers : null, validFrom: f.validFrom } })
      onDone()
    } catch (e: any) { setErr(e.message) }
  }
  return (
    <Modal title="로열티 정책" onClose={onClose}>
      <form className="form" onSubmit={e => { e.preventDefault(); save() }}>
        <label htmlFor="ro-store">매장</label><select id="ro-store" value={f.storeId} onChange={e => setF({ ...f, storeId: e.target.value })}>{stores.map((s: any) => <option key={s.id} value={s.id}>{s.name}</option>)}</select>
        <div className="row">
          <div><label htmlFor="ro-type">유형</label><select id="ro-type" value={f.type} onChange={e => setF({ ...f, type: e.target.value })}>{Object.entries(TYPE).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></div>
          <div><label htmlFor="ro-base">기준</label><select id="ro-base" value={f.base} onChange={e => setF({ ...f, base: e.target.value })}>{Object.entries(BASE).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></div>
        </div>
        {['RATE', 'RATE_PLUS_FIXED'].includes(f.type) && <><label htmlFor="ro-rate">요율 (%)</label><input id="ro-rate" inputMode="decimal" value={f.rate} onChange={e => setF({ ...f, rate: e.target.value })} /></>}
        {['FIXED', 'RATE_PLUS_FIXED'].includes(f.type) && <><label htmlFor="ro-fixed">정액 (원/월)</label><input id="ro-fixed" inputMode="numeric" value={f.fixedAmount} onChange={e => setF({ ...f, fixedAmount: e.target.value.replace(/\D/g, '') })} /></>}
        {f.type === 'TIERED' && <><label htmlFor="ro-tiers">구간 (JSON)</label><textarea id="ro-tiers" rows={3} value={f.tiers} onChange={e => setF({ ...f, tiers: e.target.value })} /></>}
        {f.type !== 'NONE' && <div className="row">
          <div><label htmlFor="ro-min">최소 (원)</label><input id="ro-min" inputMode="numeric" value={f.minAmount} onChange={e => setF({ ...f, minAmount: e.target.value.replace(/\D/g, '') })} /></div>
          <div><label htmlFor="ro-max">최대 (원)</label><input id="ro-max" inputMode="numeric" value={f.maxAmount} onChange={e => setF({ ...f, maxAmount: e.target.value.replace(/\D/g, '') })} /></div>
        </div>}
        <label htmlFor="ro-from">적용 시작일</label><input id="ro-from" type="date" value={f.validFrom} onChange={e => setF({ ...f, validFrom: e.target.value })} />
        {err && <div className="error-box">{err}</div>}
        <button className="primary">저장</button>
      </form>
    </Modal>
  )
}
