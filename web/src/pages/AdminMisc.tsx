import { useState } from 'react'
import { api } from '../api'
import { useApi, Page, ErrorBox, Toast, Badge } from '../components/ui'
import { ROLE_NAMES } from '../format'

/** 급여 요율 (연도별) */
export function AdminPayrollRates() {
  const [year, setYear] = useState(2026)
  const rates = useApi<any[]>(`/api/core/payroll/rates?year=${year}`, [year])
  const [msg, setMsg] = useState<string | null>(null)
  const [err, setErr] = useState<string | null>(null)
  const save = async (code: string, value: string) => {
    setErr(null)
    try { await api('/api/core/payroll/rates', { method: 'PUT', body: { year, code, value } }); setMsg(`${code} 저장`); rates.reload() } catch (e: any) { setErr(e.message) }
  }
  return (
    <Page title="급여 요율" sub="연도별 최저임금·4대보험·가산율 · 저장 후 다시 계산하는 급여부터 적용"
      actions={<input id="rt-year" type="number" aria-label="연도" value={year} onChange={e => setYear(Number(e.target.value))} style={{ width: 110 }} />}>
      <div className="note warn">데모 요율은 임의 입력값입니다. 운영 전 고용노동부·국민연금공단·건강보험공단·국세청 고시값으로 확인해 바꿔 주세요. 근로소득 간이세액표는 hr.income_tax_table 에 등록하면 추정 대신 표를 씁니다.</div>
      <ErrorBox error={rates.error || err} />
      <div className="card table-wrap"><table className="tbl">
        <thead><tr><th>코드</th><th>설명</th><th className="num">값</th><th className="num">상한</th><th className="num">하한</th></tr></thead>
        <tbody>{(rates.data ?? []).map(r => <tr key={r.code}><td><code>{r.code}</code></td><td>{r.memo}</td>
          <td className="num"><input className="mini" style={{ width: 110 }} aria-label={r.code} defaultValue={Number(r.value)} onBlur={e => e.target.value !== String(Number(r.value)) && save(r.code, e.target.value)} /></td>
          <td className="num">{r.upper_limit ?? '—'}</td><td className="num">{r.lower_limit ?? '—'}</td></tr>)}</tbody>
      </table></div>
      <Toast msg={msg} onDone={() => setMsg(null)} />
    </Page>
  )
}

const SEC: Record<string, string> = { KPI: '핵심 지표', PNL: '손익 요약', DIVISION: '구분별', LOGISTICS: '물류', STOCK: '재고·손실', LABOR_TOTAL: '인건비 총액', LABOR_DETAIL: '개인별 인건비', RENT_OPEX: '임대·운영비', ROYALTY: '로열티', SETTLEMENT: '결산액', APPROVALS: '결재 현황', OWN_PAYSLIP: '내 급여' }
const ROLES = ['HQ_ADMIN', 'OWNER', 'STORE_MGR', 'LOGISTICS_MGR', 'HR_MGR', 'STAFF', 'SUPERVISOR']

/** 리포트 섹션 노출 (등급 × 섹션) */
export function AdminReportSections() {
  const m = useApi<any[]>('/api/core/reports/sections')
  const [err, setErr] = useState<string | null>(null)
  const on = (r: string, s: string) => (m.data ?? []).find(x => x.role_code === r && x.section === s)?.visible
  const toggle = async (r: string, s: string) => {
    setErr(null)
    try { await api('/api/core/reports/sections', { method: 'PUT', body: { role: r, section: s, visible: !on(r, s) } }); m.reload() } catch (e: any) { setErr(e.message) }
  }
  return (
    <Page title="리포트 섹션 노출" sub="등급별로 1장 리포트에서 볼 수 있는 섹션 · 개인별 인건비는 매장 설정(점주·매장관리자 열람)과 함께 판단">
      <ErrorBox error={m.error || err} />
      <div className="card table-wrap"><table className="tbl">
        <thead><tr><th>섹션</th>{ROLES.map(r => <th key={r} className="num">{ROLE_NAMES[r]}</th>)}</tr></thead>
        <tbody>{Object.entries(SEC).map(([k, l]) => <tr key={k}><td>{l}</td>{ROLES.map(r => <td key={r} className="num">
          <input type="checkbox" aria-label={`${ROLE_NAMES[r]} ${l}`} checked={!!on(r, k)} disabled={r === 'HQ_ADMIN'} onChange={() => toggle(r, k)} style={{ width: 20, height: 20, minHeight: 0, accentColor: 'var(--accent)' }} /></td>)}</tr>)}</tbody>
      </table></div>
    </Page>
  )
}

