import { useState } from 'react'
import { api } from '../api'
import { useApi, Page, ErrorBox, Modal, Toast, Empty, Badge } from '../components/ui'
import { useSession } from '../session'
import { dt } from '../format'

const DAY = ['월', '화', '수', '목', '금', '토', '일']
const TYPE: Record<string, string> = { OPEN: '오픈', CLOSE: '마감', MID: '일반', HQ: '본사', CUSTOM: '수동' }
const ACTION: Record<string, string> = { GENERATE: '자동 생성', ADJUST: '수동 조정', DELETE: '삭제', LEAVE: '연차', DAYOFF: '휴무', SUBSTITUTE: '대체 근무', COPY: '지난주 복사' }
const kst = () => new Date(Date.now() + 9 * 3600e3)
const iso = (d: Date) => d.toISOString().slice(0, 10)
function mondayOf(d: Date) { const x = new Date(d); const w = (x.getUTCDay() + 6) % 7; x.setUTCDate(x.getUTCDate() - w); return x }
function addDays(d: Date, n: number) { const x = new Date(d); x.setUTCDate(x.getUTCDate() + n); return x }
const hours = (a: string, b: string) => { const [h1, m1] = a.split(':').map(Number), [h2, m2] = b.split(':').map(Number); let m = h2 * 60 + m2 - (h1 * 60 + m1); if (m <= 0) m += 1440; return m / 60 }

