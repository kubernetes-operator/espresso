import { useState } from 'react'
import { api } from '../api'
import { useApi, Page, Badge, ErrorBox, Modal, Toast, Empty } from '../components/ui'
import { useSession } from '../session'
import { won, dt } from '../format'

export const EMP_TYPE: Record<string, string> = { REGULAR: '정규직', CONTRACT: '계약직', PART_TIME: '아르바이트', FREELANCER: '프리랜서(3.3%)' }
const thisMonth = () => new Date(Date.now() + 9 * 3600e3).toISOString().slice(0, 7)
const hm = (m: number) => `${Math.floor(m / 60)}:${String(m % 60).padStart(2, '0')}`

export function Hr() {
  const s = useSession()
  const all = s.storeId === 'ALL'
  const storeId = all ? '' : s.storeId
  const [tab, setTab] = useState<'emp' | 'work'>('emp')
  const [month, setMonth] = useState(thisMonth())
  const emps = useApi<any[]>(`/api/core/hr/employees${storeId ? `?storeId=${storeId}` : ''}`, [storeId])
  const work = useApi<any[]>(tab === 'work' ? `/api/core/hr/work?month=${month}${storeId ? `&storeId=${storeId}` : ''}` : null, [storeId, month, tab])
  const [edit, setEdit] = useState<any>(null)
  const [addWork, setAddWork] = useState(false)
  const [msg, setMsg] = useState<string | null>(null)
  const [err, setErr] = useState<string | null>(null)
  const canManage = s.has('HR_MGR', 'HQ_ADMIN')
  const canWork = s.has('HR_MGR', 'HQ_ADMIN', 'OWNER', 'STORE_MGR')
  const storeName = all ? '전체 (사이트별)' : s.stores.find(x => x.id === storeId)?.name
  const totals = (work.data ?? []).reduce((t, w) => ({ reg: t.reg + w.regular_minutes, ot: t.ot + w.overtime_minutes, night: t.night + w.night_minutes }), { reg: 0, ot: 0, night: 0 })
  return (
    <Page title="직원·근무" sub={`${storeName ?? ''} · 사번·연봉 등급·계약(시급/월급·주휴·4대보험·3.3%)과 근무기록`}
      actions={<>
        <div className="seg"><button className={tab === 'emp' ? 'on' : ''} onClick={() => setTab('emp')}>직원</button><button className={tab === 'work' ? 'on' : ''} onClick={() => setTab('work')}>근무기록</button></div>
        {tab === 'emp' && canManage && !all && <button className="primary" onClick={() => setEdit({})}>+ 직원</button>}
        {tab === 'work' && <input id="hr-month" type="month" aria-label="월" value={month} onChange={e => setMonth(e.target.value)} />}
        {tab === 'work' && canWork && !all && <button className="primary" onClick={() => setAddWork(true)}>+ 근무 입력</button>}
      </>}>
      <ErrorBox error={emps.error || work.error || err} />
      {tab === 'emp' ? (
        <div className="card table-wrap">
          <table className="tbl">
            <thead><tr>{all && <th>소속</th>}<th>사번</th><th>이름</th><th>직책</th><th>고용형태</th><th>급여</th><th className="num">주 소정</th><th>주휴</th><th>4대보험</th><th>입사</th><th>상태</th><th>앱 계정</th><th></th></tr></thead>
            <tbody>{(emps.data ?? []).map(e => (
              <tr key={e.id}>
                {all && <td><small>{e.store_name}</small></td>}
                <td><code>{e.emp_no}</code></td><td><b>{e.name}</b></td><td><small>{e.job_title ?? ''}</small></td><td>{EMP_TYPE[e.employment_type]}</td>
                <td>{e.base_wage == null ? <span className="muted">권한 없음</span>
                  : e.salary_grade ? <><Badge tone="info">{e.salary_grade}</Badge> 연 {won(e.annual_salary)} · 월 {won(e.base_wage)}원</>
                  : `${e.pay_type === 'HOURLY' ? '시급' : '월급'} ${won(e.base_wage)}원`}</td>
                <td className="num">{Number(e.weekly_contract_hours)}h</td><td>{e.weekly_holiday_pay ? '대상' : '—'}</td>
                <td>{e.income_tax_type === 'BIZ_3_3' ? '3.3%' : [e.national_pension && '연금', e.health_insurance && '건강', e.employment_insurance && '고용'].filter(Boolean).join('·') || '—'}</td>
                <td>{e.hire_date}</td><td><Badge tone={e.status === 'ACTIVE' ? 'ok' : 'neutral'}>{({ ACTIVE: '재직', LEAVE: '휴직', RESIGNED: '퇴사' } as any)[e.status]}</Badge></td>
                <td>{e.user_id ? <Badge tone="info">연결</Badge> : '—'}</td>
                <td>{canManage && e.base_wage != null && <button className="ghost small" onClick={() => setEdit(e)}>수정</button>}</td>
              </tr>))}</tbody>
          </table>
          {emps.data?.length === 0 && <Empty>직원이 없습니다.</Empty>}
        </div>
      ) : (<>
        <div className="grid3">
          <div className="card stat"><small>정규 근무</small><b>{hm(totals.reg)}</b></div>
          <div className="card stat"><small>연장</small><b>{hm(totals.ot)}</b></div>
          <div className="card stat"><small>야간 (22~06시)</small><b>{hm(totals.night)}</b></div>
        </div>
        <div className="card table-wrap">
          <table className="tbl">
            <thead><tr><th>일자</th>{all && <th>소속</th>}<th>직원</th><th>출근</th><th>퇴근</th><th className="num">휴게</th><th className="num">정규</th><th className="num">연장</th><th className="num">야간</th><th>입력</th><th></th></tr></thead>
            <tbody>{(work.data ?? []).map(w => (
              <tr key={w.id}><td>{w.work_date}</td>{all && <td><small>{w.store_name}</small></td>}<td>{w.employee_name}</td><td>{dt(w.clock_in)}</td><td>{w.clock_out ? dt(w.clock_out) : <Badge tone="warn">근무 중</Badge>}</td>
                <td className="num">{w.break_minutes}분</td><td className="num">{hm(w.regular_minutes)}</td><td className="num">{w.overtime_minutes ? hm(w.overtime_minutes) : ''}</td><td className="num">{w.night_minutes ? hm(w.night_minutes) : ''}</td>
                <td>{w.source === 'APP' ? <Badge tone="info">앱</Badge> : '수기'} {!w.confirmed_at && w.clock_out && <Badge tone="warn">미확인</Badge>}</td>
                <td className="nowrap">{!w.confirmed_at && w.clock_out && canWork && <button className="small" onClick={async () => { try { await api(`/api/core/hr/work/${w.id}/confirm`, { body: {} }); work.reload() } catch (e: any) { setErr(e.message) } }}>확인</button>}
                  {s.has('HR_MGR', 'HQ_ADMIN', 'STORE_MGR') && <button className="ghost small" onClick={async () => { try { await api(`/api/core/hr/work/${w.id}`, { method: 'DELETE' }); work.reload() } catch (e: any) { setErr(e.message) } }}>삭제</button>}</td></tr>))}</tbody>
          </table>
        </div>
      </>)}
      {all && <p className="muted small">전체 화면은 사이트별 목록입니다. 직원 추가·근무 입력은 상단에서 매장(또는 본사·베이커리)을 고르세요.</p>}
      {edit && <EmpModal emp={edit} storeId={edit.store_id ?? storeId} onClose={() => setEdit(null)} onDone={() => { setEdit(null); setMsg('저장했습니다'); emps.reload() }} />}
      {addWork && <WorkModal emps={emps.data ?? []} onClose={() => setAddWork(false)} onDone={() => { setAddWork(false); setMsg('근무를 입력했습니다'); work.reload() }} />}
      <Toast msg={msg} onDone={() => setMsg(null)} />
    </Page>
  )
}

