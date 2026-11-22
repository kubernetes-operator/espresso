import { useState } from 'react'
import { useApi, Page, ErrorBox } from '../components/ui'
import { LineChart, manwon } from '../components/LineChart'
import { useSession } from '../session'
import { won } from '../format'

const thisMonth = () => new Date(Date.now() + 9 * 3600e3).toISOString().slice(0, 7)

/** 매출 추이: 최근 12개월 순매출 vs 1년 전 같은 달 (겹친 꺾은선) */
export function Trends() {
  const s = useSession()
  const [to, setTo] = useState(thisMonth())
  const store = s.storeId !== 'ALL' && (s.currentStore?.site_type ?? 'STORE') === 'STORE' ? s.storeId : ''
  const d = useApi<any>(`/api/core/sales/trend?to=${to}${store ? `&storeId=${store}` : ''}`, [to, store])
  const months: any[] = d.data?.months ?? []
  const label = (m: string) => `${m.slice(2, 4)}.${m.slice(5)}`
  const name = store ? s.currentStore?.name : '볼 수 있는 매장 합산'
  return (
    <Page title="매출 추이" sub={`${name} · 순매출(공급가: 과세+면세−할인−환불) · 최근 12개월과 1년 전 같은 달`}
      actions={<input id="tr-to" type="month" aria-label="마지막 달" value={to} onChange={e => setTo(e.target.value)} />}>
      <ErrorBox error={d.error} />
      {s.storeId !== 'ALL' && !store && <div className="note warn">본사·베이커리는 매출이 없어 전체 매장 합산을 보여 줍니다.</div>}
      {d.data && <>
        <div className="grid3">
          <div className="card stat"><small>최근 12개월 순매출</small><b className="tnum">{won(d.data.total)}원</b></div>
          <div className="card stat"><small>1년 전 같은 기간</small><b className="tnum">{won(d.data.prev_total)}원</b></div>
          <div className="card stat"><small>전년 대비</small><b>{d.data.growth == null ? '—' : `${d.data.growth > 0 ? '+' : ''}${d.data.growth}%`}</b></div>
        </div>
        <section className="card">
          <h2>월별 순매출</h2>
          <LineChart title="월별 순매출, 올해와 1년 전" labels={months.map(m => label(m.month))} fmt={v => `${won(v)}원`} axisFmt={manwon}
            series={[
              { key: 'cur', name: '최근 12개월', color: 'var(--series-1)', values: months.map(m => m.net_sales) },
              { key: 'prev', name: '1년 전', color: 'var(--series-2)', dash: true, values: months.map(m => m.prev_year) },
            ]} />
          <p className="muted small">점선은 1년 전 같은 달입니다. 데이터가 없는 달은 선이 끊깁니다. 데모의 지난달 이전 매출은 '과거 실적'으로 넣은 값입니다(결산 미반영).</p>
        </section>
      </>}
    </Page>
  )
}
