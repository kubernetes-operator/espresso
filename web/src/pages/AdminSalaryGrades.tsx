import { useState } from 'react'
import { api } from '../api'
import { useApi, Page, ErrorBox, Toast, Modal } from '../components/ui'
import { won, num } from '../format'

/** 연봉 테이블: 직급군(접두어) × 20단계. 금액을 바꾸면 그 등급 직원의 현재 계약 월급에 바로 반영 */
export function AdminSalaryGrades() {
  const d = useApi<any[]>('/api/core/hr/salary-grades')
  const gg = useApi<any[]>('/api/core/hr/salary-grade-groups')
  const [group, setGroup] = useState('s')
  const [edit, setEdit] = useState<Record<string, string>>({})
  const [adding, setAdding] = useState(false)
  const [msg, setMsg] = useState<string | null>(null)
  const [err, setErr] = useState<string | null>(null)
  const groups = [...new Map((d.data ?? []).map(g => [g.grade_group, g.group_name])).entries()]
  const rows = (d.data ?? []).filter(g => g.grade_group === group)
  const rule = (gg.data ?? []).find(g => g.grade_group === group)
  const monthlyUnit = rule?.pay_unit === 'MONTHLY'
  const save = async (code: string) => {
    setErr(null)
    try { await api(`/api/core/hr/salary-grades/${code}`, { method: 'PUT', body: { annualSalary: Number(edit[code]) } }); setMsg(`${code} 연봉을 바꿨습니다`); setEdit(e => { const x = { ...e }; delete x[code]; return x }); d.reload() }
    catch (e: any) { setErr(e.message) }
  }
  return (
    <Page title="연봉 테이블" sub="직급군별 20단계 · 코드 = 접두어 + 단계 (예: 사원 s1~s20, 인사 관리자 h1~h20) · 월 급여 = 연봉 ÷ 12"
      actions={<button onClick={() => setAdding(true)}>+ 직급군</button>}>
      <ErrorBox error={d.error || err} />
      <div className="seg small" role="tablist">
        {groups.map(([g, n]) => <button key={g} className={group === g ? 'on' : ''} onClick={() => setGroup(g)}>{n} ({g})</button>)}
      </div>
      {rule && <RuleCard key={group + rule.updated_at} rule={rule} onDone={(m: string) => { setMsg(m); d.reload(); gg.reload() }} onErr={setErr} />}
      <div className="card table-wrap">
        <table className="tbl">
          <thead><tr><th>코드</th><th>단계</th><th className="num">{monthlyUnit ? '연 환산' : '연봉'}</th><th className="num">{monthlyUnit ? '월 금액 (3.3% 원천징수 전)' : '월 급여'}</th><th></th></tr></thead>
          <tbody>{rows.map(g => (
            <tr key={g.code}>
              <td><code>{g.code}</code></td><td>{g.step}단계</td>
              <td className="num">{edit[g.code] !== undefined
                ? <input aria-label={`${g.code} 연봉`} inputMode="numeric" style={{ width: 150, textAlign: 'right' }} value={num(edit[g.code])} onChange={e => setEdit({ ...edit, [g.code]: e.target.value.replace(/\D/g, '') })} />
                : won(g.annual_salary)}</td>
              <td className="num">{won(edit[g.code] !== undefined ? Math.floor(Number(edit[g.code]) / 12) : g.monthly)}</td>
              <td className="nowrap">{edit[g.code] !== undefined
                ? <><button className="small primary" onClick={() => save(g.code)}>저장</button><button className="ghost small" onClick={() => setEdit(e => { const x = { ...e }; delete x[g.code]; return x })}>취소</button></>
                : <button className="ghost small" onClick={() => setEdit({ ...edit, [g.code]: String(g.annual_salary) })}>수정</button>}</td>
            </tr>))}</tbody>
        </table>
      </div>
      <p className="muted small">프리랜서(f)는 월 금액 기준이며 급여 계산 시 4대보험 없이 3.3%(사업소득세 3% + 지방소득세 0.3%)만 뺍니다. 데모 금액입니다. 운영 전 회사 연봉표로 바꾸세요. 연봉을 바꾸면 이 등급을 쓰는 직원의 다음 급여 계산부터 반영되고, 이미 확정된 급여는 바뀌지 않습니다.</p>
      {adding && <AddGroup onClose={() => setAdding(false)} onDone={(g: string) => { setAdding(false); setGroup(g); setMsg('직급군을 추가했습니다'); d.reload() }} />}
      <Toast msg={msg} onDone={() => setMsg(null)} />
    </Page>
  )
}

