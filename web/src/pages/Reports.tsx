import { Fragment, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { api } from '../api'
import { useApi, Page, Badge, ErrorBox, Toast, Empty } from '../components/ui'
import { useSession } from '../session'
import { won, dt, ROLE_NAMES, DIVISIONS } from '../format'
import { useNames } from '../components/directory'
import { EMP_TYPE } from './Hr'

const lastMonth = () => { const d = new Date(Date.now() + 9 * 3600e3); d.setUTCDate(1); d.setUTCMonth(d.getUTCMonth() - 1); return d.toISOString().slice(0, 7) }
const SEC: Record<string, string> = { KPI: '핵심 지표', PNL: '손익 요약', DIVISION: '구분별', LOGISTICS: '물류', STOCK: '재고·손실', LABOR_TOTAL: '인건비', LABOR_DETAIL: '개인별 인건비', RENT_OPEX: '임대·운영비', ROYALTY: '로열티', SETTLEMENT: '결산액', APPROVALS: '결재 현황', OWN_PAYSLIP: '내 급여' }
const DIV = DIVISIONS

export function Reports() {
  const s = useSession()
  const nav = useNavigate()
  const storeId = s.storeId !== 'ALL' ? s.storeId : ''
  const list = useApi<any[]>(`/api/core/reports${storeId ? `?storeId=${storeId}` : ''}`, [s.storeId])
  const [month, setMonth] = useState(lastMonth())
  const [err, setErr] = useState<string | null>(null)
  const name = useNames()
  const canPublish = !!storeId && s.has('HQ_ADMIN', 'OWNER')
  const publish = async () => {
    setErr(null)
    try { const r: any = await api('/api/core/reports', { body: { storeId, period: month.replace('-', '') } }); nav(`/reports/${r.id}`) } catch (e: any) { setErr(e.message) }
  }
  return (
    <Page title="리포트" sub="월간 1장 리포트 · 발행 시점 수치로 고정 · 보는 사람 등급에 따라 섹션이 다릅니다"
      actions={canPublish && <><input id="rp-month" type="month" aria-label="월" value={month} onChange={e => setMonth(e.target.value)} /><button className="primary" onClick={publish}>리포트 발행</button></>}>
      <ErrorBox error={list.error || err} />
      <div className="card table-wrap">
        <table className="tbl">
          <thead><tr><th>기간</th><th>매장</th><th>버전</th><th>발행</th><th>발행자</th></tr></thead>
          <tbody>{(list.data ?? []).map(r => (
            <tr key={r.id} className="clickable" onClick={() => nav(`/reports/${r.id}`)}>
              <td><b>{String(r.period_from).slice(0, 7)}</b></td><td>{r.store_name}</td><td>v{r.version}</td><td>{dt(r.published_at)}</td><td>{name(r.published_by)}</td>
            </tr>))}</tbody>
        </table>
        {list.data?.length === 0 && <Empty>발행된 리포트가 없습니다.{canPublish ? ' 월을 고르고 발행하세요.' : ''}</Empty>}
      </div>
    </Page>
  )
}

export function ReportView() {
  const { id } = useParams()
  const s = useSession()
  const d = useApi<any>(`/api/core/reports/${id}`)
  const name = useNames()
  const r = d.data
  if (!r) return <Page title="리포트"><ErrorBox error={d.error} /></Page>
  const S = r.sections
  const roleLabel = [...s.roles].map(x => ROLE_NAMES[x]).join(' · ')
  const kpi = S.KPI
  return (
    <div className="report-wrap">
      <div className="report-actions no-print">
        <span className="muted small">{roleLabel} 기준으로 보이는 섹션: {r.visible.map((v: string) => SEC[v]).join(', ')}</span>
        <button className="primary" onClick={() => { api(`/api/core/reports/${id}?format=PDF`).catch(() => {}); window.print() }}>인쇄 · PDF 저장</button>
      </div>
      <article className="report a4">
        <header className="rp-head">
          <div><h1>{r.store_name} 월간 리포트</h1><span>{r.period} · v{r.version}</span></div>
          <div className="rp-meta">발행 {dt(r.published_at)} · {name(r.published_by)}<br />금액: 공급가(VAT 제외) · 열람: {roleLabel}</div>
        </header>
        {kpi && <section className="rp-kpi">
          {[['순매출', kpi.net_sales], ['매출총이익', kpi.gross_profit], ['영업이익', kpi.operating_profit]].map(([l, v]) => <div key={l as string}><small>{l}</small><b>{won(v as number)}</b></div>)}
          {S.SETTLEMENT && <div className="hl"><small>결산액</small><b>{won(S.SETTLEMENT.settlement_amount)}</b></div>}
          {[['원가율', 'cogs'], ['인건비율', 'labor'], ['손실률', 'loss']].map(([l, k]) => <div key={k}><small>{l}</small><b>{kpi.ratios?.[k] == null ? '—' : kpi.ratios[k] + '%'}</b></div>)}
        </section>}
        <div className="rp-grid">
          {S.PNL && <section><h2>손익 요약</h2><table className="rp-tbl"><tbody>
            {[['순매출', 'net_sales'], ['매출원가', 'cogs'], ['매출총이익', 'gross_profit'], ['인건비', 'labor'], ['임대료', 'rent'], ['공과금', 'utility'], ['내부사용', 'internal_use'], ['운영비', 'operating_exp'], ['손실·결손', 'stock_loss'], ['감가상각', 'depreciation'], ['영업이익', 'operating_profit']]
              .map(([l, k]) => <tr key={k} className={['gross_profit', 'operating_profit'].includes(k) ? 'b' : ''}><td>{l}</td><td>{won(S.PNL[k])}</td></tr>)}
            {S.ROYALTY && <tr><td>로열티</td><td>{won(S.ROYALTY.royalty)}</td></tr>}
            {S.SETTLEMENT && <tr className="b"><td>결산액</td><td>{won(S.SETTLEMENT.settlement_amount)}</td></tr>}
          </tbody></table></section>}
          {S.DIVISION && <section><h2>구분별</h2><table className="rp-tbl"><thead><tr><th></th><th>매출</th><th>원가</th><th>손실</th></tr></thead><tbody>
            {Object.entries(S.DIVISION).filter(([k]) => DIV[k]).map(([k, v]: any) => <tr key={k}><td>{DIV[k]}</td><td>{won(v.sales)}</td><td>{won(v.cogs)}</td><td>{won(v.loss)}</td></tr>)}
          </tbody></table></section>}
          {S.LOGISTICS && <section><h2>물류</h2><table className="rp-tbl"><tbody>
            <tr><td>요청 / 완료 / 진행 중</td><td>{S.LOGISTICS.total} / {S.LOGISTICS.completed} / {S.LOGISTICS.open}건</td></tr>
            <tr><td>평균 리드타임 (요청→완료)</td><td>{S.LOGISTICS.lead_hours ?? '—'}시간</td></tr>
            <tr><td>완료 금액</td><td>{won(S.LOGISTICS.completed_amount)}</td></tr>
            <tr><td>외부 구매 비중</td><td>{S.LOGISTICS.completed_amount ? Math.round(S.LOGISTICS.external_amount / S.LOGISTICS.completed_amount * 1000) / 10 : 0}%</td></tr>
          </tbody></table></section>}
          {S.STOCK && <section><h2>재고·손실</h2><table className="rp-tbl"><tbody>
            {S.STOCK.closing_stock != null && <tr><td>기말 재고</td><td>{won(S.STOCK.closing_stock)}</td></tr>}
            {S.STOCK.by_type.map((t: any) => <tr key={t.adj_type}><td>{({ LOSS: '손실', SHORTAGE: '결손', INTERNAL_USE: '내부사용' } as any)[t.adj_type]} {t.cnt}건</td><td>{won(t.amount)}</td></tr>)}
            {S.STOCK.top.map((t: any, i: number) => <tr key={i}><td className="sub">{t.name} · {t.reason}</td><td>{won(t.amount)}</td></tr>)}
            {S.STOCK.by_type.length === 0 && <tr><td colSpan={2} className="muted">이 달 승인된 손실·결손 없음</td></tr>}
          </tbody></table></section>}
          {S.LABOR_TOTAL && <section><h2>인건비</h2><table className="rp-tbl"><tbody>
            {S.LABOR_TOTAL.headcount != null ? <>
              <tr><td>인원 · 상태</td><td>{S.LABOR_TOTAL.headcount}명 · {S.LABOR_TOTAL.status}</td></tr>
              <tr><td>지급 총액</td><td>{won(S.LABOR_TOTAL.gross)}</td></tr>
              <tr><td>사업자 부담 + 퇴직 충당</td><td>{won(Number(S.LABOR_TOTAL.employer) + Number(S.LABOR_TOTAL.severance))}</td></tr>
            </> : <tr><td colSpan={2} className="muted">이 달 급여 실행 없음</td></tr>}
            {S.LABOR_DETAIL?.map((p: any, i: number) => <tr key={i}><td className="sub">{p.name} · {EMP_TYPE[p.employment_type]}</td><td>{won(p.gross_pay)}</td></tr>)}
          </tbody></table></section>}
          {S.RENT_OPEX && <section><h2>임대·운영비</h2><table className="rp-tbl"><tbody>
            {[['임대료·관리비', 'rent'], ['공과금', 'utility'], ['내부사용', 'internal_use'], ['운영비', 'operating_exp'], ['감가상각', 'depreciation']].map(([l, k]) => <tr key={k}><td>{l}</td><td>{won(S.RENT_OPEX[k])}</td></tr>)}
          </tbody></table></section>}
          {S.APPROVALS && <section><h2>결재 현황</h2><table className="rp-tbl"><tbody>
            <tr><td>진행 중</td><td>{S.APPROVALS.in_progress}건</td></tr><tr><td>이 달 승인 / 반려</td><td>{S.APPROVALS.approved} / {S.APPROVALS.rejected}건</td></tr>
            {S.MY_REQUESTS && <tr><td>내 물류 요청 (완료)</td><td>{S.MY_REQUESTS.total} ({S.MY_REQUESTS.completed})건</td></tr>}
          </tbody></table></section>}
          {S.OWN_PAYSLIP && <section><h2>내 급여</h2><table className="rp-tbl"><tbody>
            {S.OWN_PAYSLIP.length ? S.OWN_PAYSLIP.map((p: any) => <Fragment key={p.id}><tr><td>지급</td><td>{won(p.gross_pay)}</td></tr><tr className="b"><td>실지급</td><td>{won(p.net_pay)}</td></tr></Fragment>)
              : <tr><td colSpan={2} className="muted">발행된 명세서 없음</td></tr>}
          </tbody></table></section>}
        </div>
        <footer className="rp-foot">Espresso · 이 리포트는 발행 시점의 스냅샷입니다. 섹션 노출은 등급과 매장 설정(개인별 급여 열람)에 따릅니다.</footer>
      </article>
    </div>
  )
}