function EmpModal({ emp, storeId, onClose, onDone }: any) {
  const isNew = !emp.id
  // 이 사이트 계정만 연결 후보로 (선택 메뉴는 해당 지점 값만)
  const users = useApi<any[]>(`/api/auth/users?storeId=${storeId}`)
  const grades = useApi<any[]>('/api/core/hr/salary-grades')
  const allow = emp.allowances ? JSON.parse(emp.allowances) : {}
  const [f, setF] = useState({ name: emp.name ?? '', userId: emp.user_id ?? '', jobTitle: emp.job_title ?? '', hireDate: emp.hire_date ?? new Date().toISOString().slice(0, 10), employmentType: emp.employment_type ?? 'PART_TIME',
    payType: emp.salary_grade ? 'GRADE' : emp.pay_type ?? 'HOURLY', salaryGrade: emp.salary_grade ?? '', baseWage: emp.base_wage != null && !emp.salary_grade ? String(emp.base_wage) : '',
    weeklyHours: emp.weekly_contract_hours != null ? String(Number(emp.weekly_contract_hours)) : '40',
    weeklyHolidayPay: emp.weekly_holiday_pay ?? true, nationalPension: emp.national_pension ?? true, healthInsurance: emp.health_insurance ?? true, employmentInsurance: emp.employment_insurance ?? true,
    taxDependents: String(emp.tax_dependents ?? 1), mealAllowance: String(allow.meal ?? ''), status: emp.status ?? 'ACTIVE', resignDate: emp.resign_date ?? '' })
  const [etc, setEtc] = useState<{ name: string; amount: string }[]>((allow.etc ?? []).map((x: any) => ({ name: x.name, amount: String(x.amount) })))
  const [err, setErr] = useState<string | null>(null)
  const set = (k: string, v: any) => setF({ ...f, [k]: v })
  const grade = (grades.data ?? []).find(g => g.code === f.salaryGrade)
  const groups = [...new Map((grades.data ?? []).map(g => [g.grade_group, g.group_name])).entries()]
  const save = async () => {
    setErr(null)
    const byGrade = f.payType === 'GRADE'
    const body = { ...f, storeId, userId: f.userId || null, payType: byGrade ? 'MONTHLY' : f.payType, salaryGrade: byGrade ? f.salaryGrade : null,
      baseWage: byGrade ? null : Number(f.baseWage), weeklyHours: Number(f.weeklyHours), taxDependents: Number(f.taxDependents), mealAllowance: f.mealAllowance ? Number(f.mealAllowance) : 0,
      otherAllowances: etc.filter(x => x.name && Number(x.amount) > 0).map(x => ({ name: x.name, amount: Number(x.amount) })), resignDate: f.status === 'RESIGNED' ? f.resignDate || null : null }
    try { if (isNew) await api('/api/core/hr/employees', { body }); else await api(`/api/core/hr/employees/${emp.id}`, { method: 'PUT', body }); onDone() } catch (e: any) { setErr(e.message) }
  }
  const chk = (k: string, l: string) => <label className="switch"><input type="checkbox" checked={(f as any)[k]} onChange={e => set(k, e.target.checked)} /><span>{l}</span></label>
  return (
    <Modal title={isNew ? '직원 등록' : `직원 수정 · ${emp.name} (${emp.emp_no})`} onClose={onClose} wide>
      <form className="form" onSubmit={e => { e.preventDefault(); save() }}>
        <div className="row">
          <div><label htmlFor="em-name">이름</label><input id="em-name" value={f.name} onChange={e => set('name', e.target.value)} /></div>
          <div><label htmlFor="em-title">직책</label><input id="em-title" value={f.jobTitle} placeholder="예: 사원" onChange={e => set('jobTitle', e.target.value)} /></div>
          <div><label htmlFor="em-hire">입사일</label><input id="em-hire" type="date" value={f.hireDate} disabled={!isNew} onChange={e => set('hireDate', e.target.value)} /></div>
          <div><label htmlFor="em-user">앱 계정 연결</label><select id="em-user" value={f.userId} onChange={e => set('userId', e.target.value)}><option value="">없음</option>{(users.data ?? []).map(u => <option key={u.id} value={u.id}>{u.name} ({u.loginId})</option>)}</select></div>
        </div>
        {isNew && <p className="muted small">사번은 저장할 때 자동으로 붙습니다 (00000001부터 연번).</p>}
        <div className="row">
          <div><label htmlFor="em-type">고용형태</label><select id="em-type" value={f.employmentType} onChange={e => set('employmentType', e.target.value)}>{Object.entries(EMP_TYPE).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></div>
          <div><label htmlFor="em-pay">급여 방식</label><select id="em-pay" value={f.payType} onChange={e => set('payType', e.target.value)}><option value="GRADE">연봉 등급</option><option value="HOURLY">시급</option><option value="MONTHLY">월급(직접 입력)</option></select></div>
          {f.payType === 'GRADE' ? <div><label htmlFor="em-grade">연봉 등급</label>
            <select id="em-grade" value={f.salaryGrade} onChange={e => set('salaryGrade', e.target.value)}>
              <option value="">선택</option>
              {groups.map(([g, n]) => <optgroup key={g} label={`${n} (${g}1~)`}>{(grades.data ?? []).filter(x => x.grade_group === g).map(x => <option key={x.code} value={x.code}>{x.code} · 연 {won(x.annual_salary)}</option>)}</optgroup>)}
            </select></div>
            : <div><label htmlFor="em-wage">{f.payType === 'HOURLY' ? '시급' : '월급'} (원)</label><input id="em-wage" inputMode="numeric" value={f.baseWage} onChange={e => set('baseWage', e.target.value.replace(/\D/g, ''))} /></div>}
          <div><label htmlFor="em-wh">주 소정근로 (시간)</label><input id="em-wh" inputMode="decimal" value={f.weeklyHours} onChange={e => set('weeklyHours', e.target.value)} /></div>
        </div>
        {f.payType === 'GRADE' && grade && <div className="note">연봉 <b>{won(grade.annual_salary)}원</b> · 월 급여 <b>{won(grade.monthly)}원</b> ({grade.group_name} {grade.step}단계)</div>}
        {f.employmentType !== 'FREELANCER' && <div className="chips">{chk('weeklyHolidayPay', '주휴수당')}{chk('nationalPension', '국민연금')}{chk('healthInsurance', '건강보험')}{chk('employmentInsurance', '고용보험')}</div>}
        <div className="row">
          <div><label htmlFor="em-dep">부양가족 수 (소득세)</label><input id="em-dep" inputMode="numeric" value={f.taxDependents} onChange={e => set('taxDependents', e.target.value)} /></div>
          <div><label htmlFor="em-meal">식대 비과세 (원/월)</label><input id="em-meal" inputMode="numeric" value={f.mealAllowance} onChange={e => set('mealAllowance', e.target.value.replace(/\D/g, ''))} /></div>
          {!isNew && <div><label htmlFor="em-st">상태</label><select id="em-st" value={f.status} onChange={e => set('status', e.target.value)}><option value="ACTIVE">재직</option><option value="LEAVE">휴직</option><option value="RESIGNED">퇴사</option></select></div>}
          {!isNew && f.status === 'RESIGNED' && <div><label htmlFor="em-resign">퇴사일</label><input id="em-resign" type="date" value={f.resignDate} onChange={e => set('resignDate', e.target.value)} /></div>}
        </div>
        <b className="small">기타 비과세 항목 (비급여)</b>
        {etc.map((x, i) => (
          <div key={i} className="allow-row">
            <input aria-label="항목 이름" placeholder="예: 자가운전보조금" value={x.name} onChange={e => setEtc(etc.map((y, j) => j === i ? { ...y, name: e.target.value } : y))} />
            <input aria-label="월 금액" inputMode="numeric" placeholder="원/월" value={x.amount} onChange={e => setEtc(etc.map((y, j) => j === i ? { ...y, amount: e.target.value.replace(/\D/g, '') } : y))} />
            <button type="button" className="ghost small" aria-label="항목 삭제" onClick={() => setEtc(etc.filter((_, j) => j !== i))}>✕</button>
          </div>))}
        <button type="button" className="ghost small" onClick={() => setEtc([...etc, { name: '', amount: '' }])}>+ 기타 항목</button>
        <p className="muted small">급여 조건을 바꾸면 오늘부터 새 계약으로 적용되고 이전 계약은 이력으로 남습니다. 비과세 한도는 항목별 세법 기준을 확인해 넣으세요. 주민등록번호·계좌번호는 데모에서 받지 않습니다.</p>
        {err && <div className="error-box">{err}</div>}
        <button className="primary">저장</button>
      </form>
    </Modal>
  )
}

function WorkModal({ emps, onClose, onDone }: any) {
  const [f, setF] = useState({ employeeId: emps[0]?.id ?? '', workDate: new Date(Date.now() + 9 * 3600e3).toISOString().slice(0, 10), clockIn: '09:00', clockOut: '18:00', breakMinutes: '60', memo: '' })
  const [err, setErr] = useState<string | null>(null)
  const save = async () => { setErr(null); try { await api('/api/core/hr/work', { body: { ...f, breakMinutes: Number(f.breakMinutes) } }); onDone() } catch (e: any) { setErr(e.message) } }
  return (
    <Modal title="근무 입력" onClose={onClose}>
      <form className="form" onSubmit={e => { e.preventDefault(); save() }}>
        <label htmlFor="wk-emp">직원</label><select id="wk-emp" value={f.employeeId} onChange={e => setF({ ...f, employeeId: e.target.value })}>{emps.map((x: any) => <option key={x.id} value={x.id}>{x.name}</option>)}</select>
        <label htmlFor="wk-date">일자</label><input id="wk-date" type="date" value={f.workDate} onChange={e => setF({ ...f, workDate: e.target.value })} />
        <div className="row">
          <div><label htmlFor="wk-in">출근</label><input id="wk-in" type="time" value={f.clockIn} onChange={e => setF({ ...f, clockIn: e.target.value })} /></div>
          <div><label htmlFor="wk-out">퇴근 (자정 넘으면 다음날로 계산)</label><input id="wk-out" type="time" value={f.clockOut} onChange={e => setF({ ...f, clockOut: e.target.value })} /></div>
          <div><label htmlFor="wk-brk">휴게 (분)</label><input id="wk-brk" inputMode="numeric" value={f.breakMinutes} onChange={e => setF({ ...f, breakMinutes: e.target.value })} /></div>
        </div>
        <label htmlFor="wk-memo">메모</label><input id="wk-memo" value={f.memo} onChange={e => setF({ ...f, memo: e.target.value })} />
        {err && <div className="error-box">{err}</div>}
        <button className="primary">저장</button>
      </form>
    </Modal>
  )
}
