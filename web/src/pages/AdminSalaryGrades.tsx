import { useState } from 'react'
import { api } from '../api'
import { useApi, Page, ErrorBox, Toast, Modal } from '../components/ui'
import { won } from '../format'

/** 연봉 테이블: 직급군(접두어) × 20단계. 금액을 바꾸면 그 등급 직원의 현재 계약 월급에 바로 반영 */
export function AdminSalaryGrades() {
  const d = useApi<any[]>('/api/core/hr/salary-grades')
  const [group, setGroup] = useState('s')
  const [edit, setEdit] = useState<Record<string, string>>({})
  const [adding, setAdding] = useState(false)
  const [msg, setMsg] = useState<string | null>(null)
  const [err, setErr] = useState<string | null>(null)
  const groups = [...new Map((d.data ?? []).map(g => [g.grade_group, g.group_name])).entries()]
  const rows = (d.data ?? []).filter(g => g.grade_group === group)
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
      <div className="card table-wrap">
        <table className="tbl">
          <thead><tr><th>코드</th><th>단계</th><th className="num">연봉</th><th className="num">월 급여</th><th></th></tr></thead>
          <tbody>{rows.map(g => (
            <tr key={g.code}>
              <td><code>{g.code}</code></td><td>{g.step}단계</td>
              <td className="num">{edit[g.code] !== undefined
                ? <input aria-label={`${g.code} 연봉`} inputMode="numeric" style={{ width: 150, textAlign: 'right' }} value={edit[g.code]} onChange={e => setEdit({ ...edit, [g.code]: e.target.value.replace(/\D/g, '') })} />
                : won(g.annual_salary)}</td>
              <td className="num">{won(edit[g.code] !== undefined ? Math.floor(Number(edit[g.code]) / 12) : g.monthly)}</td>
              <td className="nowrap">{edit[g.code] !== undefined
                ? <><button className="small primary" onClick={() => save(g.code)}>저장</button><button className="ghost small" onClick={() => setEdit(e => { const x = { ...e }; delete x[g.code]; return x })}>취소</button></>
                : <button className="ghost small" onClick={() => setEdit({ ...edit, [g.code]: String(g.annual_salary) })}>수정</button>}</td>
            </tr>))}</tbody>
        </table>
      </div>
      <p className="muted small">데모 금액입니다. 운영 전 회사 연봉표로 바꾸세요. 연봉을 바꾸면 이 등급을 쓰는 직원의 다음 급여 계산부터 반영되고, 이미 확정된 급여는 바뀌지 않습니다.</p>
      {adding && <AddGroup onClose={() => setAdding(false)} onDone={(g: string) => { setAdding(false); setGroup(g); setMsg('직급군을 추가했습니다'); d.reload() }} />}
      <Toast msg={msg} onDone={() => setMsg(null)} />
    </Page>
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
          <div><label htmlFor="sg-p">접두어 (영문 소문자)</label><input id="sg-p" value={f.prefix} placeholder="예: b" onChange={e => setF({ ...f, prefix: e.target.value })} /></div>
          <div><label htmlFor="sg-n">직급군 이름</label><input id="sg-n" value={f.name} placeholder="예: 베이커" onChange={e => setF({ ...f, name: e.target.value })} /></div>
        </div>
        <div className="row">
          <div><label htmlFor="sg-b">1단계 연봉 (원)</label><input id="sg-b" inputMode="numeric" value={f.base} onChange={e => setF({ ...f, base: e.target.value.replace(/\D/g, '') })} /></div>
          <div><label htmlFor="sg-i">단계별 인상액 (원)</label><input id="sg-i" inputMode="numeric" value={f.increment} onChange={e => setF({ ...f, increment: e.target.value.replace(/\D/g, '') })} /></div>
        </div>
        {f.base && <p className="muted small">20단계 연봉 = {won(Number(f.base) + 19 * Number(f.increment || 0))}원. 만든 뒤 단계별로 고칠 수 있습니다.</p>}
        {err && <div className="error-box">{err}</div>}
        <button className="primary" disabled={!f.prefix || !f.name || !f.base}>추가</button>
      </form>
    </Modal>
  )
}
