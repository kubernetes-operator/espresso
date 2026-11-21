import { useEffect, useMemo, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { api } from '../api'
import { checkRequired } from '../components/enhance'
import { useApi, Page, Badge, ErrorBox, Toast } from '../components/ui'
import { useSession } from '../session'
import { won, num as comma } from '../format'

const STEPS = ['기본정보', '부가세·결산·로열티', '임대', '설비·투자', '초도 물류·단가', '인원', '결재선', '검토']
const GUIDE: Record<number, { tip: string; check: string[] }> = {
  1: { tip: '매장 코드는 보고서·문서번호에 쓰입니다. 권역은 1,000개 매장 확장 시 비교·담당 범위의 기준입니다.', check: ['사업자등록증 확인', '오픈 예정일 확정'] },
  2: { tip: '부가세 표시는 이 매장 계정들의 기본값입니다(계정별로 따로 지정 가능). 로열티 기준은 기본 VAT 제외 순매출이고, 필요하면 VAT 포함으로 바꿉니다.', check: ['가맹/직영 확인', '로열티 계약서 요율 확인', '5인 이상 사업장 여부 (연장·야간 가산)'] },
  3: { tip: '임대 계약을 넣으면 매달 [비용 › 월세·관리비 생성]으로 자동 생성되고, 보증금은 투자금에 합산됩니다.', check: ['임대차 계약서', '월세 부가세 여부'] },
  4: { tip: '가구·냉장고·냉동고·커피머신 같은 설비는 자산으로 등록되어 매월 감가상각됩니다. 모든 항목은 매장 투자 총액에 합산됩니다.', check: ['인테리어 견적·정산', '설비 구매 증빙'] },
  5: { tip: '기본 초도 패키지를 불러와 수량을 조정하세요. 완료하면 초도 물류 요청이 자동 생성되어 물류 처리 보드로 넘어갑니다. 매장 전용 단가·할인도 여기서 정합니다.', check: ['오픈 메뉴 확정', '보관 공간(냉장·냉동) 확인'] },
  6: { tip: '점주는 매장관리자의 상위입니다. 물류·인사 관리자는 이 매장을 담당할 사람만 고르세요(전 매장 담당자는 자동 포함).', check: ['점주 계정 생성', '매장관리자 계정 생성'] },
  7: { tip: '비워 두면 문서별 기본 결재선(전체 결재)을 따릅니다. 이 매장만 다르게 하려면 단계를 넣으세요.', check: [] },
  8: { tip: '완료하면 위 내용이 한 번에 반영되고 매장 상태가 "운영"으로 바뀝니다.', check: [] },
}

export function StoreWizardStart() {
  const nav = useNavigate()
  const regions = useApi<any[]>('/api/core/regions')
  const list = useApi<any[]>('/api/core/onboarding')
  const [f, setF] = useState({ code: '', name: '', regionId: '', businessType: 'FRANCHISE', siteType: 'STORE' })
  const [err, setErr] = useState<string | null>(null)
  const start = async () => { setErr(null); try { const r: any = await api('/api/core/onboarding', { body: { ...f, regionId: f.regionId || null } }); nav(`/admin/stores/wizard/${r.storeId}`) } catch (e: any) { setErr(e.message) } }
  return (
    <Page title="매장 생성" sub="총괄 관리자 · 8단계 안내에 따라 물류·구성을 함께 설정합니다">
      <section className="card form">
        <h2>새 매장 시작</h2>
        <div className="row">
          <div><label htmlFor="sw-code">매장 코드</label><input id="sw-code" required value={f.code} onChange={e => setF({ ...f, code: e.target.value.toUpperCase() })} placeholder="예: SS01" /></div>
          <div><label htmlFor="sw-name">매장명</label><input id="sw-name" required value={f.name} onChange={e => setF({ ...f, name: e.target.value })} placeholder="예: 성수점" /></div>
          <div><label htmlFor="sw-region">권역</label><select id="sw-region" required value={f.regionId} onChange={e => setF({ ...f, regionId: e.target.value })}><option value="">선택</option>{(regions.data ?? []).map(r => <option key={r.id} value={r.id}>{r.name}</option>)}</select></div>
          <div><label htmlFor="sw-site">구분</label><select id="sw-site" value={f.siteType} onChange={e => setF({ ...f, siteType: e.target.value })}><option value="STORE">매장</option><option value="BAKERY">베이커리</option></select></div>
          <div><label htmlFor="sw-type">형태</label><select id="sw-type" value={f.businessType} onChange={e => setF({ ...f, businessType: e.target.value })}><option value="FRANCHISE">가맹</option><option value="DIRECT">직영</option></select></div>
        </div>
        {err && <div className="error-box">{err}</div>}
        <button className="primary" onClick={e => { const b = (e.target as HTMLElement).closest('.card, .form, div'); if (b && checkRequired((b.parentElement ?? b) as HTMLElement).length) return; start() }}>시작</button>
      </section>
      {(list.data ?? []).length > 0 && <section className="card table-wrap"><h2>진행 이력</h2>
        <table className="tbl"><thead><tr><th>매장</th><th>단계</th><th>상태</th><th></th></tr></thead>
          <tbody>{list.data!.map(o => <tr key={o.id}><td>{o.name} <code>{o.code}</code></td><td>{o.current_step}/8 {STEPS[o.current_step - 1]}</td>
            <td>{o.completed_at ? <Badge tone="ok">완료</Badge> : <Badge tone="warn">작성 중</Badge>}</td>
            <td>{!o.completed_at && <button className="small" onClick={() => nav(`/admin/stores/wizard/${o.id}`)}>이어서</button>}</td></tr>)}</tbody></table></section>}
    </Page>
  )
}

export function StoreWizard() {
  const { id } = useParams()
  const nav = useNavigate()
  const s = useSession()
  const o = useApi<any>(`/api/core/onboarding/${id}`)
  const [step, setStep] = useState(1)
  const [data, setData] = useState<Record<string, any>>({})
  const [msg, setMsg] = useState<string | null>(null)
  const [err, setErr] = useState<string | null>(null)
  const [result, setResult] = useState<any>(null)
  useEffect(() => { if (o.data) { setData(o.data.step_data ?? {}); setStep(Math.min(o.data.current_step ?? 1, 8)) } }, [o.data])
  const cur = data[String(step)] ?? {}
  const set = (patch: any) => setData(d => ({ ...d, [String(step)]: { ...(d[String(step)] ?? {}), ...patch } }))
  const save = async (next?: number) => {
    setErr(null)
    try { await api(`/api/core/onboarding/${id}/steps/${step}`, { method: 'PUT', body: cur }); if (next) setStep(next); else setMsg('임시저장했습니다') } catch (e: any) { setErr(e.message) }
  }
  const complete = async () => {
    setErr(null)
    try { const r = await api(`/api/core/onboarding/${id}/complete`, { body: {} }); setResult(r); s.reloadStores() } catch (e: any) { setErr(e.message) }
  }
  if (!o.data) return <Page title="매장 생성"><ErrorBox error={o.error} /></Page>
  if (result) return (
    <Page title={`${o.data.name} 개설 완료`}>
      <section className="card"><h2>반영된 내용</h2><ul>{result.applied.map((a: string) => <li key={a}>{a}</li>)}</ul>
        <div className="act-buttons" style={{ justifyContent: 'flex-start' }}>
          {result.initialRequestId && <button className="primary" onClick={() => nav(`/logistics/${result.initialRequestId}`)}>초도 물류 요청 보기</button>}
          <button onClick={() => nav('/admin/stores')}>매장 관리로</button><button onClick={() => nav('/org')}>조직도 보기</button>
        </div></section>
    </Page>)
  const g = GUIDE[step]
  return (
    <Page title={`매장 생성 · ${o.data.name}`} sub={<><code>{o.data.code}</code> · {o.data.completed_at ? <Badge tone="ok">완료됨</Badge> : <Badge tone="warn">작성 중</Badge>}</>}
      actions={!o.data.completed_at && <button className="ghost" onClick={async () => { if (window.confirm('작성 중인 매장을 지울까요?')) { await api(`/api/core/onboarding/${id}`, { method: 'DELETE' }); nav('/admin/stores/new') } }}>작성 취소</button>}>
      <ol className="wizard-steps">{STEPS.map((t, i) => <li key={t} className={i + 1 === step ? 'on' : i + 1 < step ? 'done' : ''}><button onClick={() => setStep(i + 1)}>{i + 1}. {t}</button></li>)}</ol>
      <ErrorBox error={err} />
      <div className="wizard">
        <aside className="card wizard-guide"><b>안내</b><p className="small">{g.tip}</p>{g.check.length > 0 && <><b className="small">체크리스트</b><ul className="small">{g.check.map(c => <li key={c}>☐ {c}</li>)}</ul></>}</aside>
        <section className="card form">
          {step === 1 && <Step1 v={cur} set={set} />}
          {step === 2 && <Step2 v={cur} set={set} />}
          {step === 3 && <Step3 v={cur} set={set} />}
          {step === 4 && <Step4 v={cur} set={set} />}
          {step === 5 && <Step5 v={cur} set={set} />}
          {step === 6 && <Step6 v={cur} set={set} />}
          {step === 7 && <Step7 v={cur} set={set} />}
          {step === 8 && <Review data={data} />}
          <div className="act-buttons">
            {step > 1 && <button onClick={() => save(step - 1)}>이전</button>}
            {step < 8 && <button onClick={() => save()}>임시저장</button>}
            {step < 8 ? <button className="primary" onClick={() => save(step + 1)}>다음</button>
              : <button className="primary" onClick={complete}>매장 생성 완료</button>}
          </div>
        </section>
      </div>
      <Toast msg={msg} onDone={() => setMsg(null)} />
    </Page>
  )
}

const F = ({ id, label, children }: any) => <div><label htmlFor={id}>{label}</label>{children}</div>
const num = (v: string) => v.replace(/\D/g, '')

function Step1({ v, set }: any) {
  return (<>
    <div className="row">
      <F id="w1-name" label="매장명"><input id="w1-name" value={v.name ?? ''} onChange={e => set({ name: e.target.value })} /></F>
      <F id="w1-open" label="오픈일"><input id="w1-open" type="date" value={v.openDate ?? ''} onChange={e => set({ openDate: e.target.value })} /></F>
      <F id="w1-phone" label="전화"><input id="w1-phone" value={v.phone ?? ''} onChange={e => set({ phone: e.target.value })} /></F>
    </div>
    <F id="w1-addr" label="주소"><input id="w1-addr" value={v.address ?? ''} onChange={e => set({ address: e.target.value })} /></F>
    <div className="row">
      <F id="w1-biz" label="사업자등록번호"><input id="w1-biz" value={v.bizRegNo ?? ''} onChange={e => set({ bizRegNo: e.target.value })} placeholder="000-00-00000" /></F>
      <F id="w1-bizname" label="상호"><input id="w1-bizname" value={v.bizName ?? ''} onChange={e => set({ bizName: e.target.value })} /></F>
    </div>
  </>)
}
function Step2({ v, set }: any) {
  const type = v.royaltyType ?? 'RATE'
  return (<>
    <div className="row">
      <F id="w2-vat" label="부가세 표시 기본값"><select id="w2-vat" value={v.vatDisplayMode ?? 'EXCLUSIVE'} onChange={e => set({ vatDisplayMode: e.target.value })}><option value="EXCLUSIVE">VAT 별도</option><option value="INCLUSIVE">VAT 포함</option></select></F>
      <F id="w2-roy" label="로열티 유형"><select id="w2-roy" value={type} onChange={e => set({ royaltyType: e.target.value })}><option value="RATE">매출 %</option><option value="FIXED">정액</option><option value="RATE_PLUS_FIXED">% + 정액</option><option value="NONE">없음 (직영)</option></select></F>
      <F id="w2-base" label="로열티 기준"><select id="w2-base" value={v.royaltyBase ?? 'NET_SALES_EXCL_VAT'} onChange={e => set({ royaltyBase: e.target.value })}><option value="NET_SALES_EXCL_VAT">순매출 (VAT 제외)</option><option value="NET_SALES_INCL_VAT">순매출 (VAT 포함)</option></select></F>
    </div>
    <div className="row">
      {['RATE', 'RATE_PLUS_FIXED'].includes(type) && <F id="w2-rate" label="요율 (%)"><input id="w2-rate" inputMode="decimal" value={v.royaltyRatePct ?? ''} onChange={e => set({ royaltyRatePct: e.target.value, royaltyRate: e.target.value ? String(Number(e.target.value) / 100) : null })} /></F>}
      {['FIXED', 'RATE_PLUS_FIXED'].includes(type) && <F id="w2-fixed" label="정액 (원/월)"><input id="w2-fixed" inputMode="numeric" value={comma(v.royaltyFixed ?? '')} onChange={e => set({ royaltyFixed: num(e.target.value) })} /></F>}
    </div>
    <div className="chips">
      <label className="switch"><input type="checkbox" checked={v.fivePlus ?? true} onChange={e => set({ fivePlus: e.target.checked })} /><span>5인 이상 사업장 (연장·야간·휴일 가산)</span></label>
      <label className="switch"><input type="checkbox" checked={v.ownerPayrollVisible ?? false} onChange={e => set({ ownerPayrollVisible: e.target.checked })} /><span>점주 개인별 급여 열람 허용 (끄면 총액만)</span></label>
    </div>
  </>)
}
function Step3({ v, set }: any) {
  return (<>
    <F id="w3-lessor" label="임대인"><input id="w3-lessor" value={v.lessorName ?? ''} onChange={e => set({ lessorName: e.target.value })} /></F>
    <div className="row">
      <F id="w3-dep" label="보증금"><input id="w3-dep" inputMode="numeric" value={comma(v.deposit ?? '')} onChange={e => set({ deposit: num(e.target.value) })} /></F>
      <F id="w3-rent" label="월세 (공급가)"><input id="w3-rent" inputMode="numeric" value={comma(v.monthlyRent ?? '')} onChange={e => set({ monthlyRent: num(e.target.value) })} /></F>
      <F id="w3-fee" label="관리비"><input id="w3-fee" inputMode="numeric" value={comma(v.maintenanceFee ?? '')} onChange={e => set({ maintenanceFee: num(e.target.value) })} /></F>
      <F id="w3-day" label="납부일"><input id="w3-day" inputMode="numeric" value={v.paymentDay ?? '25'} onChange={e => set({ paymentDay: num(e.target.value) })} /></F>
    </div>
    <div className="row">
      <F id="w3-start" label="계약 시작"><input id="w3-start" type="date" value={v.startDate ?? ''} onChange={e => set({ startDate: e.target.value })} /></F>
      <F id="w3-end" label="계약 종료"><input id="w3-end" type="date" value={v.endDate ?? ''} onChange={e => set({ endDate: e.target.value })} /></F>
    </div>
  </>)
}
const ICAT: Record<string, string> = { INTERIOR: '인테리어', EQUIPMENT: '설비 (자산)', PREMIUM: '권리금', INITIAL_STOCK: '초도 물품', OTHER: '기타' }
function Step4({ v, set }: any) {
  const items: any[] = v.items ?? [{ category: 'INTERIOR', description: '인테리어 공사', amount: '' }, { category: 'EQUIPMENT', description: '에스프레소 머신', amount: '', asset: true }]
  const up = (i: number, p: any) => set({ items: items.map((x, j) => j === i ? { ...x, ...p } : x) })
  const total = items.reduce((t, x) => t + Number(x.amount || 0), 0)
  return (<>
    {items.map((x, i) => <div key={i} className="step-edit">
      <select aria-label="구분" value={x.category} onChange={e => up(i, { category: e.target.value, asset: e.target.value === 'EQUIPMENT' })}>{Object.entries(ICAT).map(([k, l]) => <option key={k} value={k}>{l}</option>)}</select>
      <input aria-label="내용" value={x.description} onChange={e => up(i, { description: e.target.value })} />
      <input aria-label="금액" inputMode="numeric" value={comma(x.amount)} placeholder="금액" onChange={e => up(i, { amount: num(e.target.value) })} style={{ width: 140 }} />
      <button type="button" className="ghost small" onClick={() => set({ items: items.filter((_, j) => j !== i) })}>✕</button></div>)}
    <button type="button" onClick={() => set({ items: [...items, { category: 'OTHER', description: '', amount: '' }] })}>+ 항목</button>
    <p className="small">합계 <b>{won(total)}</b>원 (임대 보증금은 3단계 값이 자동 합산)</p>
  </>)
}
function Step5({ v, set }: any) {
  const pkgs = useApi<any[]>('/api/core/catalog/packages')
  const items = useApi<any[]>('/api/core/catalog/items')
  const init: any[] = v.initialItems ?? []
  const prices: any[] = v.prices ?? []
  const name = (id: string) => items.data?.find(i => i.id === id)?.name ?? ''
  const base = (id: string) => items.data?.find(i => i.id === id)?.base_price ?? 0
  const total = useMemo(() => init.reduce((t, l) => t + Number(l.qty || 0) * base(l.itemId), 0), [init, items.data])
  const [add, setAdd] = useState('')
  return (<>
    <div className="row"><F id="w5-pkg" label="초도 패키지 불러오기"><select id="w5-pkg" value="" onChange={e => { const p = pkgs.data?.find(x => x.id === e.target.value); if (p) set({ initialItems: p.items.map((i: any) => ({ itemId: i.item_id, qty: String(Number(i.qty)) })) }) }}>
      <option value="">선택</option>{(pkgs.data ?? []).map(p => <option key={p.id} value={p.id}>{p.name} ({p.items.length}개)</option>)}</select></F>
      <F id="w5-add" label="품목 추가"><select id="w5-add" value={add} onChange={e => { setAdd(''); if (e.target.value && !init.some(l => l.itemId === e.target.value)) set({ initialItems: [...init, { itemId: e.target.value, qty: '1' }] }) }}>
        <option value="">선택</option>{(items.data ?? []).map(i => <option key={i.id} value={i.id}>{i.division_name} · {i.name}</option>)}</select></F></div>
    <table className="tbl compact"><thead><tr><th>초도 품목</th><th className="num">기준가</th><th className="num">수량</th><th>매장 단가</th><th>할인 %</th><th></th></tr></thead>
      <tbody>{init.map((l, i) => {
        const p = prices.find(x => x.itemId === l.itemId) ?? {}
        const setP = (patch: any) => set({ prices: [...prices.filter(x => x.itemId !== l.itemId), { itemId: l.itemId, ...p, ...patch }] })
        return <tr key={l.itemId}><td>{name(l.itemId)}</td><td className="num">{won(base(l.itemId))}</td>
          <td className="num"><input className="mini" aria-label="수량" value={l.qty} onChange={e => set({ initialItems: init.map((x, j) => j === i ? { ...x, qty: e.target.value } : x) })} /></td>
          <td><input className="mini" aria-label="매장 단가" placeholder="기준가" value={p.unitPrice ?? ''} onChange={e => setP({ unitPrice: num(e.target.value) || null })} /></td>
          <td><input className="mini" aria-label="할인율" placeholder="—" value={p.discountPct ?? ''} onChange={e => setP({ discountPct: e.target.value, discountType: e.target.value ? 'RATE' : null, discountValue: e.target.value ? String(Number(e.target.value) / 100) : null })} /></td>
          <td><button className="ghost small" onClick={() => set({ initialItems: init.filter((_, j) => j !== i) })}>✕</button></td></tr>
      })}</tbody></table>
    <p className="small">초도 물류 예상 금액(기준가·VAT 별도) <b>{won(total)}</b>원</p>
  </>)
}
function Step6({ v, set }: any) {
  const users = useApi<any[]>('/api/auth/users')
  const pick = (key: string, label: string, roleHint: string[]) => {
    const sel: string[] = v[key] ?? []
    return <F id={`w6-${key}`} label={label}>
      <div className="chips">{sel.map(id => <span key={id} className="chip">{users.data?.find(u => u.id === id)?.name}<button className="chip-x" onClick={() => set({ [key]: sel.filter(x => x !== id) })}>×</button></span>)}
        <select id={`w6-${key}`} value="" onChange={e => e.target.value && set({ [key]: [...sel, e.target.value] })}><option value="">+ 추가</option>
          {(users.data ?? []).filter(u => u.status === 'ACTIVE' && !sel.includes(u.id) && (roleHint.length === 0 || u.assignments.length === 0 || u.assignments.some((a: any) => roleHint.includes(a.role))))
            .map(u => <option key={u.id} value={u.id}>{u.name} ({u.loginId})</option>)}</select></div></F>
  }
  return (<>
    <p className="muted small">계정이 없으면 먼저 [설정 › 계정·등급]에서 만드세요. 등급이 없는 새 계정도 목록에 나옵니다.</p>
    {pick('ownerIds', '점주', ['OWNER'])}{pick('managerIds', '매장 관리자', ['STORE_MGR'])}{pick('staffIds', '일반 사원', ['STAFF'])}
    {pick('logisticsIds', '담당 물류 관리자 (매장 지정)', ['LOGISTICS_MGR'])}{pick('hrIds', '담당 인사 관리자 (매장 지정)', ['HR_MGR'])}
  </>)
}
const DOCS: Record<string, string> = { LOGISTICS_REQUEST: '물류 요청', STOCK_ADJUSTMENT: '손실·결손·사용', PAYROLL_RUN: '급여 확정', EXPENSE: '비용' }
const ROLES: Record<string, string> = { STORE_MGR: '매장 관리자', OWNER: '점주', LOGISTICS_MGR: '물류 관리자', HR_MGR: '인사 관리자', HQ_ADMIN: '총괄 관리자' }
function Step7({ v, set }: any) {
  const lines: any[] = v.lines ?? []
  const lineOf = (dt: string) => lines.find(l => l.docType === dt)
  const setLine = (dt: string, steps: any[]) => set({ lines: [...lines.filter(l => l.docType !== dt), { docType: dt, steps }] })
  return (<>{Object.entries(DOCS).map(([dt, label]) => {
    const steps: any[] = lineOf(dt)?.steps ?? []
    return <div key={dt} className="card soft"><b>{label}</b> {steps.length === 0 ? <Badge>기본 결재선</Badge> : <Badge tone="accent">매장 전용</Badge>}
      {steps.map((st, i) => <div key={i} className="step-edit"><span className="muted">{i + 1}</span>
        <select aria-label="등급" value={st.roleCode} onChange={e => setLine(dt, steps.map((x, j) => j === i ? { ...x, roleCode: e.target.value } : x))}>{Object.entries(ROLES).map(([k, l]) => <option key={k} value={k}>{l}</option>)}</select>
        <button className="ghost small" onClick={() => setLine(dt, steps.filter((_, j) => j !== i))}>✕</button></div>)}
      <button className="small" onClick={() => setLine(dt, [...steps, { approverType: 'ROLE', roleCode: 'HQ_ADMIN' }])}>+ 단계</button></div>
  })}</>)
}
function Review({ data }: any) {
  const d1 = data['1'] ?? {}, d2 = data['2'] ?? {}, d3 = data['3'] ?? {}, d4 = data['4'] ?? {}, d5 = data['5'] ?? {}, d6 = data['6'] ?? {}, d7 = data['7'] ?? {}
  const inv = (d4.items ?? []).reduce((t: number, x: any) => t + Number(x.amount || 0), 0) + Number(d3.deposit || 0)
  const rows: [string, string][] = [
    ['기본정보', `${d1.name ?? ''} · 오픈 ${d1.openDate ?? '미정'} · ${d1.address ?? '주소 미입력'}`],
    ['부가세·로열티', `${d2.vatDisplayMode === 'INCLUSIVE' ? 'VAT 포함' : 'VAT 별도'} · ${({ RATE: '매출 %', FIXED: '정액', RATE_PLUS_FIXED: '% + 정액', NONE: '없음' } as any)[d2.royaltyType ?? 'RATE']} ${d2.royaltyRatePct ? d2.royaltyRatePct + '%' : ''} ${d2.royaltyFixed ? won(d2.royaltyFixed) + '원' : ''} · ${(d2.fivePlus ?? true) ? '5인 이상' : '5인 미만'}`],
    ['임대', d3.monthlyRent ? `월세 ${won(d3.monthlyRent)} · 관리비 ${won(d3.maintenanceFee)} · 보증금 ${won(d3.deposit)}` : '입력 없음'],
    ['투자 총액', `${won(inv)}원`],
    ['초도 물류', `${(d5.initialItems ?? []).length}개 품목 · 매장 단가 ${(d5.prices ?? []).length}건`],
    ['인원', `점주 ${(d6.ownerIds ?? []).length} · 매장관리자 ${(d6.managerIds ?? []).length} · 사원 ${(d6.staffIds ?? []).length}`],
    ['결재선', (d7.lines ?? []).filter((l: any) => l.steps?.length).length ? `${(d7.lines ?? []).filter((l: any) => l.steps?.length).length}개 문서 매장 전용` : '기본 결재선'],
  ]
  return <table className="tbl"><tbody>{rows.map(([k, val]) => <tr key={k}><td><b>{k}</b></td><td>{val}</td></tr>)}</tbody></table>
}