/** 1단계 금액 + 상승 규칙 → 2~20단계 자동 작성 */
function RuleCard({ rule, onDone, onErr }: any) {
  const monthly = rule.pay_unit === 'MONTHLY'
  const [base, setBase] = useState(String(monthly ? Math.round(rule.base_amount / 12) : rule.base_amount))
  const [mode, setMode] = useState<'rate' | 'inc'>(rule.raise_rate != null ? 'rate' : 'inc')
  const [rate, setRate] = useState(rule.raise_rate != null ? String(Math.round(Number(rule.raise_rate) * 10000) / 100) : '3')
  const [inc, setInc] = useState(String(rule.increment != null ? (monthly ? Math.round(rule.increment / 12) : rule.increment) : ''))
  const preview = (n: number) => mode === 'rate' ? Number(base) * Math.pow(1 + Number(rate) / 100, n - 1) : Number(base) + (n - 1) * Number(inc || 0)
  const apply = async () => {
    onErr(null)
    try {
      await api(`/api/core/hr/salary-grade-groups/${rule.grade_group}`, { method: 'PUT', body: { base: Number(base), raiseRate: mode === 'rate' ? Number(rate) / 100 : null, increment: mode === 'inc' ? Number(inc || 0) : null } })
      onDone(`${rule.group_name} 20단계를 다시 작성했습니다 (해당 등급 직원 월급 갱신)`)
    } catch (e: any) { onErr(e.message) }
  }
  return (
    <section className="card">
      <form className="form" onSubmit={e => { e.preventDefault(); apply() }}>
        <b>{rule.group_name} ({rule.grade_group}1~{rule.grade_group}20) 단계 규칙</b>
        <div className="row">
          <div><label htmlFor="rule-base">1단계 {monthly ? '월 금액' : '연봉'} (원)</label><input id="rule-base" required inputMode="numeric" value={num(base)} onChange={e => setBase(e.target.value.replace(/\D/g, ''))} /></div>
          <div><label htmlFor="rule-mode">상승 방식</label><select id="rule-mode" value={mode} onChange={e => setMode(e.target.value as any)}><option value="rate">상승률 (%) — 단계마다 복리</option><option value="inc">증가액 (원) — 단계마다 같은 금액</option></select></div>
          {mode === 'rate' ? <div><label htmlFor="rule-rate">단계별 상승률 (%)</label><input id="rule-rate" required inputMode="decimal" value={rate} onChange={e => setRate(e.target.value)} /></div>
            : <div><label htmlFor="rule-inc">단계별 증가액 (원)</label><input id="rule-inc" required inputMode="numeric" value={num(inc)} onChange={e => setInc(e.target.value.replace(/\D/g, ''))} /></div>}
        </div>
        <p className="muted small">미리보기: 1단계 {won(preview(1))} · 5단계 {won(Math.round(preview(5) / 1000) * 1000)} · 10단계 {won(Math.round(preview(10) / 1000) * 1000)} · 20단계 {won(Math.round(preview(20) / 1000) * 1000)}원{monthly ? ' (월)' : ''} — 1,000원 단위 반올림</p>
        <button className="primary">20단계 다시 작성</button>
      </form>
    </section>
  )
}

function AddGroup({ onClose, onDone }: any) {
  const [f, setF] = useState({ prefix: '', name: '', base: '', increment: '' })
  const [err, setErr] = useState<string | null>(null)
  const save = async () => {
    setErr(null)
    try { await api('/api/core/hr/salary-grades/groups', { body: { prefix: f.prefix, name: f.name, base: Number(f.base), increment: Number(f.increment || 0) } }); onDone(f.prefix.toLowerCase()) }
    catch (e: any) { setErr(e.message) }
  }
  return (
    <Modal title="직급군 추가 (20단계)" onClose={onClose}>
      <form className="form" onSubmit={e => { e.preventDefault(); save() }}>
        <div className="row">
          <div><label htmlFor="sg-p">접두어 (영문 소문자)</label><input id="sg-p" required value={f.prefix} placeholder="예: b" onChange={e => setF({ ...f, prefix: e.target.value })} /></div>
          <div><label htmlFor="sg-n">직급군 이름</label><input id="sg-n" required value={f.name} placeholder="예: 베이커" onChange={e => setF({ ...f, name: e.target.value })} /></div>
        </div>
        <div className="row">
          <div><label htmlFor="sg-b">1단계 연봉 (원)</label><input id="sg-b" required inputMode="numeric" value={num(f.base)} onChange={e => setF({ ...f, base: e.target.value.replace(/\D/g, '') })} /></div>
          <div><label htmlFor="sg-i">단계별 인상액 (원)</label><input id="sg-i" inputMode="numeric" value={num(f.increment)} onChange={e => setF({ ...f, increment: e.target.value.replace(/\D/g, '') })} /></div>
        </div>
        {f.base && <p className="muted small">20단계 연봉 = {won(Number(f.base) + 19 * Number(f.increment || 0))}원. 만든 뒤 단계별로 고칠 수 있습니다.</p>}
        {err && <div className="error-box">{err}</div>}
        <button className="primary">추가</button>
      </form>
    </Modal>
  )
}