/** 계정과목 · 외부(더존) 코드 매핑 */
export function AdminAccounts() {
  const list = useApi<any[]>('/api/stl/accounts')
  const [msg, setMsg] = useState<string | null>(null)
  const [err, setErr] = useState<string | null>(null)
  const [nw, setNw] = useState({ code: '', name: '', pnlSection: 'OPERATING', externalCode: '' })
  const save = async (code: string, body: any) => { setErr(null); try { await api(`/api/stl/accounts/${code}`, { method: 'PUT', body }); setMsg('저장했습니다'); list.reload() } catch (e: any) { setErr(e.message) } }
  const add = async () => { setErr(null); try { await api('/api/stl/accounts', { body: nw }); setMsg('추가했습니다'); setNw({ code: '', name: '', pnlSection: 'OPERATING', externalCode: '' }); list.reload() } catch (e: any) { setErr(e.message) } }
  return (
    <Page title="계정과목" sub="결산·전표 내보내기 기준 · 외부 코드를 넣으면 더존 업로드 CSV에 함께 나갑니다">
      <ErrorBox error={list.error || err} />
      <section className="card form-inline">
        <div><label htmlFor="ac-code">코드</label><input id="ac-code" value={nw.code} onChange={e => setNw({ ...nw, code: e.target.value })} style={{ width: 90 }} /></div>
        <div className="grow"><label htmlFor="ac-name">이름</label><input id="ac-name" value={nw.name} onChange={e => setNw({ ...nw, name: e.target.value })} /></div>
        <div><label htmlFor="ac-sec">손익 구분</label><select id="ac-sec" value={nw.pnlSection} onChange={e => setNw({ ...nw, pnlSection: e.target.value })}>
          <option value="OPERATING">운영비</option><option value="UTILITY">공과금</option><option value="RENT">임대</option><option value="NON_OP_INCOME">영업외 수익</option><option value="NON_OP_EXPENSE">영업외 비용</option></select></div>
        <div><label htmlFor="ac-ext">외부 코드</label><input id="ac-ext" value={nw.externalCode} onChange={e => setNw({ ...nw, externalCode: e.target.value })} style={{ width: 90 }} /></div>
        <button className="primary" disabled={!nw.code || !nw.name} onClick={add}>+ 계정 추가</button>
      </section>
      <div className="card table-wrap"><table className="tbl">
        <thead><tr><th>코드</th><th>이름</th><th>유형</th><th>손익 구분</th><th>외부 코드</th><th></th></tr></thead>
        <tbody>{(list.data ?? []).map(a => <tr key={a.code}><td><code>{a.code}</code></td><td>{a.name}</td><td>{a.type}</td><td>{a.pnl_section ?? '—'}</td>
          <td><input className="mini" style={{ width: 90, textAlign: 'left' }} aria-label={`${a.name} 외부 코드`} defaultValue={a.external_code ?? ''} onBlur={e => e.target.value !== (a.external_code ?? '') && save(a.code, { externalCode: e.target.value })} /></td>
          <td>{a.is_system && <Badge>시스템</Badge>}</td></tr>)}</tbody>
      </table></div>
      <Toast msg={msg} onDone={() => setMsg(null)} />
    </Page>
  )
}
