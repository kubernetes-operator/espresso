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
