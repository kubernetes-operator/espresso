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
  const storeId = s.storeId !== 'ALL' ? s.storeId : ''
  const runs = useApi<any[]>(storeId ? `/api/core/payroll/runs?storeId=${storeId}` : null, [storeId])
  const [month, setMonth] = useState(lastMonth())
  const [err, setErr] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const canCalc = s.has('HR_MGR', 'HQ_ADMIN')
  const calc = async () => {
    setErr(null); setBusy(true)
    try { const r: any = await api('/api/core/payroll/runs', { body: { storeId, period: month.replace('-', '') } }); nav(`/payroll/${r.id}`) } catch (e: any) { setErr(e.message) } finally { setBusy(false) }
  }
  if (!storeId) return <PayrollSummary />
  return (
    <Page title={`급여 · ${s.stores.find(x => x.id === storeId)?.name ?? ''}`} sub="이 사이트 급여만 표시 · 근무기록·연봉 등급 기준 자동 계산 → 인사관리자 → 점주 → 총괄 결재 → 명세서 발행"
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

/** 전체(합산): 사이트별 지급 총액 표 — 계산·상신은 매장을 골라서 */
function PayrollSummary() {
  const nav = useNavigate()
  const [period, setPeriod] = useState('')
  const d = useApi<any>(`/api/core/payroll/summary${period ? `?period=${period}` : ''}`, [period])
  const rows: any[] = d.data?.rows ?? []
  const byPeriod = [...new Set(rows.map(r => r.period))]
  const s = useSession()
  const empPeriod = period || byPeriod[0]
  const emps = useApi<any[]>(empPeriod && s.has('HQ_ADMIN', 'HR_MGR') ? `/api/core/payroll/summary/employees?period=${empPeriod}` : null, [empPeriod])
  const sum = (list: any[], k: string) => list.reduce((t, r) => t + Number(r[k] ?? 0), 0)
  return (
    <Page title="급여 · 전체" sub="사이트별 급여 지급 총액 (전체 화면에서는 계산·상신하지 않습니다 — 상단에서 매장을 고르세요)"
      actions={<select id="ps-period" aria-label="귀속월" value={period} onChange={e => setPeriod(e.target.value)}>
        <option value="">전체 기간</option>{(d.data?.periods ?? []).map((p: string) => <option key={p} value={p}>{p.slice(0, 4)}-{p.slice(4)}</option>)}
      </select>}>
      <ErrorBox error={d.error} />
      {byPeriod.map(p => {
        const list = rows.filter(r => r.period === p)
        return (
          <section key={p} className="card table-wrap">
            <h2>{p.slice(0, 4)}년 {Number(p.slice(4))}월</h2>
            <table className="tbl">
              <thead><tr><th>사이트</th><th className="num">인원</th><th className="num">지급 총액</th><th className="num">공제</th><th className="num">실지급</th><th className="num">사업자 부담</th><th className="num">퇴직 충당</th><th>상태</th></tr></thead>
              <tbody>{list.map(r => (
                <tr key={r.id} className="clickable" onClick={() => nav(`/payroll/${r.id}`)}>
                  <td><b>{r.store_name}</b></td><td className="num">{r.headcount}</td><td className="num">{won(r.total_gross)}</td><td className="num">{won(r.total_deduction)}</td>
                  <td className="num">{won(r.total_net)}</td><td className="num">{won(r.total_employer_contrib)}</td><td className="num">{won(r.total_severance_accrual)}</td>
                  <td><Badge tone={TONE[r.status]}>{PR_ST[r.status]}</Badge></td>
                </tr>))}
                <tr className="b"><td><b>합계</b></td><td className="num">{sum(list, 'headcount')}</td><td className="num"><b>{won(sum(list, 'total_gross'))}</b></td><td className="num">{won(sum(list, 'total_deduction'))}</td>
                  <td className="num"><b>{won(sum(list, 'total_net'))}</b></td><td className="num">{won(sum(list, 'total_employer_contrib'))}</td><td className="num">{won(sum(list, 'total_severance_accrual'))}</td><td></td></tr>
              </tbody>
            </table>
          </section>
        )
      })}
      {d.data && rows.length === 0 && <Empty>급여 실행이 없습니다.</Empty>}
      {emps.data && emps.data.length > 0 && <section className="card table-wrap">
        <h2>{empPeriod.slice(0, 4)}년 {Number(empPeriod.slice(4))}월 전체 직원 급여 ({emps.data.length}명)</h2>
        <table className="tbl">
          <thead><tr><th>사이트</th><th>사번</th><th>이름</th><th>직책</th><th>형태</th><th>등급</th><th className="num">근무(h)</th><th className="num">지급</th><th className="num">공제</th><th className="num">실지급</th><th className="num">사업자 부담</th><th>상태</th></tr></thead>
          <tbody>{emps.data.map(p => (
            <tr key={p.id}><td><small>{p.store_name}</small></td><td><code>{p.emp_no}</code></td><td><b>{p.name}</b></td><td><small>{p.job_title}</small></td><td>{EMP_TYPE[p.employment_type]}</td>
              <td>{p.salary_grade ?? '—'}</td><td className="num">{Math.round(p.worked_minutes / 6) / 10}</td><td className="num">{won(p.gross_pay)}</td><td className="num">{won(p.total_deduction)}</td>
              <td className="num"><b>{won(p.net_pay)}</b></td><td className="num">{won(p.employer_contrib)}</td><td><Badge tone={TONE[p.run_status]}>{PR_ST[p.run_status]}</Badge></td></tr>))}
            <tr className="b"><td colSpan={7}><b>합계</b></td><td className="num"><b>{won(sum(emps.data, 'gross_pay'))}</b></td><td className="num">{won(sum(emps.data, 'total_deduction'))}</td>
              <td className="num"><b>{won(sum(emps.data, 'net_pay'))}</b></td><td className="num">{won(sum(emps.data, 'employer_contrib'))}</td><td></td></tr>
          </tbody>
        </table>
      </section>}
    </Page>
  )
}

export function PayrollDetail() {
  const { id } = useParams()
  const s = useSession()
  const d = useApi<any>(`/api/core/payroll/runs/${id}`)
  const [slip, setSlip] = useState<string | null>(null)
  const [extras, setExtras] = useState(false)
  const [msg, setMsg] = useState<string | null>(null)
  const [err, setErr] = useState<string | null>(null)
  const r = d.data
  if (!r) return <Page title="급여"><ErrorBox error={d.error} /></Page>
  const hr = s.has('HR_MGR', 'HQ_ADMIN')
  const run = async (p: string, body: any, ok: string) => { setErr(null); try { await api(p, { body }); setMsg(ok); d.reload() } catch (e: any) { setErr(e.message) } }
  return (
    <Page title={`${s.stores.find(x => x.id === r.store_id)?.name ?? ''} ${r.period.slice(0, 4)}-${r.period.slice(4)} 급여`} sub={<><Badge tone={TONE[r.status]}>{PR_ST[r.status]}</Badge> · {r.headcount}명</>}
      actions={hr && <>
        {['DRAFT', 'CALCULATED', 'REJECTED'].includes(r.status) && <button onClick={() => setExtras(true)}>상여·퇴직금</button>}
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
      {extras && <ExtrasModal run={r} onClose={() => setExtras(false)}
        onDone={async () => { setExtras(false); await run('/api/core/payroll/runs', { storeId: r.store_id, period: r.period }, '상여·퇴직금을 반영해 다시 계산했습니다') }} />}
      <Toast msg={msg} onDone={() => setMsg(null)} />
    </Page>
  )
}

/** 상여금·퇴직금 입력 (월·직원별, 다시 계산해도 유지) */
function ExtrasModal({ run, onClose, onDone }: any) {
  const d = useApi<any[]>(`/api/core/payroll/extras?storeId=${run.store_id}&period=${run.period}`)
  const [vals, setVals] = useState<Record<string, { bonus: string; severance: string }>>({})
  const [err, setErr] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const v = (e: any) => vals[e.employee_id] ?? { bonus: e.bonus != null ? String(e.bonus) : '', severance: e.severance != null ? String(e.severance) : '' }
  const set = (e: any, k: 'bonus' | 'severance', x: string) => setVals({ ...vals, [e.employee_id]: { ...v(e), [k]: x.replace(/\D/g, '') } })
  const save = async () => {
    setErr(null); setBusy(true)
    try {
      for (const e of d.data ?? []) {
        const cur = vals[e.employee_id]; if (!cur) continue
        if (cur.bonus !== (e.bonus != null ? String(e.bonus) : ''))
          await api('/api/core/payroll/extras', { method: 'PUT', body: { storeId: run.store_id, period: run.period, employeeId: e.employee_id, code: 'BONUS', amount: Number(cur.bonus || 0) } })
        if (cur.severance !== (e.severance != null ? String(e.severance) : ''))
          await api('/api/core/payroll/extras', { method: 'PUT', body: { storeId: run.store_id, period: run.period, employeeId: e.employee_id, code: 'SEVERANCE', amount: Number(cur.severance || 0) } })
      }
      onDone()
    } catch (e: any) { setErr(e.message) } finally { setBusy(false) }
  }
  return (
    <Modal title={`상여금·퇴직금 · ${run.period.slice(0, 4)}-${run.period.slice(4)}`} onClose={onClose} wide>
      <ErrorBox error={d.error || err} />
      <div className="table-wrap"><table className="tbl compact">
        <thead><tr><th>사번</th><th>이름</th><th>입사 / 퇴사</th><th className="num">상여금 (원)</th><th className="num">퇴직금 (원)</th></tr></thead>
        <tbody>{(d.data ?? []).map(e => (
          <tr key={e.employee_id}>
            <td><code>{e.emp_no}</code></td><td>{e.name}</td>
            <td><small>{e.hire_date}{e.resign_date && <> / <b>{e.resign_date} 퇴사</b></>}</small></td>
            <td className="num"><input aria-label={`${e.name} 상여금`} className="mini" style={{ width: 120 }} inputMode="numeric" value={v(e).bonus} onChange={x => set(e, 'bonus', x.target.value)} /></td>
            <td className="num"><input aria-label={`${e.name} 퇴직금`} className="mini" style={{ width: 120 }} inputMode="numeric" value={v(e).severance} onChange={x => set(e, 'severance', x.target.value)} />
              {e.suggested_severance != null && !v(e).severance && <div><button className="ghost small" onClick={() => set(e, 'severance', String(e.suggested_severance))}>추정 {won(e.suggested_severance)} 넣기</button></div>}</td>
          </tr>))}</tbody>
      </table></div>
      <p className="muted small">상여금은 과세 급여에 더해 4대보험·소득세를 계산합니다. 퇴직금은 지급액에 넣되 4대보험·근로소득세에서 빼고, 퇴직소득세는 별도로 신고합니다. 추정치 = 최근 3개월 지급 ÷ 91일 × 30일 × 재직일수/365 (1년 이상 근무 퇴사자).</p>
      <button className="primary" disabled={busy} onClick={save}>{busy ? '저장 중…' : '저장하고 다시 계산'}</button>
    </Modal>
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
