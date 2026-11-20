import { useState } from 'react'
import { api } from '../api'
import { useApi, Page, Badge, ErrorBox, Toast, Empty, Modal } from '../components/ui'
import { won, dt } from '../format'

const lastMonth = () => { const d = new Date(Date.now() + 9 * 3600e3); d.setUTCDate(1); d.setUTCMonth(d.getUTCMonth() - 1); return d.toISOString().slice(0, 7) }

/** 투자자 지급액 결정 (인사 메뉴): 월 결산액 × 출자 비율(투자자 출자 ÷ 매장 구성 총액) → 지급액 결정 → 지급 완료 */
export function InvestorPayouts() {
  const [month, setMonth] = useState(lastMonth())
  const period = month.replace('-', '')
  const d = useApi<any[]>(`/api/stl/investor-payouts?period=${period}`, [period])
  const [edit, setEdit] = useState<any>(null)
  const [msg, setMsg] = useState<string | null>(null)
  const rows = d.data ?? []
  const sum = (f: (r: any) => number) => rows.reduce((t, r) => t + f(r), 0)
  return (
    <Page title="투자자 지급" sub="이익금 배분: 월 결산액 × 출자 비율 · 출자 비율 = 투자자 출자 ÷ (투자자 출자 + 본사 지원)"
      actions={<input id="ip-month" type="month" aria-label="귀속월" value={month} onChange={e => setMonth(e.target.value)} />}>
      <ErrorBox error={d.error} />
      <div className="grid3">
        <div className="card stat"><small>계산된 배분액 합계</small><b className="tnum">{won(sum(r => r.calculated_amount))}원</b></div>
        <div className="card stat"><small>결정된 지급액 합계</small><b className="tnum">{won(sum(r => r.payout?.decided_amount ?? 0))}원</b></div>
        <div className="card stat"><small>지급 완료</small><b>{rows.filter(r => r.payout?.status === 'PAID').length} / {rows.length}건</b></div>
      </div>
      <div className="card table-wrap">
        <table className="tbl">
          <thead><tr><th>투자자</th><th>매장</th><th className="num">투자자 출자</th><th className="num">매장 구성 총액</th><th className="num">출자 비율</th><th className="num">결산액(이익)</th><th className="num">계산 배분액</th><th className="num">결정 지급액</th><th>상태</th><th></th></tr></thead>
          <tbody>{rows.map(r => (
            <tr key={r.investor_id + r.store_id}>
              <td><b>{r.investor_name}</b></td><td>{r.store_name}</td>
              <td className="num">{won(r.investor_amount)}</td><td className="num">{won(r.store_total)}</td>
              <td className="num">{(Number(r.share_ratio) * 100).toFixed(2)}%</td>
              <td className="num">{won(r.profit)} {!r.closed && <Badge tone="warn">마감 전</Badge>}</td>
              <td className="num">{won(r.calculated_amount)}</td>
              <td className="num"><b>{r.payout ? won(r.payout.decided_amount) : '—'}</b></td>
              <td>{r.payout ? <Badge tone={r.payout.status === 'PAID' ? 'ok' : 'info'}>{r.payout.status === 'PAID' ? `지급 완료 ${dt(r.payout.paid_at)}` : '결정'}</Badge> : <Badge tone="warn">미결정</Badge>}</td>
              <td className="nowrap"><button className="small" onClick={() => setEdit(r)}>{r.payout ? '수정' : '지급액 결정'}</button></td>
            </tr>))}</tbody>
        </table>
        {d.data && rows.length === 0 && <Empty>투자자 출자 항목이 있는 매장이 없습니다. 투자 관리에서 항목의 자금 출처를 "투자자 출자"로 정하세요.</Empty>}
      </div>
      <p className="muted small">결산액이 음수면 계산 배분액은 0원입니다. 마감 전 달은 실시간 계산값이라 바뀔 수 있으니, 보통 월 마감 뒤에 결정합니다.</p>
      {edit && <DecideModal row={edit} period={period} onClose={() => setEdit(null)} onDone={(m: string) => { setEdit(null); setMsg(m); d.reload() }} />}
      <Toast msg={msg} onDone={() => setMsg(null)} />
    </Page>
  )
}

function DecideModal({ row, period, onClose, onDone }: any) {
  const [amount, setAmount] = useState(String(row.payout?.decided_amount ?? row.calculated_amount))
  const [memo, setMemo] = useState(row.payout?.memo ?? '')
  const [err, setErr] = useState<string | null>(null)
  const save = async (status: string) => {
    setErr(null)
    try { await api('/api/stl/investor-payouts', { method: 'PUT', body: { investorId: row.investor_id, storeId: row.store_id, period, decidedAmount: Number(amount), status, memo } }); onDone(status === 'PAID' ? '지급 완료로 기록했습니다' : '지급액을 결정했습니다') }
    catch (e: any) { setErr(e.message) }
  }
  return (
    <Modal title={`${row.investor_name} · ${row.store_name} · ${period.slice(0, 4)}-${period.slice(4)}`} onClose={onClose}>
      <form className="form" onSubmit={e => { e.preventDefault(); save('DECIDED') }}>
        <p className="small">결산액 {won(row.profit)}원 × 출자 비율 {(Number(row.share_ratio) * 100).toFixed(2)}% = 계산 배분액 <b>{won(row.calculated_amount)}원</b></p>
        <label htmlFor="po-amt">지급액 (원)</label>
        <input id="po-amt" required inputMode="numeric" value={amount} onChange={e => setAmount(e.target.value.replace(/\D/g, ''))} />
        <label htmlFor="po-memo">메모</label><input id="po-memo" value={memo} placeholder="예: 운영 유보금 10% 차감" onChange={e => setMemo(e.target.value)} />
        {err && <div className="error-box">{err}</div>}
        <div className="act-buttons"><button>지급액 결정</button><button type="button" className="primary" onClick={e => { const f = (e.target as HTMLElement).closest('form'); if (f && !f.reportValidity()) return; save('PAID') }}>지급 완료</button></div>
      </form>
    </Modal>
  )
}
