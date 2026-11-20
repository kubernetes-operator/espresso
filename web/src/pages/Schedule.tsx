import { useState } from 'react'
import { api } from '../api'
import { useApi, Page, ErrorBox, Modal, Toast, Empty } from '../components/ui'
import { useSession } from '../session'

const DAY = ['월', '화', '수', '목', '금', '토', '일']
const kst = () => new Date(Date.now() + 9 * 3600e3)
const iso = (d: Date) => d.toISOString().slice(0, 10)
function mondayOf(d: Date) { const x = new Date(d); const w = (x.getUTCDay() + 6) % 7; x.setUTCDate(x.getUTCDate() - w); return x }
function addDays(d: Date, n: number) { const x = new Date(d); x.setUTCDate(x.getUTCDate() + n); return x }
const hours = (a: string, b: string) => { const [h1, m1] = a.split(':').map(Number), [h2, m2] = b.split(':').map(Number); let m = h2 * 60 + m2 - (h1 * 60 + m1); if (m <= 0) m += 1440; return m / 60 }

/** 매장별 주간 근무표 (계획). 실제 출퇴근은 직원·근무 › 근무기록 */
export function Schedule() {
  const s = useSession()
  const [mon, setMon] = useState(mondayOf(kst()))
  const storeId = s.storeId !== 'ALL' ? s.storeId : ''
  const days = [...Array(7)].map((_, i) => addDays(mon, i))
  const d = useApi<any>(storeId ? `/api/core/hr/schedule?storeId=${storeId}&from=${iso(days[0])}&to=${iso(days[6])}` : null, [storeId, iso(mon)])
  const [edit, setEdit] = useState<any>(null)
  const [msg, setMsg] = useState<string | null>(null)
  const [err, setErr] = useState<string | null>(null)
  const today = iso(kst())
  const at = (emp: string, day: string) => (d.data?.items ?? []).find((x: any) => x.employee_id === emp && x.work_date === day)
  const weekHours = (emp: string) => (d.data?.items ?? []).filter((x: any) => x.employee_id === emp).reduce((t: number, x: any) => t + hours(x.start_time, x.end_time), 0)
  const copyLast = async () => {
    setErr(null)
    try { const r: any = await api('/api/core/hr/schedule/copy-week', { body: { storeId, fromMonday: iso(addDays(mon, -7)), toMonday: iso(mon) } }); setMsg(`지난주 근무 ${r.copied}건을 복사했습니다`); d.reload() }
    catch (e: any) { setErr(e.message) }
  }
  if (!storeId) return <Page title="근무표"><div className="note warn">근무표는 매장별입니다. 상단에서 매장(또는 본사·베이커리)을 고르세요.</div></Page>
  return (
    <Page title="근무표" sub={`${s.currentStore?.name} · ${iso(days[0])} ~ ${iso(days[6])}`}
      actions={<>
        <button className="ghost" onClick={() => setMon(addDays(mon, -7))} aria-label="지난주">‹ 지난주</button>
        <button className="ghost" onClick={() => setMon(mondayOf(kst()))}>이번 주</button>
        <button className="ghost" onClick={() => setMon(addDays(mon, 7))} aria-label="다음 주">다음 주 ›</button>
        {d.data?.canEdit && <button onClick={copyLast}>지난주 복사</button>}
      </>}>
      <ErrorBox error={d.error || err} />
      {d.data && (d.data.employees.length === 0 ? <Empty>재직 중인 직원이 없습니다.</Empty> : (
        <div className="card table-wrap">
          <table className="sched">
            <thead><tr><th className="emp">직원</th>{days.map((x, i) => <th key={i} className={iso(x) === today ? 'today' : ''}>{DAY[i]} {iso(x).slice(5)}</th>)}<th>주 합계</th></tr></thead>
            <tbody>{d.data.employees.map((e: any) => (
              <tr key={e.id}>
                <td className="emp"><b>{e.name}</b><br /><small className="muted">{e.job_title ?? ''} {e.emp_no}</small></td>
                {days.map((x, i) => {
                  const it = at(e.id, iso(x))
                  return <td key={i} className={iso(x) === today ? 'today' : ''}>
                    {d.data.canEdit
                      ? <button className={`cell ${it ? 'on' : ''}`} aria-label={`${e.name} ${iso(x)} 근무`} onClick={() => setEdit({ emp: e, day: iso(x), it })}>
                          {it ? <>{it.start_time}–{it.end_time}{it.memo && <><br /><small>{it.memo}</small></>}</> : '+'}</button>
                      : it ? <span>{it.start_time}–{it.end_time}{it.memo && <><br /><small className="muted">{it.memo}</small></>}</span> : <span className="muted">—</span>}
                  </td>
                })}
                <td className="tnum">{weekHours(e.id) ? `${Math.round(weekHours(e.id) * 10) / 10}h` : ''}</td>
              </tr>))}</tbody>
          </table>
        </div>))}
      <p className="muted small">근무표는 계획입니다. 실제 출근·퇴근은 각 직원이 홈 화면에서 누르고, 직원·근무 › 근무기록에서 확인합니다. 주 합계는 휴게 시간을 빼지 않은 값입니다.</p>
      {edit && <SlotModal edit={edit} onClose={() => setEdit(null)} onDone={(m: string) => { setEdit(null); setMsg(m); d.reload() }} />}
      <Toast msg={msg} onDone={() => setMsg(null)} />
    </Page>
  )
}

function SlotModal({ edit, onClose, onDone }: any) {
  const [f, setF] = useState({ startTime: edit.it?.start_time ?? '09:00', endTime: edit.it?.end_time ?? '18:00', memo: edit.it?.memo ?? '' })
  const [err, setErr] = useState<string | null>(null)
  const save = async () => {
    setErr(null)
    try { await api('/api/core/hr/schedule', { method: 'PUT', body: { employeeId: edit.emp.id, workDate: edit.day, ...f } }); onDone('근무표를 저장했습니다') } catch (e: any) { setErr(e.message) }
  }
  const remove = async () => {
    setErr(null)
    try { await api(`/api/core/hr/schedule/${edit.it.id}`, { method: 'DELETE' }); onDone('근무를 지웠습니다') } catch (e: any) { setErr(e.message) }
  }
  return (
    <Modal title={`${edit.emp.name} · ${edit.day}`} onClose={onClose}>
      <form className="form" onSubmit={e => { e.preventDefault(); save() }}>
        <div className="row">
          <div><label htmlFor="sc-in">시작</label><input id="sc-in" type="time" value={f.startTime} onChange={e => setF({ ...f, startTime: e.target.value })} /></div>
          <div><label htmlFor="sc-out">종료 (자정 넘으면 다음날)</label><input id="sc-out" type="time" value={f.endTime} onChange={e => setF({ ...f, endTime: e.target.value })} /></div>
        </div>
        <label htmlFor="sc-memo">메모</label><input id="sc-memo" value={f.memo} placeholder="예: 오픈, 마감" onChange={e => setF({ ...f, memo: e.target.value })} />
        {err && <div className="error-box">{err}</div>}
        <div className="act-buttons">{edit.it && <button type="button" className="danger" onClick={remove}>지우기</button>}<button className="primary">저장</button></div>
      </form>
    </Modal>
  )
}