/** 매장별 근무표: 주간 / 4주(월간) · 자동 로테이션 · 휴무·연차 · 대체 근무 · 수동 조정(승인 메모) */
export function Schedule() {
  const s = useSession()
  const [mon, setMon] = useState(mondayOf(kst()))
  const [weeks, setWeeks] = useState<1 | 4>(1)
  const storeId = s.storeId !== 'ALL' ? s.storeId : ''
  const days = [...Array(7 * weeks)].map((_, i) => addDays(mon, i))
  const d = useApi<any>(storeId ? `/api/core/hr/schedule?storeId=${storeId}&from=${iso(days[0])}&to=${iso(days[days.length - 1])}` : null, [storeId, iso(mon), weeks])
  const [cell, setCell] = useState<any>(null)
  const [settings, setSettings] = useState(false)
  const [gen, setGen] = useState(false)
  const [msg, setMsg] = useState<string | null>(null)
  const [err, setErr] = useState<string | null>(null)
  const today = iso(kst())
  const items: any[] = d.data?.items ?? []
  const at = (emp: string, day: string) => items.find(x => x.employee_id === emp && x.work_date === day)
  const off = (emp: string, day: string) => (d.data?.dayoffs ?? []).find((x: any) => x.employee_id === emp && x.off_date === day)
  const sumH = (emp: string, from: string, to: string) => items.filter(x => x.employee_id === emp && x.work_date >= from && x.work_date <= to).reduce((t, x) => t + hours(x.start_time, x.end_time), 0)
  const needs = items.filter(x => x.needs_adjust).length
  const canEdit = d.data?.canEdit
  const hq = d.data?.siteType === 'HQ'
  const reload = (m: string) => { setMsg(m); d.reload() }
  if (!storeId) return <Page title="근무표"><div className="note warn">근무표는 매장별입니다. 상단에서 매장(또는 본사·베이커리)을 고르세요.</div></Page>
  const rebuild = async () => {
    setErr(null)
    try { const r: any = await api('/api/core/hr/schedule/generate', { body: { storeId, monday: iso(mondayOf(kst())), weeks: 4, from: today, memo: '퇴사·변경 반영 다시 배치' } }); reload(`오늘부터 다시 배치했습니다 (${r.created}칸)`) }
    catch (e: any) { setErr(e.message) }
  }
  return (
    <Page title="근무표" sub={`${s.currentStore?.name} · ${iso(days[0])} ~ ${iso(days[days.length - 1])}${hq ? ' · 본사는 평일 9시~18시 기본' : ''}`}
      actions={<>
        <div className="seg"><button className={weeks === 1 ? 'on' : ''} onClick={() => setWeeks(1)}>주간</button><button className={weeks === 4 ? 'on' : ''} onClick={() => setWeeks(4)}>4주</button></div>
        <button className="ghost" onClick={() => setMon(addDays(mon, -7 * weeks))} aria-label="이전">‹ 이전</button>
        <button className="ghost" onClick={() => setMon(mondayOf(kst()))}>이번 주</button>
        <button className="ghost" onClick={() => setMon(addDays(mon, 7 * weeks))} aria-label="다음">다음 ›</button>
        {canEdit && !hq && <button onClick={() => setSettings(true)}>근무 기준</button>}
        {canEdit && <button className="primary" onClick={() => setGen(true)}>자동 로테이션</button>}
      </>}>
      <ErrorBox error={d.error || err} />
      {needs > 0 && <div className="note warn">퇴사(또는 퇴사 예정) 직원의 근무 {needs}칸이 남아 있습니다. {canEdit && <button className="small" onClick={rebuild}>오늘부터 다시 배치</button>}</div>}
      <div className="legend-row">
        {Object.entries(TYPE).filter(([k]) => hq ? k === 'HQ' || k === 'CUSTOM' : k !== 'HQ').map(([k, v]) => <span key={k} className={`shift ${k}`}>{v}</span>)}
        <span className="shift off">휴무</span><span className="shift leave">연차</span>
        {d.data?.settings && !hq && <span className="muted">· 오픈 {d.data.settings.openTime.slice(0, 5)} · 마감 {d.data.settings.closeTime.slice(0, 5)} · 최번시 {d.data.settings.peakStart.slice(0, 5)}~{d.data.settings.peakEnd.slice(0, 5)} (최소 {d.data.settings.minPeak}명)</span>}
      </div>
      {d.data && (d.data.employees.length === 0 ? <Empty>재직 중인 직원이 없습니다.</Empty> : (
        <div className="card table-wrap">
          <table className={`sched ${weeks === 4 ? 'month' : ''}`}>
            <thead><tr><th className="emp">직원</th>{days.map((x, i) => <th key={i} className={iso(x) === today ? 'today' : ''}>{DAY[i % 7]}<br /><small>{iso(x).slice(5)}</small></th>)}<th>{weeks === 4 ? '4주 합계' : '주 합계'}</th></tr></thead>
            <tbody>{d.data.employees.map((e: any) => {
              const mine = e.id === d.data.myEmployeeId
              const total = sumH(e.id, iso(days[0]), iso(days[days.length - 1]))
              const cap = Number(e.weekly_hours) * weeks
              return (
                <tr key={e.id}>
                  <td className="emp"><b>{e.name}</b>{e.status !== 'ACTIVE' && <> <Badge tone="bad">퇴사</Badge></>}{mine && <> <Badge tone="accent">나</Badge></>}<br />
                    <small className="muted">{e.job_title ?? ''} · 주 {Number(e.weekly_hours)}h</small></td>
                  {days.map((x, i) => {
                    const day = iso(x), it = at(e.id, day), o = off(e.id, day)
                    const click = canEdit || mine
                    const content = it
                      ? <span className={`shift ${it.shift_type}`}>{TYPE[it.shift_type]}{weeks === 1 ? ` ${it.start_time}–${it.end_time}` : ''}{it.substitute_for_name && <><br /><small>대체: {it.substitute_for_name}</small></>}{it.adjusted_at && weeks === 1 && <><br /><small>조정됨</small></>}</span>
                      : o ? <span className={`shift ${o.kind === 'LEAVE' ? 'leave' : 'off'}`}>{o.kind === 'LEAVE' ? '연차' : '휴무'}</span> : null
                    return <td key={i} className={`${day === today ? 'today' : ''} ${it?.needs_adjust ? 'needs' : ''}`} title={it ? `${TYPE[it.shift_type]} ${it.start_time}–${it.end_time}${it.adjust_memo ? ` · 메모: ${it.adjust_memo}` : ''}` : o?.memo ?? ''}>
                      {click ? <button className={`cell ${it || o ? 'on' : ''}`} aria-label={`${e.name} ${day}`} onClick={() => setCell({ emp: e, day, it, off: o, mine, canEdit })}>{content ?? '+'}</button>
                        : content ?? <span className="muted">—</span>}
                    </td>
                  })}
                  <td className={`tnum ${total > cap ? 'overdue' : ''}`}>{total ? `${Math.round(total * 10) / 10}h` : ''}<br /><small className="muted">/ {cap}h</small></td>
                </tr>)
            })}</tbody>
          </table>
        </div>))}
      {d.data?.logs?.length > 0 && <section className="card table-wrap">
        <h2>조정 이력</h2>
        <table className="tbl compact"><thead><tr><th>시각</th><th>근무일</th><th>직원</th><th>구분</th><th>변경 전</th><th>변경 후</th><th>승인 메모</th></tr></thead>
          <tbody>{d.data.logs.map((l: any, i: number) => <tr key={i}><td>{dt(l.occurred_at)}</td><td>{l.work_date}</td><td>{l.employee_name ?? '전체'}</td><td>{ACTION[l.action] ?? l.action}</td>
            <td><small>{l.before_text ?? '—'}</small></td><td><small>{l.after_text ?? '—'}</small></td><td><small>{l.memo}</small></td></tr>)}</tbody></table>
      </section>}
      <p className="muted small">근무표는 계획입니다. 실제 출근·퇴근은 홈 화면에서 누르고 직원·근무 › 근무기록에서 확인합니다. 합계는 휴게 포함 시간이며 주 소정 시간을 넘으면 빨갛게 표시됩니다.</p>
      {cell && <CellModal cell={cell} onClose={() => setCell(null)} onDone={(m: string) => { setCell(null); reload(m) }} />}
      {settings && d.data && <SettingsModal storeId={storeId} cur={d.data.settings} onClose={() => setSettings(false)} onDone={(m: string) => { setSettings(false); reload(m) }} />}
      {gen && <GenerateModal storeId={storeId} monday={iso(mon)} hq={hq} onClose={() => setGen(false)} onDone={(m: string) => { setGen(false); setWeeks(4); reload(m) }} />}
      <Toast msg={msg} onDone={() => setMsg(null)} />
    </Page>
  )
}

