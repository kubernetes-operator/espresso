import { useApi, Page, ErrorBox, Empty, Badge } from '../components/ui'
import { LineChart, manwon } from '../components/LineChart'
import { won } from '../format'

export function Investor() {
  const d = useApi<any[]>('/api/stl/investor/me')
  const t = useApi<any[]>('/api/stl/investor/me/trend')
  return (
    <Page title="투자 현황" sub="총괄 관리자가 공개한 범위만 표시됩니다">
      <ErrorBox error={d.error} />
      {d.data?.length === 0 && <Empty>공개된 투자 정보가 없습니다.</Empty>}
      {(d.data ?? []).map((x, i) => {
        return (
          <section key={i} className="card">
            <h2>{x.store_name} <Badge tone="accent">{x.mode === 'RATIO' ? `지분 ${Number(x.ratio) * 100}%` : x.mode === 'FIXED_AMOUNT' ? '지정액' : '총액'}</Badge></h2>
            <div className="grid3">
              <div className="stat"><small>투자 금액</small><b className="tnum">{won(x.amount)}원</b>
                {x.investor_total != null && <span className="muted small">투자자 출자 {won(x.investor_total)} + 본사 지원 {won(x.hq_total)}</span>}</div>
              {x.cumulative_settlement != null && <div className="stat"><small>누적 결산 (내 몫)</small><b className="tnum">{won(x.cumulative_settlement)}원</b></div>}
              {x.recovery_rate != null && <div className="stat"><small>회수율</small><b>{x.recovery_rate}%</b><div className="bar"><span style={{ width: `${Math.min(100, x.recovery_rate)}%` }} /></div></div>}
            </div>
            <TrendCharts trend={(t.data ?? []).find((y: any) => y.store_id === x.store_id)} />
          </section>
        )
      })}
      <ErrorBox error={t.error} />
    </Page>
  )
}

/** 투자 매장 최근 12개월: 월별 매출액 / 영업이익(+ 공개 시 결산 내 몫) — 단위가 같아도 크기가 달라 두 차트로 나눔 */
function TrendCharts({ trend }: { trend: any }) {
  if (!trend) return null
  const ms: any[] = trend.months
  const labels = ms.map(m => `${m.period.slice(2, 4)}.${m.period.slice(4)}`)
  return (
    <div className="grid2" style={{ marginTop: 12 }}>
      <div>
        <h3>월별 매출액 (최근 1년)</h3>
        <LineChart title={`${trend.store_name} 월별 순매출`} labels={labels} fmt={v => `${won(v)}원`} axisFmt={manwon}
          series={[{ key: 's', name: '순매출', color: 'var(--series-1)', values: ms.map(m => m.net_sales) }]} />
      </div>
      <div>
        <h3>월별 이익 (최근 1년)</h3>
        <LineChart title={`${trend.store_name} 월별 이익`} labels={labels} fmt={v => `${won(v)}원`} axisFmt={manwon}
          series={[{ key: 'op', name: '영업이익', color: 'var(--series-1)', values: ms.map(m => m.operating_profit) },
            ...(trend.show_settlement ? [{ key: 'sh', name: '결산액(내 몫)', color: 'var(--series-2)', dash: true, values: ms.map((m: any) => m.settlement_share) }] : [])]} />
      </div>
      <p className="muted small" style={{ gridColumn: '1 / -1' }}>매출액은 일 매출 기준(도입 전 과거 실적 포함)입니다. 이익은 결산이 반영된 달부터 보이며, 마감 전 달은 실시간 계산값입니다.</p>
    </div>
  )
}
