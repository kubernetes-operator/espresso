import { Fragment, useState } from 'react'
import { Link } from 'react-router-dom'
import { useApi, Page, ErrorBox, Badge } from '../components/ui'
import { LineChart, manwon } from '../components/LineChart'
import { useSession } from '../session'
import { won } from '../format'

const today = () => new Date(Date.now() + 9 * 3600e3).toISOString().slice(0, 10)
const G = [
  { key: 'bean', name: '원두', sales: '음료 매출', perKg: '원두 1kg당 음료 매출' },
  { key: 'bakery', name: '베이커리', sales: '디저트 매출', perKg: '베이커리 1kg당 디저트 매출' },
] as const
const kg = (v: number | null | undefined) => v == null ? '—' : `${v.toLocaleString('ko-KR', { maximumFractionDigits: 1 })}kg`
const pct = (v: number | null | undefined) => v == null ? '—' : `${v}%`

/** 재고 소비 분석: 소비된 원두·베이커리 중량(kg) 대비 구분 매출 · 원가율 · 손실 비중 */
export function Consumption() {
  const s = useSession()
  const [unit, setUnit] = useState<'month' | 'week'>('month')
  const [to, setTo] = useState(today())
  const store = s.storeId !== 'ALL' && (s.currentStore?.site_type ?? 'STORE') === 'STORE' ? s.storeId : ''
  const d = useApi<any>(`/api/core/reports/consumption?unit=${unit}&to=${to}&count=12${store ? `&storeId=${store}` : ''}`, [unit, to, store])
  const x = d.data
  const rows: any[] = x ? (store ? x.stores[0]?.rows ?? [] : x.total) : []
  const label = (b: string) => unit === 'month' ? `${b.slice(2, 4)}.${b.slice(5, 7)}` : `${b.slice(5, 7)}/${b.slice(8)}`
  // 진행 중인 기간(이번 달·이번 주)은 다 안 찬 값이라 요약 카드에서는 직전 완료 기간을 씀
  const cur = (() => { const t = today(); if (unit === 'month') return t.slice(0, 7) + '-01'; const dd = new Date(t + 'T00:00:00Z'); dd.setUTCDate(dd.getUTCDate() - ((dd.getUTCDay() + 6) % 7)); return dd.toISOString().slice(0, 10) })()
  const done = (r: any) => r.bucket < cur
  const last = [...rows].reverse().find(r => done(r) && (r.bean.used_kg > 0 || r.bakery.used_kg > 0))
  const name = store ? s.currentStore?.name : '볼 수 있는 매장 합산'
  return (
    <Page title="재고 소비" sub={`${name} · 소비된 재고를 중량(kg)으로 바꿔 매출과 비교 · 금액은 공급가(VAT 제외)`}
      actions={<>
        <div className="seg"><button className={unit === 'month' ? 'on' : ''} onClick={() => setUnit('month')}>월별</button><button className={unit === 'week' ? 'on' : ''} onClick={() => setUnit('week')}>주별</button></div>
        <input id="cs-to" type="date" aria-label="마지막 기간 기준일" value={to} onChange={e => setTo(e.target.value)} />
      </>}>
      <ErrorBox error={d.error} />
      {s.storeId !== 'ALL' && !store && <div className="note warn">본사·베이커리는 매출이 없어 전체 매장 합산을 보여 줍니다.</div>}
      {x && <>
        {last && <div className="grid3">
          {G.map(g => <div key={g.key} className="card stat"><small>{g.perKg} ({label(last.bucket)})</small>
            <b className="tnum">{last[g.key].sales_per_kg == null ? '—' : `${won(last[g.key].sales_per_kg)}원`}</b>
            <span className="muted small">소비 {kg(last[g.key].used_kg)} · 원가율 {pct(last[g.key].cost_ratio)}</span></div>)}
          <div className="card stat"><small>손실 비중 ({label(last.bucket)})</small>
            <b className="tnum">원두 {pct(last.bean.loss_ratio)} · 베이커리 {pct(last.bakery.loss_ratio)}</b>
            <span className="muted small">소비 중 손실·결손 중량</span></div>
        </div>}
        {x.missing_weight.length > 0 && <div className="note warn">
          단위당 중량이 없는 품목 {x.missing_weight.length}개는 kg에서 빠졌습니다(원가율에는 포함): {x.missing_weight.slice(0, 5).map((m: any) => `${m.name}(${m.unit})`).join(', ')}{x.missing_weight.length > 5 ? ' 외' : ''}.
          {s.has('HQ_ADMIN', 'LOGISTICS_MGR') ? <> <Link to="/admin/catalog">품목·단가</Link>에서 [수정] › 단위당 중량(g)을 넣으세요.</> : ' 물류 관리자에게 중량 입력을 요청하세요.'}
        </div>}
        <div className="grid2">
          {G.map(g => <section key={g.key} className="card">
            <h2>{g.perKg}</h2>
            <LineChart title={`${unit === 'month' ? '월별' : '주별'} ${g.perKg}`} labels={rows.map(r => label(r.bucket))} fmt={v => `${won(v)}원`} axisFmt={manwon}
              series={[{ key: g.key, name: g.perKg, color: 'var(--series-1)', values: rows.map(r => r[g.key].sales_per_kg) }]} />
          </section>)}
        </div>
        <section className="card">
          <h2>손실 비중</h2>
          <LineChart title="소비 중 손실·결손 비중 (%)" labels={rows.map(r => label(r.bucket))} fmt={v => `${v}%`} axisFmt={v => `${v}%`} height={200}
            series={[{ key: 'bean', name: '원두', color: 'var(--series-1)', values: rows.map(r => r.bean.loss_ratio) },
              { key: 'bakery', name: '베이커리', color: 'var(--series-2)', values: rows.map(r => r.bakery.loss_ratio) }]} />
        </section>
        {G.map(g => <section key={g.key} className="card table-wrap">
          <h2>{g.name} 소비 · {g.sales}</h2>
          <table className="tbl compact">
            <thead><tr><th>{unit === 'month' ? '월' : '주(월요일)'}</th><th className="num">소비 kg</th><th className="num">판매 소진</th><th className="num">손실·결손</th><th className="num">내부사용</th>
              <th className="num">소비 원가</th><th className="num">kg당 원가</th><th className="num">{g.sales}</th><th className="num">kg당 매출</th><th className="num">원가율</th><th className="num">손실 비중</th></tr></thead>
            <tbody>{[...rows].reverse().map(r => { const v = r[g.key]; return <tr key={r.bucket}>
              <td>{unit === 'month' ? r.bucket.slice(0, 7) : r.bucket}{!done(r) && <> <Badge>진행 중</Badge></>}</td><td className="num"><b>{kg(v.used_kg)}</b></td><td className="num">{kg(v.sale_kg)}</td>
              <td className="num">{kg(v.loss_kg)}</td><td className="num">{kg(v.internal_kg)}</td><td className="num">{won(v.cost)}</td>
              <td className="num">{v.cost_per_kg == null ? '—' : won(v.cost_per_kg)}</td><td className="num">{won(v.sales)}</td>
              <td className="num"><b>{v.sales_per_kg == null ? '—' : won(v.sales_per_kg)}</b></td><td className="num">{pct(v.cost_ratio)}</td>
              <td className="num">{v.loss_ratio != null && v.loss_ratio >= 10 ? <Badge tone="warn">{pct(v.loss_ratio)}</Badge> : pct(v.loss_ratio)}</td></tr> })}</tbody>
          </table>
        </section>)}
        {!store && x.stores.length > 1 && last && <section className="card table-wrap">
          <h2>매장 비교 ({label(last.bucket)})</h2>
          <table className="tbl compact">
            <thead><tr><th>매장</th><th className="num">원두 kg</th><th className="num">원두 1kg당 음료 매출</th><th className="num">원두 원가율</th><th className="num">원두 손실</th>
              <th className="num">베이커리 kg</th><th className="num">베이커리 1kg당 디저트 매출</th><th className="num">베이커리 원가율</th><th className="num">베이커리 손실</th></tr></thead>
            <tbody>{x.stores.map((st: any) => { const r = st.rows.find((q: any) => q.bucket === last.bucket); return <tr key={st.id}>
              <td>{st.name}</td>
              {G.map(g => <Fragment key={g.key}><td className="num">{kg(r[g.key].used_kg)}</td><td className="num">{r[g.key].sales_per_kg == null ? '—' : won(r[g.key].sales_per_kg)}</td>
                <td className="num">{pct(r[g.key].cost_ratio)}</td><td className="num">{pct(r[g.key].loss_ratio)}</td></Fragment>)}
            </tr> })}</tbody>
          </table>
        </section>}
        <p className="muted small">요약 카드는 마지막으로 끝난 {unit === 'month' ? '달' : '주'} 기준입니다. 진행 중인 기간은 실사 전이라 소비가 덜 잡혀 낮거나 높게 보일 수 있습니다. 소비 = 재고 원장에서 빠진 양(실사의 판매 소진·차이, 손실·결손, 내부사용)이며 품목의 <b>단위당 중량</b>으로 kg으로 바꿉니다. 원두 = 커피(원두) 분류, 베이커리 = 디저트·베이킹 재료 분류입니다. kg당 매출이 낮아지거나 손실 비중이 높아지면 과추출·폐기·누락을 확인하세요. 다음 단계에서는 판매 제품별 레시피로 이론 소비량을 계산해 수율(실제 ÷ 이론)로 볼 예정입니다. 데모의 과거 소비 이력은 가상값입니다.</p>
      </>}
    </Page>
  )
}
