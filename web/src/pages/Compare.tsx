import { useMemo, useState } from 'react'
import { useApi, Page, ErrorBox, Badge } from '../components/ui'
import { useSession } from '../session'
import { won } from '../format'

const lastMonth = () => { const d = new Date(Date.now() + 9 * 3600e3); d.setUTCDate(1); d.setUTCMonth(d.getUTCMonth() - 1); return d.toISOString().slice(0, 7) }

type Metric = { key: string; label: string; fmt: (v: any) => string; better: 'high' | 'low'; src: 'stl' | 'core'; ratio?: boolean }
const METRICS: Metric[] = [
  { key: 'net_sales', label: '순매출', fmt: won, better: 'high', src: 'stl' },
  { key: 'r_cogs', label: '원가율', fmt: v => v == null ? '—' : v + '%', better: 'low', src: 'stl', ratio: true },
  { key: 'r_labor', label: '인건비율', fmt: v => v == null ? '—' : v + '%', better: 'low', src: 'stl', ratio: true },
  { key: 'r_rent', label: '임대료율', fmt: v => v == null ? '—' : v + '%', better: 'low', src: 'stl', ratio: true },
  { key: 'r_loss', label: '손실률', fmt: v => v == null ? '—' : v + '%', better: 'low', src: 'stl', ratio: true },
  { key: 'operating_profit', label: '영업이익', fmt: won, better: 'high', src: 'stl' },
  { key: 'settlement_amount', label: '결산액', fmt: won, better: 'high', src: 'stl' },
  { key: 'r_margin', label: '결산 이익률', fmt: v => v == null ? '—' : v + '%', better: 'high', src: 'stl', ratio: true },
  { key: 'lr_count', label: '물류 요청 건수', fmt: v => v ?? '—', better: 'high', src: 'core' },
  { key: 'lr_amount', label: '물류 완료 금액', fmt: won, better: 'low', src: 'core' },
  { key: 'lead_hours', label: '평균 리드타임(h)', fmt: v => v ?? '—', better: 'low', src: 'core' },
  { key: 'loss_amount', label: '손실·결손 금액', fmt: won, better: 'low', src: 'core' },
  { key: 'stock_value', label: '현재 재고 금액', fmt: won, better: 'low', src: 'core' },
  { key: 'headcount', label: '급여 인원', fmt: v => v ?? '—', better: 'low', src: 'core' },
  { key: 'payroll_gross', label: '급여 총액', fmt: won, better: 'low', src: 'core' },
  { key: 'work_hours', label: '근무 시간', fmt: v => v ?? '—', better: 'low', src: 'core' },
]

