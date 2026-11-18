import { useApi, Page, ErrorBox, Empty, Badge } from '../components/ui'
import { won } from '../format'

export function Investor() {
  const d = useApi<any[]>('/api/stl/investor/me')
  return (
    <Page title="투자 현황" sub="총괄 관리자가 공개한 범위만 표시됩니다">
      <ErrorBox error={d.error} />
      {d.data?.length === 0 && <Empty>공개된 투자 정보가 없습니다.</Empty>}
      {(d.data ?? []).map((x, i) => {
        const max = Math.max(1, ...(x.monthly ?? []).map((m: any) => Math.abs(m.amount)))
        return (
          <section key={i} className="card">
            <h2>{x.store_name} <Badge tone="accent">{x.mode === 'RATIO' ? `지분 ${Number(x.ratio) * 100}%` : x.mode === 'FIXED_AMOUNT' ? '지정액' : '총액'}</Badge></h2>
            <div className="grid3">
              <div className="stat"><small>투자 금액</small><b className="tnum">{won(x.amount)}원</b></div>
              {x.cumulative_settlement != null && <div className="stat"><small>누적 결산 (내 몫)</small><b className="tnum">{won(x.cumulative_settlement)}원</b></div>}
              {x.recovery_rate != null && <div className="stat"><small>회수율</small><b>{x.recovery_rate}%</b><div className="bar"><span style={{ width: `${Math.min(100, x.recovery_rate)}%` }} /></div></div>}
            </div>
            {x.monthly?.length > 0 && <>
              <h3 style={{ marginTop: 12 }}>월별 결산 (내 몫, 마감 기준)</h3>
              <div className="mini-bars">{x.monthly.map((m: any) => <div key={m.period} title={`${m.period}: ${won(m.amount)}원`}><span style={{ height: `${Math.abs(m.amount) / max * 100}%` }} className={m.amount < 0 ? 'neg' : ''} /><small>{m.period.slice(4)}월</small><small className="tnum">{won(Math.round(m.amount / 10000))}만</small></div>)}</div>
            </>}
          </section>
        )
      })}
    </Page>
  )
}
