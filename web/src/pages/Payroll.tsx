import { Fragment, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { api } from '../api'
import { useApi, Page, Badge, ErrorBox, Modal, Toast, Empty } from '../components/ui'
import { ApprovalPanel, Timeline } from '../components/DocPanels'
import { useSession } from '../session'
import { won, dt } from '../format'
import { EMP_TYPE } from './Hr'

export const PR_ST: Record<string, string> = { DRAFT: '작성', CALCULATED: '계산됨', REQUESTED: '결재 중', CONFIRMED: '확정', PAID: '지급 완료', REJECTED: '반려' }
const TONE: Record<string, any> = { CALCULATED: 'info', REQUESTED: 'warn', CONFIRMED: 'ok', PAID: 'ok', REJECTED: 'bad' }
const lastMonth = () => { const d = new Date(Date.now() + 9 * 3600e3); d.setUTCDate(1); d.setUTCMonth(d.getUTCMonth() - 1); return d.toISOString().slice(0, 7) }

export function Payroll() {
  const s = useSession()
  const nav = useNavigate()
  const storeId = s.storeId !== 'ALL' ? s.storeId : s.stores[0]?.id
  const runs = useApi<any[]>(storeId ? `/api/core/payroll/runs?storeId=${storeId}` : null, [storeId])
  const [month, setMonth] = useState(lastMonth())
  const [err, setErr] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const canCalc = s.has('HR_MGR', 'HQ_ADMIN')
  const calc = async () => {
    setErr(null); setBusy(true)
    try { const r: any = await api('/api/core/payroll/runs', { body: { storeId, period: month.replace('-', '') } }); nav(`/payroll/${r.id}`) } catch (e: any) { setErr(e.message) } finally { setBusy(false) }
  }
  return (
    <Page title="급여" sub={`${s.stores.find(x => x.id === storeId)?.name ?? ''} · 근무기록 기준 자동 계산 → 인적관리자 → 점주 → 총괄 결재 → 명세서 발행`}
      actions={canCalc && <>
        <input id="pr-month" type="month" aria-label="급여 월" value={month} onChange={e => setMonth(e.target.value)} />
        <button className="primary" disabled={busy} onClick={calc}>{busy ? '계산 중…' : '급여 계산'}</button>
      </>}>
      <ErrorBox error={runs.error || err} />
      <div className="card table-wrap">
        <table className="tbl">
          <thead><tr><th>귀속월</th><th className="num">인원</th><th className="num">지급 총액</th><th className="num">공제</th><th className="num">실지급</th><th className="num">사업자 부담</th><th className="num">퇴직 충당</th><th>상태</th><th>확정</th></tr></thead>
          <tbody>{(runs.data ?? []).map(r => (
            <tr key={r.id} className="clickable" onClick={() => nav(`/payroll/${r.id}`)}>
              <td><b>{r.period.slice(0, 4)}-{r.period.slice(4)}</b></td><td className="num">{r.headcount}</td><td className="num">{won(r.total_gross)}</td><td className="num">{won(r.total_deduction)}</td>
              <td className="num">{won(r.total_net)}</td><td className="num">{won(r.total_employer_contrib)}</td><td className="num">{won(r.total_severance_accrual)}</td>
              <td><Badge tone={TONE[r.status]}>{PR_ST[r.status]}</Badge></td><td>{dt(r.confirmed_at)}</td>
            </tr>))}</tbody>
        </table>
        {runs.data?.length === 0 && <Empty>급여 실행이 없습니다.{canCalc ? ' 월을 고르고 급여 계산을 누르세요.' : ''}</Empty>}
      </div>
      <p className="muted small">요율·최저임금은 연도별 표(설정)로 관리합니다. 데모 요율과 간이세액 추정치는 운영 전 고시값으로 바꿔야 합니다.</p>
    </Page>
  )
}

export function PayrollDetail() {
  const { id } = useParams()
  const s = useSession()
  const d = useApi<any>(`/api/core/payroll/runs/${id}`)
  const [slip, setSlip] = useState<string | null>(null)
  const [msg, setMsg] = useState<string | null>(null)
  const [err, setErr] = useState<string | null>(null)
  const r = d.data
  if (!r) return <Page title="급여"><ErrorBox error={d.error} /></Page>
  const hr = s.has('HR_MGR', 'HQ_ADMIN')
  const run = async (p: string, body: any, ok: string) => { setErr(null); try { await api(p, { body }); setMsg(ok); d.reload() } catch (e: any) { setErr(e.message) } }
  return (
    <Page title={`${r.period.slice(0, 4)}-${r.period.slice(4)} 급여`} sub={<><Badge tone={TONE[r.status]}>{PR_ST[r.status]}</Badge> · {r.headcount}명</>}
      actions={hr && <>
        {['CALCULATED', 'REJECTED'].includes(r.status) && <button onClick={() => run('/api/core/payroll/runs', { storeId: r.store_id, period: r.period }, '다시 계산했습니다')}>다시 계산</button>}
        {r.status === 'CALCULATED' && <button className="primary" onClick={() => run(`/api/core/payroll/runs/${id}/submit`, {}, '상신했습니다')}>상신</button>}
        {['CONFIRMED', 'PAID'].includes(r.status) && <button className="primary" onClick={() => run(`/api/core/payroll/runs/${id}/publish`, {}, '명세서를 발행했습니다')}>명세서 발행</button>}
        {r.status === 'CONFIRMED' && <button onClick={() => run(`/api/core/payroll/runs/${id}/paid`, {}, '지급 완료로 바꿨습니다')}>지급 완료</button>}
      </>}>
      <ErrorBox error={err} />
      <div className="detail">
        <div className="detail-main">
          <div className="grid3">
            <div className="card stat"><small>지급 총액</small><b className="tnum">{won(r.total_gross)}원</b></div>
            <div className="card stat"><small>공제 / 실지급</small><b className="tnum">{won(r.total_deduction)} / {won(r.total_net)}</b></div>
            <div className="card stat"><small>사업자 부담 + 퇴직 충당</small><b className="tnum">{won(Number(r.total_employer_contrib) + Number(r.total_severance_accrual))}원</b></div>
          </div>
          {r.detailVisible ? (
            <div className="card table-wrap">
              <table className="tbl">
                <thead><tr><th>사번</th><th>이름</th><th>형태</th><th className="num">근무</th><th className="num">지급</th><th className="num">공제</th><th className="num">실지급</th><th className="num">사업자 부담</th><th>발행</th></tr></thead>
                <tbody>{r.payslips.map((p: any) => (
                  <tr key={p.id} className="clickable" onClick={() => setSlip(p.id)}>
                    <td><code>{p.emp_no}</code></td><td><b>{p.name}</b></td><td>{EMP_TYPE[p.employment_type]}</td><td className="num">{Math.round(p.worked_minutes / 6) / 10}h</td>
                    <td className="num">{won(p.gross_pay)}</td><td className="num">{won(p.total_deduction)}</td><td className="num"><b>{won(p.net_pay)}</b></td><td className="num">{won(p.employer_contrib)}</td>
                    <td>{p.published_at ? <Badge tone="ok">{p.viewed_at ? '열람' : '발행'}</Badge> : '—'}</td>
                  </tr>))}</tbody>
              </table>
            </div>
          ) : <div className="note warn">이 매장은 점주의 개인별 급여 열람이 꺼져 있어 총액만 표시됩니다. (설정 › 매장 관리)</div>}
          <section className="card"><b>진행 이력</b><Timeline history={r.history} labels={PR_ST} /></section>
        </div>
        <aside className="detail-side"><section className="card"><ApprovalPanel approval={r.approval} onDone={() => { setMsg('처리했습니다'); d.reload() }} /></section></aside>
      </div>
      {slip && <PayslipModal id={slip} onClose={() => setSlip(null)} />}
      <Toast msg={msg} onDone={() => setMsg(null)} />
    </Page>
  )
}

export function PayslipModal({ id, onClose }: { id: string; onClose: () => void }) {
  const p = useApi<any>(`/api/core/payroll/payslips/${id}`)
  const d = p.data
  const group = (k: string) => (d?.items ?? []).filter((i: any) => i.kind === k && i.code !== 'WARN_MIN_WAGE')
  return (
    <Modal title={d ? `${d.period.slice(0, 4)}년 ${Number(d.period.slice(4))}월 급여명세서` : '급여명세서'} onClose={onClose}>
      <ErrorBox error={p.error} />
      {d && <div className="payslip">
        <p className="small">{d.store_name} · {d.name} ({d.emp_no}) · {EMP_TYPE[d.employment_type]} · {d.pay_type === 'HOURLY' ? '시급' : '월급'} {won(d.base_wage)}원</p>
        {d.items.some((i: any) => i.code === 'WARN_MIN_WAGE') && <div className="note warn">시급이 최저임금보다 낮습니다. 계약을 확인하세요.</div>}
        <div className="grid2 tight">
          <div><b>지급</b><dl className="totals">{group('EARNING').map((i: any) => <Fragment key={i.code}><dt>{i.name}</dt><dd>{won(i.amount)}</dd></Fragment>)}<dt className="strong">지급 합계</dt><dd className="strong">{won(d.gross_pay)}</dd></dl></div>
          <div><b>공제</b><dl className="totals">{group('DEDUCTION').map((i: any) => <Fragment key={i.code}><dt>{i.name}</dt><dd>{won(i.amount)}</dd></Fragment>)}<dt className="strong">공제 합계</dt><dd className="strong">{won(d.total_deduction)}</dd></dl></div>
        </div>
        <dl className="totals inline"><dt className="strong">실지급액</dt><dd className="strong">{won(d.net_pay)}원</dd></dl>
        {group('EMPLOYER').length > 0 && <details><summary className="small muted">사업자 부담분 (명세서 참고)</summary>
          <dl className="totals">{group('EMPLOYER').map((i: any) => <Fragment key={i.code}><dt>{i.name}</dt><dd>{won(i.amount)}</dd></Fragment>)}</dl></details>}
      </div>}
    </Modal>
  )
}