export function Compare() {
  const s = useSession()
  const [month, setMonth] = useState(lastMonth())
  const period = month.replace('-', '')
  const pnl = s.has('HQ_ADMIN', 'OWNER')
  const stl = useApi<any[]>(pnl ? `/api/stl/compare?period=${period}` : null, [period])
  const core = useApi<any[]>(`/api/core/compare?period=${period}`, [period])
  const [region, setRegion] = useState('')
  const [sortKey, setSortKey] = useState('net_sales')
  // 점주: 전체 매장 기준값(이름 없이 최댓값·최솟값·평균)으로 상/하위 매장과 비교
  const ownerMode = s.has('OWNER') && !s.has('HQ_ADMIN', 'LOGISTICS_MGR', 'HR_MGR')
  const bStl = useApi<any>(ownerMode ? `/api/stl/compare/benchmark?period=${period}` : null, [period])
  const bCore = useApi<any>(ownerMode ? `/api/core/compare/benchmark?period=${period}` : null, [period])
  const [eachStore, setEachStore] = useState(false)

  const rows = useMemo(() => {
    const m = new Map<string, any>()
    for (const c of core.data ?? []) m.set(c.id, { id: c.id, name: c.name, region: c.region_name, ...c })
    for (const x of stl.data ?? []) {
      const r = m.get(x.store_id) ?? { id: x.store_id, name: x.store_name, region: x.region_name }
      Object.assign(r, x); for (const k of ['cogs', 'labor', 'rent', 'loss', 'margin']) r['r_' + k] = x.ratios?.[k]
      m.set(x.store_id, r)
    }
    return [...m.values()].filter(r => !region || r.region === region)
  }, [stl.data, core.data, region])
  const metrics = METRICS.filter(mt => rows.some(r => r[mt.key] !== undefined))
  const stats = Object.fromEntries(metrics.map(mt => {
    const vals = rows.map(r => Number(r[mt.key])).filter(v => !isNaN(v) && r0(v))
    const avg = vals.reduce((a, b) => a + b, 0) / (vals.length || 1)
    const sd = Math.sqrt(vals.reduce((a, b) => a + (b - avg) ** 2, 0) / (vals.length || 1))
    return [mt.key, { avg, sd }]
  }))
  function r0(v: number) { return v !== null && v !== undefined }
  const flag = (mt: Metric, v: any) => {
    const st = stats[mt.key]; if (v == null || !st || !st.sd || rows.length < 3) return ''
    const z = (Number(v) - st.avg) / st.sd
    if (Math.abs(z) < 1) return ''
    const good = mt.better === 'high' ? z > 0 : z < 0
    return good ? 'good' : 'bad'
  }
  const sorted = [...rows].sort((a, b) => Number(b[sortKey] ?? -Infinity) - Number(a[sortKey] ?? -Infinity))
  const regions = [...new Set((core.data ?? []).map(c => c.region_name))]
  const sortMetric = METRICS.find(m => m.key === sortKey)
  if (ownerMode) return <OwnerCompare month={month} setMonth={setMonth} rows={rows} bench={{ ...(bCore.data?.metrics ?? {}), ...(bStl.data?.metrics ?? {}) }}
    count={Math.max(bStl.data?.count ?? 0, bCore.data?.count ?? 0)} error={stl.error || core.error || bStl.error || bCore.error} eachStore={eachStore} setEachStore={setEachStore} />
  return (
    <Page title="매장 비교" sub={s.has('HQ_ADMIN') ? '전 매장 손익·물류·인건비 지표 · 평균에서 크게 벗어난 값 강조' : s.has('LOGISTICS_MGR') ? '물류 지표만 표시' : s.has('HR_MGR') ? '인건비 지표만 표시' : '내 매장끼리 비교'}
      actions={<>
        <input id="cmp-month" type="month" aria-label="월" value={month} onChange={e => setMonth(e.target.value)} />
        {regions.length > 1 && <select id="cmp-region" aria-label="권역" value={region} onChange={e => setRegion(e.target.value)}><option value="">전체 권역</option>{regions.map(r => <option key={r} value={r}>{r}</option>)}</select>}
      </>}>
      <ErrorBox error={stl.error || core.error} />
      <div className="card table-wrap">
        <table className="tbl compare">
          <thead><tr><th>지표</th>{sorted.map(r => <th key={r.id} className="num">{r.name}<br /><small className="muted">{r.region}</small></th>)}<th className="num">평균</th></tr></thead>
          <tbody>{metrics.map(mt => (
            <tr key={mt.key} className={sortKey === mt.key ? 'selected' : ''}>
              <td><button className="ghost small" onClick={() => setSortKey(mt.key)} title="이 지표로 정렬">{mt.label}</button></td>
              {sorted.map(r => <td key={r.id} className={`num ${flag(mt, r[mt.key])}`}>{mt.fmt(r[mt.key])}</td>)}
              <td className="num muted">{mt.ratio ? (stats[mt.key].avg ? stats[mt.key].avg.toFixed(1) + '%' : '—') : mt.fmt(Math.round(stats[mt.key].avg))}</td>
            </tr>))}</tbody>
        </table>
      </div>
      {sortMetric && <section className="card">
        <h2>{sortMetric.label} 순위</h2>
        <ol className="rank">{sorted.filter(r => r[sortKey] != null).map((r, i) => <li key={r.id}><span>{i + 1}. {r.name}</span><b className="tnum">{sortMetric.fmt(r[sortKey])}</b></li>)}</ol>
      </section>}
      <p className="muted small"><Badge tone="ok">초록</Badge> 평균보다 좋은 쪽으로 1표준편차 이상, <Badge tone="bad">빨강</Badge> 나쁜 쪽. 매장이 많을 때는 권역으로 좁혀 보세요.</p>
    </Page>
  )
}

