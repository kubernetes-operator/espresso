import { useState } from 'react'
import { api } from '../api'
import { useApi, Page, Badge, ErrorBox, Toast, Modal, Empty } from '../components/ui'
import { useSession } from '../session'
import { won, dt, day } from '../format'
import { useNames } from '../components/directory'

const ST: Record<string, string> = { DRAFT: '작성', ISSUED: '발행', PARTIALLY_PAID: '부분 입금', PAID: '완납', OVERDUE: '연체', CANCELLED: '취소' }
const TONE: Record<string, any> = { ISSUED: 'info', PARTIALLY_PAID: 'warn', PAID: 'ok', OVERDUE: 'bad', CANCELLED: 'neutral' }
const TYPE: Record<string, string> = { HQ_SUPPLY: '본사 물류', HQ_PAID_PURCHASE: '본사 대납 외부구매', ROYALTY: '로열티', OTHER: '기타 대납' }

export function Statements() {
  const s = useSession()
  const name = useNames()
  const storeId = s.storeId !== 'ALL' ? s.storeId : ''
  const list = useApi<any[]>(`/api/stl/statements${storeId ? `?storeId=${storeId}` : ''}`, [s.storeId])
  const pending = useApi<any[]>(`/api/stl/statements/pending${storeId ? `?storeId=${storeId}` : ''}`, [s.storeId])
  const [sel, setSel] = useState<string | null>(null)
  const detail = useApi<any>(sel ? `/api/stl/statements/${sel}` : null, [sel])
  const [pay, setPay] = useState(false)
  const [msg, setMsg] = useState<string | null>(null)
  const [err, setErr] = useState<string | null>(null)
  const admin = s.has('HQ_ADMIN')
  const storeName = (id: string) => s.stores.find(x => x.id === id)?.name ?? ''
  const run = async (fn: () => Promise<any>, ok: string) => { setErr(null); try { await fn(); setMsg(ok); list.reload(); pending.reload(); detail.reload() } catch (e: any) { setErr(e.message) } }
  const groups = Object.values((pending.data ?? []).reduce((m: any, p: any) => { const k = p.store_id + p.period; (m[k] ??= { store_id: p.store_id, period: p.period, items: [] }).items.push(p); return m }, {})) as any[]
  const d = detail.data
  return (
    <Page title="본사 정산서" sub="로열티 + 본사 물류 + 본사 대납분을 매월 청구 · 매장이 입금 등록 → 본사 확인">
      <ErrorBox error={list.error || err} />
      {groups.length > 0 && <section className="card">
        <h2>발행 대기 청구</h2>
        <table className="tbl compact"><thead><tr><th>매장</th><th>기간</th><th>항목</th><th className="num">합계</th><th></th></tr></thead>
          <tbody>{groups.map(g => <tr key={g.store_id + g.period}><td>{storeName(g.store_id)}</td><td>{g.period}</td>
            <td>{g.items.map((i: any) => `${TYPE[i.charge_type]} ${i.cnt}건`).join(', ')}</td>
            <td className="num">{won(g.items.reduce((t: number, i: any) => t + Number(i.total), 0))}</td>
            <td>{admin && <button className="small primary" onClick={() => run(() => api('/api/stl/statements/issue', { body: { storeId: g.store_id, period: g.period } }), '정산서를 발행했습니다')}>정산서 발행</button>}</td></tr>)}</tbody></table>
        <p className="muted small">로열티는 결산 월 마감 때 청구 항목으로 생깁니다.</p>
      </section>}
      <div className="detail">
        <div className="detail-main">
          <div className="card table-wrap">
            <table className="tbl">
              <thead><tr><th>번호</th><th>매장</th><th>기간</th><th className="num">청구</th><th className="num">입금</th><th className="num">잔액</th><th>납기</th><th>상태</th></tr></thead>
              <tbody>{(list.data ?? []).map(r => (
                <tr key={r.id} className={`clickable ${sel === r.id ? 'selected' : ''}`} onClick={() => setSel(r.id)}>
                  <td><code>{r.statement_no}</code></td><td>{storeName(r.store_id)}</td><td>{r.period}</td><td className="num">{won(r.total_amount)}</td>
                  <td className="num">{won(r.paid_amount)}</td><td className="num"><b>{won(r.balance)}</b></td><td>{day(r.due_date)}</td>
                  <td><Badge tone={TONE[r.status]}>{ST[r.status]}</Badge>{r.unconfirmed > 0 && <> <Badge tone="warn">확인 대기 {r.unconfirmed}</Badge></>}</td>
                </tr>))}</tbody>
            </table>
            {list.data?.length === 0 && <Empty>정산서가 없습니다.</Empty>}
          </div>
        </div>
        <aside className="detail-side">
          {d ? <section className="card">
            <div className="side-title"><code>{d.statement_no}</code><Badge tone={TONE[d.status]}>{ST[d.status]}</Badge></div>
            <p className="small">{storeName(d.store_id)} · {d.period} · 발행 {dt(d.issued_at)} · 납기 {day(d.due_date)}</p>
            <table className="tbl compact"><tbody>{d.lines.map((l: any) => <tr key={l.id}><td><small>{TYPE[l.line_type]}</small><br />{l.description}</td><td className="num">{won(l.total_amount)}</td></tr>)}</tbody></table>
            <dl className="totals"><dt>공급가</dt><dd>{won(d.supply_amount)}</dd><dt>세액</dt><dd>{won(d.vat_amount)}</dd><dt className="strong">청구 합계</dt><dd className="strong">{won(d.total_amount)}</dd><dt>입금 확인</dt><dd>{won(d.paid_amount)}</dd></dl>
            <b>입금 내역</b>
            {d.payments.map((p: any) => <div key={p.id} className="comment">
              <span>{day(p.paid_date)} · {won(p.amount)}원 · {p.depositor ?? ''}</span>
              <small className="muted">등록 {name(p.reported_by)} {dt(p.reported_at)} · {p.confirmed_at ? `확인 ${name(p.confirmed_by)} ${dt(p.confirmed_at)}` : '확인 대기'}</small>
              {!p.confirmed_at && admin && <button className="small primary" onClick={() => run(() => api(`/api/stl/statements/${d.id}/payments/${p.id}/confirm`, { body: {} }), '입금을 확인했습니다')}>입금 확인</button>}
            </div>)}
            <div className="act-buttons">
              {!['PAID', 'CANCELLED'].includes(d.status) && s.has('OWNER', 'HQ_ADMIN') && <button className="primary" onClick={() => setPay(true)}>입금 등록</button>}
              {admin && Number(d.paid_amount) === 0 && d.status !== 'CANCELLED' && <button onClick={() => run(() => api(`/api/stl/statements/${d.id}/cancel`, { body: {} }), '정산서를 취소했습니다')}>정산서 취소</button>}
              {admin && !d.tax_invoice_id && d.status !== 'CANCELLED' && <button onClick={() => run(() => api(`/api/stl/tax/invoices/from-statement/${d.id}`, { body: {} }), '세금계산서 발행 대기에 넣었습니다')}>세금계산서 요청</button>}
            </div>
          </section> : <section className="card muted small">정산서를 고르면 청구 내역과 입금이 보입니다.</section>}
        </aside>
      </div>
      {pay && d && <PayModal balance={Number(d.total_amount) - Number(d.paid_amount)} onClose={() => setPay(false)}
        onSubmit={(b: any) => { setPay(false); run(() => api(`/api/stl/statements/${d.id}/payments`, { body: b }), admin ? '입금을 등록·확인했습니다' : '입금을 등록했습니다. 본사 확인 후 반영됩니다') }} />}
      <Toast msg={msg} onDone={() => setMsg(null)} />
    </Page>
  )
}

function PayModal({ balance, onClose, onSubmit }: any) {
  const [amount, setAmount] = useState(String(balance))
  const [date, setDate] = useState(new Date(Date.now() + 9 * 3600e3).toISOString().slice(0, 10))
  const [depositor, setDepositor] = useState('')
  return (
    <Modal title="입금 등록" onClose={onClose}>
      <form className="form" onSubmit={e => { e.preventDefault(); onSubmit({ amount: Number(amount), paidDate: date, depositor, method: 'TRANSFER' }) }}>
        <label htmlFor="pay-amt">입금액 (잔액 {won(balance)}원)</label><input id="pay-amt" inputMode="numeric" value={amount} onChange={e => setAmount(e.target.value.replace(/\D/g, ''))} />
        <label htmlFor="pay-date">입금일</label><input id="pay-date" type="date" value={date} onChange={e => setDate(e.target.value)} />
        <label htmlFor="pay-dep">입금자명</label><input id="pay-dep" value={depositor} onChange={e => setDepositor(e.target.value)} />
        <button className="primary">등록</button>
      </form>
    </Modal>
  )
}
