import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '../api'
import { useApi, Page, Badge, ErrorBox, Empty, Modal, Toast } from '../components/ui'
import { useSession } from '../session'
import { won, dt, DIVISIONS } from '../format'

const DIV = DIVISIONS
const REASON: Record<string, string> = { RECEIPT: '입고', SALE: '판매', LOSS: '손실', SHORTAGE: '결손', INTERNAL_USE: '내부사용', COUNT_ADJ: '실사 조정', TRANSFER_IN: '이동 입고', TRANSFER_OUT: '이동 출고', OPENING: '기초' }

export function Inventory() {
  const s = useSession()
  const nav = useNavigate()
  const storeQ = s.storeId !== 'ALL' ? `?storeId=${s.storeId}` : ''
  const list = useApi<any[]>(`/api/core/inventory${storeQ}`, [s.storeId])
  const [division, setDivision] = useState('')
  const [q, setQ] = useState('')
  const [ledger, setLedger] = useState<any>(null)
  const [msg, setMsg] = useState<string | null>(null)
  const [err, setErr] = useState<string | null>(null)
  const rows = useMemo(() => (list.data ?? []).filter(r => (!division || r.division_code === division) && (!q || r.item_name.includes(q))), [list.data, division, q])
  const total = rows.reduce((t, r) => t + Number(r.stock_value), 0)
  const byDiv = Object.keys(DIV).map(k => ({ k, v: (list.data ?? []).filter(r => r.division_code === k).reduce((t, r) => t + Number(r.stock_value), 0) }))
  const single = s.storeId !== 'ALL'
  const canCount = single && s.has('OWNER', 'STORE_MGR', 'STAFF', 'HQ_ADMIN')

  const startCount = async () => {
    setErr(null)
    try { const r: any = await api('/api/core/inventory/counts', { body: { storeId: s.storeId } }); nav(`/inventory/count/${r.id}`) }
    catch (e: any) {
      if (e.code === 'CONFLICT') {
        const cs: any[] = await api(`/api/core/inventory/counts?storeId=${s.storeId}`)
        const open = cs.find(c => c.status === 'IN_PROGRESS'); if (open) nav(`/inventory/count/${open.id}`)
      } else setErr(e.message)
    }
  }
  return (
    <Page title="재고" sub={`${s.currentStore?.name ?? '전체 매장 합산'} · 이동평균 단가(공급가) 기준`}
      actions={<>
        {canCount && <button onClick={startCount}>재고 실사</button>}
        <button className="primary" onClick={() => nav('/adjustments/new')}>손실·결손 등록</button>
      </>}>
      <ErrorBox error={list.error || err} />
      <div className="grid3">
        <div className="card stat"><small>재고 금액 (표시 범위)</small><b className="tnum">{won(total)}원</b><span className="muted small">{rows.length}개 품목</span></div>
        {byDiv.map(d => <div key={d.k} className="card stat"><small>{DIV[d.k]}</small><b className="tnum">{won(d.v)}원</b></div>)}
      </div>
      <div className="filters">
        <div className="seg small">{[['', '전체'], ...Object.entries(DIV)].map(([k, v]) => <button key={k} className={division === k ? 'on' : ''} onClick={() => setDivision(k)}>{v}</button>)}</div>
        <input id="inv-q" aria-label="검색" placeholder="품명 검색" value={q} onChange={e => setQ(e.target.value)} />
      </div>
      <div className="card table-wrap">
        <table className="tbl">
          <thead><tr>{!single && <th>매장</th>}<th>품목</th><th>구분</th><th className="num">수량</th><th className="num">평균 단가</th><th className="num">재고 금액</th><th className="num">안전재고</th><th>갱신</th><th></th></tr></thead>
          <tbody>
            {rows.map(r => (
              <tr key={r.store_id + r.item_id}>
                {!single && <td>{r.store_name}</td>}
                <td>{r.item_name} {r.low && <Badge tone="warn">부족</Badge>}</td>
                <td><small>{DIV[r.division_code]} · {r.category_name}</small></td>
                <td className="num">{Number(r.qty)} {r.unit}</td>
                <td className="num">{won(r.avg_cost)}</td>
                <td className="num">{won(r.stock_value)}</td>
                <td className="num">{single && s.has('OWNER', 'STORE_MGR', 'HQ_ADMIN', 'LOGISTICS_MGR')
                  ? <input aria-label="안전재고" className="mini" inputMode="decimal" defaultValue={r.safety_qty ?? ''} onBlur={async e => {
                      try { await api('/api/core/inventory/safety', { method: 'PUT', body: { storeId: r.store_id, itemId: r.item_id, safetyQty: e.target.value === '' ? null : Number(e.target.value) } }); setMsg('안전재고를 저장했습니다'); list.reload() } catch (x: any) { setErr(x.message) }
                    }} /> : r.safety_qty ?? '—'}</td>
                <td>{dt(r.updated_at)}</td>
                <td><button className="ghost small" onClick={() => setLedger(r)}>원장</button></td>
              </tr>
            ))}
          </tbody>
        </table>
        {rows.length === 0 && <Empty>재고가 없습니다. 물류 수령 검수를 하면 입고됩니다.</Empty>}
      </div>
      {ledger && <LedgerModal row={ledger} onClose={() => setLedger(null)} />}
      <Toast msg={msg} onDone={() => setMsg(null)} />
    </Page>
  )
}

function LedgerModal({ row, onClose }: any) {
  const l = useApi<any[]>(`/api/core/inventory/ledger?storeId=${row.store_id}&itemId=${row.item_id}`)
  return (
    <Modal title={`재고 원장 · ${row.item_name}`} onClose={onClose} wide>
      <div className="table-wrap">
        <table className="tbl compact">
          <thead><tr><th>일시</th><th>구분</th><th className="num">수량</th><th className="num">단가</th><th className="num">금액</th><th className="num">잔량</th></tr></thead>
          <tbody>{(l.data ?? []).map((x, i) => (
            <tr key={i}><td>{dt(x.occurred_at)}</td><td>{REASON[x.reason] ?? x.reason}</td>
              <td className="num">{Number(x.qty_delta) > 0 ? '+' : ''}{Number(x.qty_delta)}</td><td className="num">{won(x.unit_cost)}</td>
              <td className="num">{won(x.amount)}</td><td className="num">{Number(x.balance_qty)}</td></tr>))}</tbody>
        </table>
      </div>
    </Modal>
  )
}