/**
 * 점주 비교 규칙
 *  - 내 매장 3개 이하: 내 매장 각각 + 전체 매장 중 상위 1개 + 하위 1개
 *  - 4개 이상: 내 매장 평균 + 상위 1개 + 하위 1개 (매장별 보기 전환 가능)
 * 상위/하위는 지표마다 좋은 쪽(매출↑, 원가율↓ 등) 기준이며 다른 매장 이름은 보이지 않습니다.
 */
function OwnerCompare({ month, setMonth, rows, bench, count, error, eachStore, setEachStore }: any) {
  const many = rows.length > 3
  const showEach = !many || eachStore
  const metrics = METRICS.filter(mt => rows.some((r: any) => r[mt.key] !== undefined) || bench[mt.key])
  const best = (mt: Metric) => { const b = bench[mt.key]; return b == null ? null : mt.better === 'high' ? b.max : b.min }
  const worst = (mt: Metric) => { const b = bench[mt.key]; return b == null ? null : mt.better === 'high' ? b.min : b.max }
  const myAvg = (mt: Metric) => { const v = rows.map((r: any) => Number(r[mt.key])).filter((x: number) => !isNaN(x)); return v.length ? v.reduce((a: number, b: number) => a + b, 0) / v.length : null }
  const fmtv = (mt: Metric, v: number | null) => v == null ? '—' : mt.ratio ? `${Math.round(v * 10) / 10}%` : mt.fmt(Math.round(v))
  const tone = (mt: Metric, v: number | null) => {
    const b = bench[mt.key]; if (v == null || !b || b.max === b.min) return ''
    const pos = (v - b.min) / (b.max - b.min)   // 0 = 최솟값, 1 = 최댓값
    const good = mt.better === 'high' ? pos : 1 - pos
    return good >= 0.75 ? 'good' : good <= 0.25 ? 'bad' : ''
  }
  return (
    <Page title="매장 비교" sub={many ? `내 매장 ${rows.length}곳 평균을 전체 ${count}개 매장의 상위·하위 매장과 비교` : `내 매장을 전체 ${count}개 매장의 상위·하위 매장 1곳씩과 비교`}
      actions={<>
        <input id="cmp-month" type="month" aria-label="월" value={month} onChange={e => setMonth(e.target.value)} />
        {many && <label className="switch"><input type="checkbox" checked={eachStore} onChange={e => setEachStore(e.target.checked)} /><span>내 매장 각각 보기</span></label>}
      </>}>
      <ErrorBox error={error} />
      <div className="card table-wrap">
        <table className="tbl compare">
          <thead><tr><th>지표</th>
            {showEach ? rows.map((r: any) => <th key={r.id} className="num">{r.name}<br /><small className="muted">내 매장</small></th>) : null}
            {many && <th className="num">내 매장 평균<br /><small className="muted">{rows.length}곳</small></th>}
            <th className="num">상위 매장<br /><small className="muted">지표별 1위</small></th>
            <th className="num">하위 매장<br /><small className="muted">지표별 최하위</small></th>
            <th className="num">전체 평균</th></tr></thead>
          <tbody>{metrics.map(mt => (
            <tr key={mt.key}>
              <td>{mt.label}</td>
              {showEach ? rows.map((r: any) => <td key={r.id} className={`num ${tone(mt, r[mt.key] == null ? null : Number(r[mt.key]))}`}>{mt.fmt(r[mt.key])}</td>) : null}
              {many && <td className={`num ${tone(mt, myAvg(mt))}`}><b>{fmtv(mt, myAvg(mt))}</b></td>}
              <td className="num muted">{fmtv(mt, best(mt))}</td>
              <td className="num muted">{fmtv(mt, worst(mt))}</td>
              <td className="num muted">{fmtv(mt, bench[mt.key]?.avg ?? null)}</td>
            </tr>))}</tbody>
        </table>
      </div>
      <p className="muted small">상위·하위는 지표마다 좋은 쪽 기준입니다(매출·이익은 높을수록, 원가율·손실은 낮을수록 좋음). 다른 매장의 이름은 보이지 않습니다. <Badge tone="ok">초록</Badge> 전체 상위 25% 안, <Badge tone="bad">빨강</Badge> 하위 25% 안.</p>
    </Page>
  )
}
