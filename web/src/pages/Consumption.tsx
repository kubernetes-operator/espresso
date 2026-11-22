import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useApi, Page, ErrorBox, Badge } from '../components/ui'
import { LineChart } from '../components/LineChart'
import { useSession } from '../session'
import { won } from '../format'

const today = () => new Date(Date.now() + 9 * 3600e3).toISOString().slice(0, 10)
type GK = 'bean' | 'milk' | 'syrup' | 'bakery'
const G: { key: GK; name: string; unit: string; sales: string }[] = [
  { key: 'bean', name: '원두', unit: 'kg', sales: '음료 매출' },
  { key: 'milk', name: '우유', unit: 'L', sales: '음료 매출' },
  { key: 'syrup', name: '시럽', unit: 'L', sales: '음료 매출' },
  { key: 'bakery', name: '베이커리', unit: 'kg', sales: '디저트 매출' },
]
const BASIS = { receipt: { name: '물류 입고 기준', qty: '입고량', cost: '물류 금액', ratio: '물류 비율' }, usage: { name: '재고 소진 기준', qty: '소진량', cost: '소진 원가', ratio: '원가 비율' } }

// 표현: 추정 지표라 대략값으로 ("약 23.7만원", "약 9%")
const approxWon = (v: number | null | undefined) => v == null ? '—' : v >= 10000 ? `약 ${(v / 10000).toLocaleString('ko-KR', { maximumFractionDigits: 1 })}만원` : `약 ${won(Math.round(v / 100) * 100)}원`
const approxPct = (v: number | null | undefined) => v == null ? '—' : `약 ${v < 10 ? v.toFixed(1).replace(/\.0$/, '') : Math.round(v)}%`
const qtyFmt = (v: number | null | undefined, unit: string) => v == null ? '—' : `${v.toLocaleString('ko-KR', { maximumFractionDigits: 1 })}${unit}`
const avg = (xs: (number | null | undefined)[]) => { const v = xs.filter((x): x is number => x != null && x > 0); return v.length ? v.reduce((a, b) => a + b, 0) / v.length : null }
/** 평소(또는 평균) 대비: ±3% 안이면 "비슷", 아니면 "n% 높음/낮음" */
function relText(v: number | null | undefined, base: number | null, what = '평소') {
  if (v == null || base == null) return <span className="muted">비교할 값 없음</span>
  const d = Math.round((v / base - 1) * 100)
  if (Math.abs(d) < 3) return <span>{what}와 비슷</span>
  return <span>{what}보다 <span style={{ fontWeight: 600 }}>{Math.abs(d)}% {d > 0 ? '높음' : '낮음'}</span></span>
}

