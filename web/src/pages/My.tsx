import { useEffect, useState } from 'react'
import { api } from '../api'
import { useApi, Page, Badge, ErrorBox, Toast, Empty } from '../components/ui'
import { won, dt } from '../format'
import { PayslipModal } from './Payroll'

export function My() {
  const me = useApi<any>('/api/core/hr/me')
  const [slip, setSlip] = useState<string | null>(null)
  const [msg, setMsg] = useState<string | null>(null)
  const [err, setErr] = useState<string | null>(null)
  const [now, setNow] = useState(Date.now())
  useEffect(() => { const t = setInterval(() => setNow(Date.now()), 30000); return () => clearInterval(t) }, [])
  const d = me.data
  const open = d?.open
  const clock = async (kind: 'in' | 'out') => {
    setErr(null)
    try { await api(`/api/core/hr/clock-${kind}`, { body: kind === 'out' ? { breakMinutes: 0 } : {} }); setMsg(kind === 'in' ? '출근했습니다' : '퇴근했습니다'); me.reload() } catch (e: any) { setErr(e.message) }
  }
  const mins = open ? Math.floor((now - new Date(open.clock_in).getTime()) / 60000) : 0
  return (
    <Page title="내 근무·급여" sub={d?.employees?.[0] ? `${d.employees[0].store_name} · ${d.employees[0].name}` : ''}>
      <ErrorBox error={me.error || err} />
      {d && d.employees.length === 0 && <div className="note warn">직원으로 등록되어 있지 않습니다. 인적 관리자에게 계정 연결을 요청하세요.</div>}
      {d && d.employees.length > 0 && <section className="card clock">
        {open ? <><div><small className="muted">출근 {dt(open.clock_in)}</small><b className="big-num">{Math.floor(mins / 60)}시간 {mins % 60}분</b></div><button className="danger big" onClick={() => clock('out')}>퇴근</button></>
          : <><div><small className="muted">출근 전</small><b className="big-num">{new Date(now).toLocaleTimeString('ko-KR', { timeZone: 'Asia/Seoul', hour: '2-digit', minute: '2-digit' })}</b></div><button className="primary big" onClick={() => clock('in')}>출근</button></>}
      </section>}
      <div className="grid2">
        <section className="card">
          <h2>급여명세서</h2>
          {(d?.payslips ?? []).map((p: any) => (
            <button key={p.id} className="list-btn" onClick={() => setSlip(p.id)}>
              <span><b>{p.period.slice(0, 4)}년 {Number(p.period.slice(4))}월</b> <small className="muted">{p.store_name}</small></span>
              <span className="tnum">{won(p.net_pay)}원 {!p.viewed_at && <Badge tone="accent">새 명세서</Badge>}</span>
            </button>))}
          {d?.payslips?.length === 0 && <Empty>발행된 명세서가 없습니다.</Empty>}
        </section>
        <section className="card table-wrap">
          <h2>최근 근무</h2>
          <table className="tbl compact"><thead><tr><th>일자</th><th>출근</th><th>퇴근</th><th className="num">근무</th></tr></thead>
            <tbody>{(d?.records ?? []).map((w: any) => <tr key={w.id}><td>{w.work_date}</td><td>{dt(w.clock_in)}</td><td>{w.clock_out ? dt(w.clock_out) : <Badge tone="warn">근무 중</Badge>}</td>
              <td className="num">{Math.round((w.regular_minutes + w.overtime_minutes) / 6) / 10}h</td></tr>)}</tbody></table>
        </section>
      </div>
      {slip && <PayslipModal id={slip} onClose={() => { setSlip(null); me.reload() }} />}
      <Toast msg={msg} onDone={() => setMsg(null)} />
    </Page>
  )
}
