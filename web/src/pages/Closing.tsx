import { useState } from 'react'
import { api } from '../api'
import { useApi, Page, Badge, ErrorBox, Toast, Empty } from '../components/ui'
import { useSession } from '../session'
import { won, dt } from '../format'

const today = () => new Date(Date.now() + 9 * 3600e3).toISOString().slice(0, 10)
const FIELDS: [string, string][] = [['beverage', '음료'], ['dessert', '디저트'], ['tableware', '식기·포장'], ['etc', '기타'], ['discount', '할인'], ['refund', '환불']]
const PAYS: [string, string][] = [['card', '카드'], ['cash', '현금'], ['otherPay', '기타 결제']]

/** 근무 마감: 모든 직원이 자기 근무의 판매·결제 금액(VAT 포함)을 입력 → 그날 소계 → 매장 관리자가 일 매출로 반영 */
export function Closing() {
  const s = useSession()
  const storeId = s.storeId !== 'ALL' && (s.currentStore?.site_type ?? 'STORE') !== 'HQ' ? s.storeId : ''
  const [date, setDate] = useState(today())
  const d = useApi<any>(storeId ? `/api/core/sales/shift-closes?storeId=${storeId}&date=${date}` : null, [storeId, date])
  const empty = Object.fromEntries([...FIELDS, ...PAYS, ['customers', ''], ['memo', '']].map(([k]) => [k, '']))
  const [f, setF] = useState<Record<string, string>>(empty)
  const [msg, setMsg] = useState<string | null>(null)
  const [err, setErr] = useState<string | null>(null)
  const n = (k: string) => Number(f[k] || 0)
  const net = n('beverage') + n('dessert') + n('tableware') + n('etc') - n('discount') - n('refund')
  const pay = n('card') + n('cash') + n('otherPay')
  if (!storeId) return <Page title="근무 마감"><div className="note warn">근무 마감은 매장별입니다. 상단에서 매장(또는 베이커리)을 고르세요.</div></Page>
  const save = async () => {
    setErr(null)
    if (net <= 0) return setErr('판매 금액(음료·디저트·식기·포장·기타 중 하나 이상)을 입력하세요.')
    if (net !== pay) return setErr(`결제 합계 ${won(pay)}원이 판매 합계 ${won(net)}원과 다릅니다 (차이 ${won(pay - net)}원).`)
    try {
      await api('/api/core/sales/shift-closes', { body: { storeId, date, ...Object.fromEntries([...FIELDS, ...PAYS].map(([k]) => [k, n(k)])), customers: f.customers ? n('customers') : null, memo: f.memo || null } })
      setMsg('근무 마감을 저장했습니다'); setF(empty); d.reload()
    } catch (e: any) { setErr(e.message) }
  }
  const apply = async (close: boolean) => {
    setErr(null)
    try { await api('/api/core/sales/shift-closes/apply', { body: { storeId, date, close } }); setMsg(close ? '일 매출로 반영하고 마감했습니다' : '일 매출로 반영했습니다'); d.reload() } catch (e: any) { setErr(e.message) }
  }
  const t = d.data?.subtotal
  const field = (k: string, l: string) => <div key={k}><label htmlFor={`sc-${k}`}>{l}</label><input id={`sc-${k}`} inputMode="numeric" value={f[k]} onChange={e => setF({ ...f, [k]: e.target.value.replace(/\D/g, '') })} /></div>
  return (
    <Page title="근무 마감" sub={`${s.currentStore?.name} · 금액은 POS와 같은 결제 금액(VAT 포함)`}
      actions={<input id="sc-date" type="date" aria-label="날짜" value={date} max={today()} onChange={e => setDate(e.target.value)} />}>
      <ErrorBox error={d.error || err} />
      <div className="grid2">
        <section className="card">
          <h2>내 근무 마감</h2>
          <form className="form" onSubmit={e => { e.preventDefault(); save() }}>
            <b className="small">판매</b>
            <div className="row">{FIELDS.slice(0, 4).map(([k, l]) => field(k, l))}</div>
            <div className="row">{FIELDS.slice(4).map(([k, l]) => field(k, l))}{field('customers', '고객 수')}</div>
            <b className="small">결제</b>
            <div className="row">{PAYS.map(([k, l]) => field(k, l))}</div>
            <label htmlFor="sc-memo">메모</label><input id="sc-memo" value={f.memo} placeholder="예: 오후 근무, 시재 이상 없음" onChange={e => setF({ ...f, memo: e.target.value })} />
            <p className="small">판매 합계 <b>{won(net)}</b>원 · 결제 합계 <b className={net !== pay ? 'overdue' : ''}>{won(pay)}</b>원 {net !== pay && pay > 0 && `(차이 ${won(pay - net)}원)`}</p>
            <button className="primary" disabled={d.data?.daily?.status === 'CLOSED'}>마감 저장</button>
            {d.data?.daily?.status === 'CLOSED' && <p className="muted small">이 날 일 매출은 이미 마감되었습니다.</p>}
          </form>
        </section>
        <section className="card">
          <h2>{date} 소계</h2>
          {t && <dl className="totals">
            <dt>음료 / 디저트</dt><dd>{won(t.beverage)} / {won(t.dessert)}</dd>
            <dt>식기·포장 / 기타</dt><dd>{won(t.tableware)} / {won(t.etc)}</dd>
            <dt>할인·환불</dt><dd>−{won(t.discount + t.refund)}</dd>
            <dt>카드 / 현금 / 기타</dt><dd>{won(t.card)} / {won(t.cash)} / {won(t.other_pay)}</dd>
            <dt>고객 수</dt><dd>{won(t.customers)}명</dd>
            <dt className="strong">판매 소계</dt><dd className="strong">{won(t.net)}원</dd>
          </dl>}
          {d.data?.daily && <p className="small">일 매출: <Badge tone={d.data.daily.status === 'CLOSED' ? 'ok' : 'warn'}>{d.data.daily.status === 'CLOSED' ? '마감' : '입력 중'}</Badge> {won(d.data.daily.total_amount)}원</p>}
          {d.data?.canApply && t?.net > 0 && d.data?.daily?.status !== 'CLOSED' && <div className="act-buttons">
            <button onClick={() => apply(false)}>일 매출로 반영</button><button className="primary" onClick={() => apply(true)}>반영 후 일 마감</button></div>}
        </section>
      </div>
      <div className="card table-wrap">
        <table className="tbl">
          <thead><tr><th>마감 시각</th><th>마감자</th><th className="num">판매</th><th className="num">카드</th><th className="num">현금</th><th className="num">기타</th><th className="num">고객</th><th>메모</th><th>반영</th><th></th></tr></thead>
          <tbody>{(d.data?.items ?? []).map((x: any) => {
            const sale = x.beverage + x.dessert + x.tableware + x.etc - x.discount - x.refund
            return <tr key={x.id}><td>{dt(x.created_at)}</td><td>{x.closer_name}</td><td className="num">{won(sale)}</td><td className="num">{won(x.card)}</td><td className="num">{won(x.cash)}</td>
              <td className="num">{won(x.other_pay)}</td><td className="num">{x.customers ?? '—'}</td><td><small>{x.memo}</small></td>
              <td>{x.applied_at ? <Badge tone="ok">반영</Badge> : '—'}</td>
              <td>{!x.applied_at && (x.user_id === s.user?.id || d.data.canApply) && <button className="ghost small" onClick={async () => { try { await api(`/api/core/sales/shift-closes/${x.id}`, { method: 'DELETE' }); d.reload() } catch (e: any) { setErr(e.message) } }}>삭제</button>}</td></tr>
          })}</tbody>
        </table>
        {d.data?.items?.length === 0 && <Empty>이 날 근무 마감이 없습니다.</Empty>}
      </div>
      <Toast msg={msg} onDone={() => setMsg(null)} />
    </Page>
  )
}