function CellModal({ cell, onClose, onDone }: any) {
  const { emp, day, it, off, mine, canEdit } = cell
  const [tab, setTab] = useState<'edit' | 'leave' | 'off'>(canEdit ? (it ? 'edit' : off ? 'off' : 'edit') : 'off')
  const [f, setF] = useState({ shiftType: it?.shift_type ?? 'CUSTOM', startTime: it?.start_time ?? '09:00', endTime: it?.end_time ?? '18:00', memo: '' })
  const [subId, setSubId] = useState('')
  const [kind, setKind] = useState(off?.kind ?? 'OFF')
  const [offMemo, setOffMemo] = useState(off?.memo ?? '')
  const subs = useApi<any[]>(canEdit && it && tab === 'leave' ? `/api/core/hr/schedule/substitutes?employeeId=${emp.id}&date=${day}` : null, [tab])
  const [err, setErr] = useState<string | null>(null)
  const run = async (fn: () => Promise<any>, m: string) => { setErr(null); try { await fn(); onDone(m) } catch (e: any) { setErr(e.message) } }
  return (
    <Modal title={`${emp.name} · ${day}`} onClose={onClose}>
      {canEdit && <div className="seg small">
        <button className={tab === 'edit' ? 'on' : ''} onClick={() => setTab('edit')}>근무 조정</button>
        {it && <button className={tab === 'leave' ? 'on' : ''} onClick={() => setTab('leave')}>연차·대체</button>}
        <button className={tab === 'off' ? 'on' : ''} onClick={() => setTab('off')}>휴무 지정</button>
      </div>}
      {err && <div className="error-box">{err}</div>}
      {tab === 'edit' && canEdit && <form className="form" onSubmit={e => { e.preventDefault(); run(() => api('/api/core/hr/schedule', { method: 'PUT', body: { employeeId: emp.id, workDate: day, ...f } }), '근무를 조정했습니다') }}>
        {it?.adjust_memo && <p className="small muted">이전 조정 메모: {it.adjust_memo}</p>}
        <div className="row">
          <div><label htmlFor="sc-type">구분</label><select id="sc-type" value={f.shiftType} onChange={e => setF({ ...f, shiftType: e.target.value })}>{Object.entries(TYPE).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></div>
          <div><label htmlFor="sc-in">시작</label><input id="sc-in" required type="time" value={f.startTime} onChange={e => setF({ ...f, startTime: e.target.value })} /></div>
          <div><label htmlFor="sc-out">종료 (자정 넘으면 다음날)</label><input id="sc-out" required type="time" value={f.endTime} onChange={e => setF({ ...f, endTime: e.target.value })} /></div>
        </div>
        <label htmlFor="sc-memo">조정 사유 (승인 메모)</label><input id="sc-memo" required value={f.memo} placeholder="예: 본인 요청으로 오후 근무 변경, 점장 승인" onChange={e => setF({ ...f, memo: e.target.value })} />
        <p className="muted small">수동으로 조정한 칸은 자동 로테이션을 다시 돌려도 유지됩니다.</p>
        <div className="act-buttons">
          {it && <button type="button" className="danger" onClick={e => { const form = (e.target as HTMLElement).closest('form'); if (!f.memo) { form?.reportValidity(); return }
            run(() => api(`/api/core/hr/schedule/${it.id}?memo=${encodeURIComponent(f.memo)}`, { method: 'DELETE' }), '근무를 지웠습니다') }}>근무 지우기</button>}
          <button className="primary">저장</button>
        </div>
      </form>}
      {tab === 'leave' && it && <form className="form" onSubmit={e => { e.preventDefault(); run(() => api('/api/core/hr/schedule/leave', { body: { employeeId: emp.id, date: day, substituteId: subId || null, memo: f.memo } }), subId ? '연차 처리 · 대체 근무를 넣었습니다' : '연차 처리했습니다 (대체 없음)') }}>
        <p className="small">{TYPE[it.shift_type]} {it.start_time}–{it.end_time} 근무를 빼고 연차로 바꿉니다. 대체 인원을 고르면 같은 시간 근무가 들어갑니다.</p>
        <label htmlFor="lv-sub">대체 인원</label>
        <select id="lv-sub" value={subId} onChange={e => setSubId(e.target.value)}>
          <option value="">대체 없음</option>
          {(subs.data ?? []).map(x => <option key={x.id} value={x.id}>{x.name} · 이번 주 남은 {x.remaining_hours}h{x.enough ? '' : ' (시간 부족)'}</option>)}
        </select>
        {subs.data && subs.data.length === 0 && <p className="muted small">그날 근무·휴무가 없는 직원이 없습니다.</p>}
        <label htmlFor="lv-memo">사유 (승인 메모)</label><input id="lv-memo" required value={f.memo} placeholder="예: 병가(긴급), 점장 승인" onChange={e => setF({ ...f, memo: e.target.value })} />
        <button className="primary">연차 처리</button>
      </form>}
      {tab === 'off' && <form className="form" onSubmit={e => { e.preventDefault(); run(() => api('/api/core/hr/schedule/dayoff', { method: 'PUT', body: { employeeId: emp.id, date: day, kind, memo: offMemo } }), kind === 'LEAVE' ? '연차를 지정했습니다' : '휴무를 지정했습니다') }}>
        <p className="small muted">{mine && !canEdit ? '내 휴무·연차를 먼저 지정하면 자동 로테이션이 나머지 근무를 채웁니다.' : '이 날 근무가 있으면 빠집니다. 연차로 빠진 자리는 [연차·대체]에서 대체 인원을 넣으세요.'}</p>
        <div className="row">
          <div><label htmlFor="off-kind">구분</label><select id="off-kind" value={kind} onChange={e => setKind(e.target.value)}><option value="OFF">휴무 (희망)</option><option value="LEAVE">연차</option></select></div>
          <div><label htmlFor="off-memo">메모</label><input id="off-memo" value={offMemo} onChange={e => setOffMemo(e.target.value)} /></div>
        </div>
        <div className="act-buttons">
          {off && <button type="button" onClick={() => run(() => api(`/api/core/hr/schedule/dayoff/${off.id}`, { method: 'DELETE' }), '휴무를 취소했습니다')}>휴무 취소</button>}
          <button className="primary">저장</button>
        </div>
      </form>}
    </Modal>
  )
}

function SettingsModal({ storeId, cur, onClose, onDone }: any) {
  const [f, setF] = useState({ openTime: cur.openTime.slice(0, 5), closeTime: cur.closeTime.slice(0, 5), peakStart: cur.peakStart.slice(0, 5), peakEnd: cur.peakEnd.slice(0, 5),
    shiftHours: String(cur.shiftHours), minOpen: String(cur.minOpen), minClose: String(cur.minClose), minPeak: String(cur.minPeak) })
  const [err, setErr] = useState<string | null>(null)
  const set = (k: string, v: string) => setF({ ...f, [k]: v })
  const save = async () => {
    setErr(null)
    try { await api('/api/core/hr/schedule/settings', { method: 'PUT', body: { storeId, ...f, shiftHours: Number(f.shiftHours), minOpen: Number(f.minOpen), minClose: Number(f.minClose), minPeak: Number(f.minPeak) } }); onDone('근무 기준을 저장했습니다') }
    catch (e: any) { setErr(e.message) }
  }
  const t = (k: string, l: string) => <div><label htmlFor={`st-${k}`}>{l}</label><input id={`st-${k}`} required type="time" value={(f as any)[k]} onChange={e => set(k, e.target.value)} /></div>
  const n = (k: string, l: string) => <div><label htmlFor={`st-${k}`}>{l}</label><input id={`st-${k}`} required inputMode="decimal" value={(f as any)[k]} onChange={e => set(k, e.target.value)} /></div>
  return (
    <Modal title="근무 기준" onClose={onClose}>
      <form className="form" onSubmit={e => { e.preventDefault(); save() }}>
        <div className="row">{t('openTime', '오픈 시각')}{t('closeTime', '마감 시각')}</div>
        <div className="row">{t('peakStart', '최번시 시작')}{t('peakEnd', '최번시 끝')}</div>
        <div className="row">{n('shiftHours', '1회 근무 (시간, 휴게 포함)')}{n('minOpen', '오픈 최소 인원')}{n('minClose', '마감 최소 인원')}{n('minPeak', '최번시 최소 인원')}</div>
        <p className="muted small">오픈 근무 = 오픈 시각부터, 마감 근무 = 마감 시각까지, 일반 근무 = 최번시를 가운데 둔 시간. 최번시 인원은 오픈·마감 근무 중 최번시를 덮는 사람도 셉니다.</p>
        {err && <div className="error-box">{err}</div>}
        <button className="primary">저장</button>
      </form>
    </Modal>
  )
}

function GenerateModal({ storeId, monday, hq, onClose, onDone }: any) {
  const [weeks, setWeeks] = useState('4')
  const [res, setRes] = useState<any>(null)
  const [err, setErr] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const run = async () => {
    setErr(null); setBusy(true)
    try { setRes(await api('/api/core/hr/schedule/generate', { body: { storeId, monday, weeks: Number(weeks) } })) } catch (e: any) { setErr(e.message) } finally { setBusy(false) }
  }
  return (
    <Modal title="자동 로테이션" onClose={() => res ? onDone(`근무 ${res.created}칸을 만들었습니다`) : onClose()}>
      {!res ? <form className="form" onSubmit={e => { e.preventDefault(); run() }}>
        <p className="small">{monday}(월)부터 만듭니다. {hq ? '본사는 평일 9시~18시로 채웁니다.' : '먼저 지정한 휴무·연차는 비우고, 오픈 → 마감 → 최번시 순서로 직원별 주 소정 시간 안에서 채웁니다. 오픈·마감은 직원끼리 돌아가며 맡습니다.'}</p>
        <label htmlFor="gen-weeks">기간</label>
        <select id="gen-weeks" value={weeks} onChange={e => setWeeks(e.target.value)}><option value="1">1주</option><option value="2">2주</option><option value="4">4주</option></select>
        <p className="muted small">자동으로 만든 칸은 다시 만들고, 수동 조정한 칸은 그대로 둡니다.</p>
        {err && <div className="error-box">{err}</div>}
        <button className="primary" disabled={busy}>{busy ? '만드는 중…' : '만들기'}</button>
      </form> : <div className="form">
        <p>근무 <b>{res.created}칸</b>을 만들었습니다 ({res.from} ~ {res.to}).</p>
        {res.shortages.length > 0 ? <div className="note warn">인원이 모자란 칸 {res.shortages.length}개: {res.shortages.slice(0, 8).map((x: any) => `${x.date.slice(5)} ${x.slot}`).join(', ')}{res.shortages.length > 8 ? ' …' : ''} — 직원을 늘리거나 근무 기준을 조정하세요.</div>
          : <div className="note">모든 칸을 채웠습니다.</div>}
        <button className="primary" onClick={() => onDone(`근무 ${res.created}칸을 만들었습니다`)}>확인</button>
      </div>}
    </Modal>
  )
}