/** 재고 소비 분석 (추정 지표): 판매 금액 ÷ 물류(입고)량 · 물류 비율 · 손실 비중. 절대값이 아니라 평소 수준·매장 간 비교로 해석 */
export function Consumption() {
  const s = useSession()
  const [unit, setUnit] = useState<'month' | 'week'>('month')
  const [basis, setBasis] = useState<'receipt' | 'usage'>('receipt')
  const [tg, setTg] = useState<GK>('bean')
  const [to, setTo] = useState(today())
  const store = s.storeId !== 'ALL' && (s.currentStore?.site_type ?? 'STORE') === 'STORE' ? s.storeId : ''
  const d = useApi<any>(`/api/core/reports/consumption?unit=${unit}&basis=${basis}&to=${to}&count=12${store ? `&storeId=${store}` : ''}`, [unit, basis, to, store])
  const x = d.data
  const B = BASIS[basis]
  const rows: any[] = x ? (store ? x.stores[0]?.rows ?? [] : x.total) : []
  const done = rows.filter(r => !r.in_progress)
  const label = (b: string) => unit === 'month' ? `${b.slice(2, 4)}.${b.slice(5, 7)}` : `${b.slice(5, 7)}/${b.slice(8)}`
  const last = [...done].reverse().find(r => G.some(g => r[g.key].qty > 0))
  const base: Record<GK, number | null> = Object.fromEntries(G.map(g => [g.key, avg(done.map(r => r[g.key].per_unit))])) as any
  const name = store ? s.currentStore?.name : '볼 수 있는 매장 합산'
  const period = unit === 'month' ? '달' : '주'
  const gsel = G.find(g => g.key === tg)!
  return (
    <Page title="재고 소비" sub={`${name} · 판매 금액과 ${basis === 'receipt' ? '물류(입고)' : '재고 소진'}량으로 본 추정 지표 · 금액은 공급가(VAT 제외)`}
      actions={<>
        <div className="seg" role="group" aria-label="기준"><button className={basis === 'receipt' ? 'on' : ''} onClick={() => setBasis('receipt')}>물류 입고 기준</button><button className={basis === 'usage' ? 'on' : ''} onClick={() => setBasis('usage')}>재고 소진 기준</button></div>
        <div className="seg" role="group" aria-label="기간 단위"><button className={unit === 'month' ? 'on' : ''} onClick={() => setUnit('month')}>월별</button><button className={unit === 'week' ? 'on' : ''} onClick={() => setUnit('week')}>주별</button></div>
        <input id="cs-to" type="date" aria-label="마지막 기간 기준일" value={to} onChange={e => setTo(e.target.value)} />
      </>}>
      <ErrorBox error={d.error} />
      <div className="note info">이 화면의 값은 <b>판단을 돕는 추정치</b>입니다. 입고한 재고는 다음 {period}에 쓰일 수 있고 기록 시점·누락에 따라 흔들리므로, 한 {period}의 숫자보다 <b>평소 수준과의 차이</b>와 <b>다른 매장과의 차이</b>를 보세요. 음료 매출은 원두·우유·시럽, 디저트 매출은 베이커리 총중량과 비교합니다. 1단위당 매출이 평소보다 <b>낮으면</b> 같은 매출에 재료가 더 들어간 것(과사용·폐기·기록 누락 또는 미리 입고)으로, <b>높으면</b> 재료가 덜 들어온 것(재고 소진·입고 지연)으로 볼 수 있습니다.</div>
      {s.storeId !== 'ALL' && !store && <div className="note warn">본사·베이커리는 매출이 없어 전체 매장 합산을 보여 줍니다.</div>}
      {x && <>
        {last && <div className="grid4">
          {G.map(g => { const v = last[g.key]; return <div key={g.key} className="card stat">
            <small>{g.name} 1{g.unit}당 {g.sales} · {label(last.bucket)}</small>
            <b className="tnum">{approxWon(v.per_unit)}</b>
            <span className="small">{relText(v.per_unit, base[g.key])}</span>
            <span className="muted small">{B.ratio} {approxPct(v.cost_ratio)} · 손실 {approxPct(v.loss_ratio)}</span>
          </div> })}
        </div>}
        {x.missing_weight.length > 0 && <div className="note warn">
          단위당 중량·용량이 없는 품목 {x.missing_weight.length}개는 양 계산에서 빠졌습니다(금액 비율에는 포함): {x.missing_weight.slice(0, 5).map((m: any) => `${m.name}(${m.unit})`).join(', ')}{x.missing_weight.length > 5 ? ' 외' : ''}.
          {s.has('HQ_ADMIN', 'LOGISTICS_MGR') ? <> <Link to="/admin/catalog">품목·단가</Link>에서 [수정] › 단위당 중량·용량을 넣으세요.</> : ' 물류 관리자에게 입력을 요청하세요.'}
        </div>}
        <h2 className="section-title">평소 대비 흐름 <small className="muted">(최근 {done.length}{period} 평균 = 100, 진행 중인 {period} 제외)</small></h2>
        <div className="grid2">
          {G.map(g => <section key={g.key} className="card">
            <h3>{g.name} · {g.sales} ÷ {g.name} {B.qty}</h3>
            <LineChart title={`${g.name} 1${g.unit}당 ${g.sales}, 평균 대비 지수`} height={180} labels={done.map(r => label(r.bucket))} fmt={v => `${v} (평균 100)`} axisFmt={v => `${v}`}
              series={[{ key: g.key, name: g.name, color: 'var(--series-1)', values: done.map(r => r[g.key].per_unit == null || base[g.key] == null ? null : Math.round(r[g.key].per_unit / base[g.key]! * 100)) }]} />
          </section>)}
        </div>
        <section className="card">
          <h2>손실 비중 <small className="muted">{B.qty} 중 손실·결손 (%)</small></h2>
          <LineChart title="원두·베이커리 손실 비중 (%)" labels={done.map(r => label(r.bucket))} fmt={v => `약 ${v}%`} axisFmt={v => `${v}%`} height={200}
            series={[{ key: 'bean', name: '원두', color: 'var(--series-1)', values: done.map(r => r.bean.loss_ratio) },
              { key: 'bakery', name: '베이커리', color: 'var(--series-2)', values: done.map(r => r.bakery.loss_ratio) }]} />
        </section>
        <div className="seg" role="tablist" aria-label="상세 그룹" style={{ marginTop: 8 }}>
          {G.map(g => <button key={g.key} className={tg === g.key ? 'on' : ''} onClick={() => setTg(g.key)}>{g.name}</button>)}
        </div>
        <section className="card table-wrap">
          <h2>{gsel.name} · {gsel.sales} <small className="muted">{B.name}</small></h2>
          <table className="tbl compact">
            <thead><tr><th>{unit === 'month' ? '월' : '주(월요일)'}</th><th className="num">입고량</th><th className="num">소진량</th><th className="num">손실·결손</th>
              <th className="num">{B.cost}</th><th className="num">{gsel.sales}</th><th className="num">1{gsel.unit}당 매출(추정)</th><th>평소 대비</th><th className="num">{B.ratio}</th><th className="num">손실 비중</th></tr></thead>
            <tbody>{[...rows].reverse().map(r => { const v = r[gsel.key]; return <tr key={r.bucket}>
              <td>{unit === 'month' ? r.bucket.slice(0, 7) : r.bucket}{r.in_progress && <> <Badge>진행 중</Badge></>}</td>
              <td className="num">{qtyFmt(v.receipt_qty, gsel.unit)}</td><td className="num">{qtyFmt(v.used_qty, gsel.unit)}</td><td className="num">{qtyFmt(v.loss_qty, gsel.unit)}</td>
              <td className="num">{won(v.cost)}</td><td className="num">{won(v.sales)}</td>
              <td className="num"><b>{approxWon(v.per_unit)}</b></td><td className="small">{r.in_progress ? <span className="muted">집계 중</span> : relText(v.per_unit, base[gsel.key])}</td>
              <td className="num">{approxPct(v.cost_ratio)}</td>
              <td className="num">{v.loss_ratio != null && v.loss_ratio >= 10 ? <Badge tone="warn">{approxPct(v.loss_ratio)}</Badge> : approxPct(v.loss_ratio)}</td></tr> })}</tbody>
          </table>
        </section>
        {!store && x.stores.length > 1 && last && (() => {
          const rs = x.stores.map((st: any) => ({ st, v: st.rows.find((q: any) => q.bucket === last.bucket)[gsel.key] }))
          const mean = avg(rs.map((q: any) => q.v.per_unit))
          return <section className="card table-wrap">
            <h2>매장 비교 · {gsel.name} ({label(last.bucket)}) <small className="muted">매장 평균과의 차이</small></h2>
            <table className="tbl compact">
              <thead><tr><th>매장</th><th className="num">{B.qty}</th><th className="num">1{gsel.unit}당 {gsel.sales}(추정)</th><th>매장 평균 대비</th><th className="num">{B.ratio}</th><th className="num">손실 비중</th></tr></thead>
              <tbody>{rs.map(({ st, v }: any) => <tr key={st.id}>
                <td>{st.name}</td><td className="num">{qtyFmt(v.qty, gsel.unit)}</td><td className="num">{approxWon(v.per_unit)}</td>
                <td className="small">{v.per_unit == null ? <span className="muted">자료 없음</span> : relText(v.per_unit, mean, '평균')}</td>
                <td className="num">{approxPct(v.cost_ratio)}</td><td className="num">{approxPct(v.loss_ratio)}</td></tr>)}</tbody>
            </table>
          </section>
        })()}
        <p className="muted small">
          <b>{B.name}</b>: {basis === 'receipt'
            ? '그 기간에 물류·구매로 들어온 양과 금액을 씁니다. 실사가 없어도 계산되지만, 몰아서 입고하면 그 달이 높게·다음 달이 낮게 보입니다.'
            : '실사의 판매 소진·차이, 손실·결손, 내부사용으로 빠진 양과 원가를 씁니다. 실제 사용에 가깝지만 실사를 해야 잡힙니다.'}
          {' '}그룹: 원두 = 커피(원두), 우유 = 유제품, 시럽 = 시럽·소스·파우더(L·kg를 1:1로 합산), 베이커리 = 디저트·베이킹 재료. 품목의 단위당 중량·용량으로 kg·L로 바꿉니다.
          판매 제품별 수량(POS)이 연동되면 레시피 기준 수율로 더 정확하게 볼 수 있습니다. 데모의 과거 이력은 가상값입니다.
        </p>
      </>}
    </Page>
  )
}
